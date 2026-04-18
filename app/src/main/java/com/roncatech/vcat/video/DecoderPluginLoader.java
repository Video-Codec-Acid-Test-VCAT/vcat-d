/*
 * VCAT (Video Codec Acid Test)
 *
 * SPDX-FileCopyrightText: Copyright (C) 2020-2025 VCAT authors and RoncaTech
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.roncatech.vcat.video;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.roncatech.libvcat.decoder.VcatDecoderManager;
import com.roncatech.vcat.decoder_plugin_api.VcatDecoderPlugin;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import dalvik.system.DexClassLoader;

/** Discovers and registers decoder plugins bundled as AARs in assets/decoder-plugins/. */
public final class DecoderPluginLoader {

    private static final String TAG = "DecoderPluginLoader";
    private static final String ASSET_DIR = "decoder-plugins";

    private DecoderPluginLoader() {}

    public static void loadAll(Context context) {
        String[] files;
        try {
            files = context.getAssets().list(ASSET_DIR);
        } catch (IOException e) {
            Log.e(TAG, "Failed to list " + ASSET_DIR, e);
            return;
        }
        if (files == null || files.length == 0) {
            Log.i(TAG, "No decoder plugins found in assets/" + ASSET_DIR);
            return;
        }
        for (String fileName : files) {
            if (fileName.endsWith(".aar")) {
                loadPlugin(context, fileName);
            }
        }
    }

    private static class PluginManifest {
        final String pluginClass;
        @Nullable final String loaderClass;
        PluginManifest(String pluginClass, @Nullable String loaderClass) {
            this.pluginClass = pluginClass;
            this.loaderClass = loaderClass;
        }
    }

    private static void loadPlugin(Context context, String fileName) {
        try {
            // Stream AAR from assets to a writable file so ZipFile can open it
            File aarFile = new File(context.getCacheDir(), fileName);
            copyAsset(context, ASSET_DIR + "/" + fileName, aarFile);

            // Read plugin-manifest.json from inside the AAR
            PluginManifest manifest = readManifest(aarFile);
            if (manifest == null) {
                Log.w(TAG, fileName + ": missing or invalid plugin-manifest.json, skipping");
                return;
            }

            // Extract classes.dex (injected by dist task) to code cache as a standalone file
            File dexFile = extractEntry(aarFile, "classes.dex",
                    new File(context.getCodeCacheDir(), fileName + ".dex"));
            dexFile.setReadOnly();

            // Extract native libs for the current ABI
            final File nativeLibDir = extractNativeLibs(context, aarFile, fileName);

            // Diagnostics: log what was extracted so failures are visible in logcat
            File[] soFiles = nativeLibDir.listFiles((d, n) -> n.endsWith(".so"));
            if (soFiles == null || soFiles.length == 0) {
                List<String> pluginAbis = getAvailableAbis(aarFile);
                String deviceAbi = Build.SUPPORTED_ABIS[0];
                if (!pluginAbis.isEmpty() && !pluginAbis.contains(deviceAbi)) {
                    Log.e(TAG, fileName + ": ABI mismatch — plugin provides " + pluginAbis
                            + " but device is " + deviceAbi
                            + ". Request an " + deviceAbi + " build from the plugin provider.");
                } else {
                    Log.e(TAG, fileName + ": no .so files extracted"
                            + " (device ABI=" + deviceAbi
                            + ", plugin ABIs=" + pluginAbis + ")");
                }
            } else {
                for (File so : soFiles) {
                    Log.d(TAG, fileName + ": extracted " + so.getName() + " (" + so.length() + " B)");
                }
            }

            // Load the plugin class via its own DexClassLoader; parent = app classloader
            // so shared interfaces (VcatDecoderPlugin, etc.) resolve to the same Class objects.
            // Override findLibrary() to directly probe nativeLibDir by filename — the default
            // BaseDexClassLoader search can silently miss the file on some devices/OS versions.
            DexClassLoader loader = new DexClassLoader(
                    dexFile.getAbsolutePath(),
                    context.getCodeCacheDir().getAbsolutePath(),
                    nativeLibDir.getAbsolutePath(),
                    context.getClassLoader()) {
                @Override
                public String findLibrary(String name) {
                    String path = super.findLibrary(name);
                    if (path != null) return path;
                    File f = new File(nativeLibDir, System.mapLibraryName(name));
                    Log.d(TAG, "findLibrary(" + name + ") fallback: " + f.getAbsolutePath()
                            + " exists=" + f.exists());
                    return f.exists() ? f.getAbsolutePath() : null;
                }
            };

            // Trigger native library loading through the DexClassLoader's namespace before
            // any native calls. The manifest must declare "loaderClass" for plugins that
            // bundle native libraries; plugins without native code omit this field.
            if (manifest.loaderClass != null) {
                try {
                    Class<?> libLoader = loader.loadClass(manifest.loaderClass);
                    libLoader.getMethod("load").invoke(null);
                    Log.i(TAG, fileName + ": native library loaded OK");
                } catch (java.lang.reflect.InvocationTargetException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    Log.e(TAG, fileName + ": native library load FAILED — " + cause.getMessage()
                            + " | device ABI=" + Build.SUPPORTED_ABIS[0]
                            + " | plugin=" + manifest.pluginClass);
                } catch (Exception e) {
                    Log.e(TAG, fileName + ": could not invoke loader class "
                            + manifest.loaderClass + " — " + e.getMessage());
                }
            }

            Class<?> clazz = loader.loadClass(manifest.pluginClass);
            VcatDecoderPlugin plugin = (VcatDecoderPlugin) clazz.getDeclaredConstructor().newInstance();
            boolean registered = VcatDecoderManager.getInstance().registerDecoder(plugin);
            Log.i(TAG, (registered ? "Registered" : "Already registered") + " decoder plugin: " + plugin.getId());
        } catch (Exception e) {
            Log.e(TAG, "Failed to load decoder plugin: " + fileName, e);
        }
    }

    private static void copyAsset(Context ctx, String assetPath, File dest) throws IOException {
        try (InputStream in = ctx.getAssets().open(assetPath);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    @Nullable
    private static PluginManifest readManifest(File aarFile) throws IOException {
        try (ZipFile zip = new ZipFile(aarFile)) {
            ZipEntry entry = zip.getEntry("assets/plugin-manifest.json");
            if (entry == null) return null;
            byte[] bytes;
            try (InputStream is = zip.getInputStream(entry)) {
                bytes = readFully(is);
            }
            JsonObject obj = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            String pluginClass = obj.get("pluginClass").getAsString();
            String loaderClass = obj.has("loaderClass")
                    ? obj.get("loaderClass").getAsString() : null;
            return new PluginManifest(pluginClass, loaderClass);
        }
    }

    private static byte[] readFully(InputStream is) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        int n;
        while ((n = is.read(tmp)) > 0) buf.write(tmp, 0, n);
        return buf.toByteArray();
    }

    private static File extractEntry(File aarFile, String entryName, File dest) throws IOException {
        if (dest.exists()) {
            dest.setWritable(true);
            dest.delete();
        }
        try (ZipFile zip = new ZipFile(aarFile);
             InputStream is = zip.getInputStream(zip.getEntry(entryName));
             OutputStream os = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        }
        return dest;
    }

    private static List<String> getAvailableAbis(File aarFile) {
        List<String> abis = new ArrayList<>();
        try (ZipFile zip = new ZipFile(aarFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith("jni/") && name.endsWith(".so")) {
                    String[] parts = name.split("/");
                    if (parts.length >= 2 && !abis.contains(parts[1])) {
                        abis.add(parts[1]);
                    }
                }
            }
        } catch (IOException e) {
            // ignore — best effort
        }
        return abis;
    }

    private static File extractNativeLibs(Context ctx, File aarFile, String pluginName)
            throws IOException {
        String abi = Build.SUPPORTED_ABIS[0];
        String prefix = "jni/" + abi + "/";
        File nativeDir = ctx.getDir("jni_" + pluginName.replace(".aar", ""), Context.MODE_PRIVATE);

        try (ZipFile zip = new ZipFile(aarFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith(prefix) && name.endsWith(".so")) {
                    File soFile = new File(nativeDir, name.substring(prefix.length()));
                    try (InputStream is = zip.getInputStream(entry);
                         OutputStream os = new FileOutputStream(soFile)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                    }
                }
            }
        }
        return nativeDir;
    }
}
