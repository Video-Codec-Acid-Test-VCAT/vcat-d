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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.roncatech.libvcat.decoder.VcatDecoderManager;
import com.roncatech.vcat.decoder_plugin_api.VcatDecoderPlugin;

import java.io.ByteArrayOutputStream;
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

    private static void loadPlugin(Context context, String fileName) {
        try {
            // Stream AAR from assets to a writable file so ZipFile can open it
            File aarFile = new File(context.getCacheDir(), fileName);
            copyAsset(context, ASSET_DIR + "/" + fileName, aarFile);

            // Read plugin-manifest.json from inside the AAR
            String pluginClass = readPluginClass(aarFile);
            if (pluginClass == null) {
                Log.w(TAG, fileName + ": missing or invalid plugin-manifest.json, skipping");
                return;
            }

            // Extract classes.dex (injected by dist task) to code cache as a standalone file
            File dexFile = extractEntry(aarFile, "classes.dex",
                    new File(context.getCodeCacheDir(), fileName + ".dex"));
            dexFile.setReadOnly();

            // Extract native libs for the current ABI
            File nativeLibDir = extractNativeLibs(context, aarFile, fileName);

            // Load the plugin class via its own DexClassLoader; parent = app classloader
            // so shared interfaces (VcatDecoderPlugin, etc.) resolve to the same Class objects.
            // librarySearchPath points to extracted .so files so that System.loadLibrary()
            // calls made from within plugin classes use the DexClassLoader's findLibrary().
            DexClassLoader loader = new DexClassLoader(
                    dexFile.getAbsolutePath(),
                    context.getCodeCacheDir().getAbsolutePath(),
                    nativeLibDir.getAbsolutePath(),
                    context.getClassLoader()
            );

            // Trigger native library loading through the DexClassLoader's namespace by
            // reflectively calling the plugin's library loader class before any native calls.
            String libLoaderClass = pluginClass.substring(0, pluginClass.lastIndexOf('.') + 1)
                    + "VvdecLibrary";
            try {
                Class<?> libLoader = loader.loadClass(libLoaderClass);
                libLoader.getMethod("load").invoke(null);
                Log.d(TAG, "Native library loaded via " + libLoaderClass);
            } catch (Exception e) {
                Log.d(TAG, "No library loader class found (" + libLoaderClass + "), skipping");
            }

            Class<?> clazz = loader.loadClass(pluginClass);
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

    private static String readPluginClass(File aarFile) throws IOException {
        try (ZipFile zip = new ZipFile(aarFile)) {
            ZipEntry entry = zip.getEntry("assets/plugin-manifest.json");
            if (entry == null) return null;
            byte[] bytes;
            try (InputStream is = zip.getInputStream(entry)) {
                bytes = readFully(is);
            }
            JsonObject obj = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            return obj.get("pluginClass").getAsString();
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
