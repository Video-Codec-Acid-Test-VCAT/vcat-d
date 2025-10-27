/*
 * VCAT (Video Codec Acid Test)
 *
 * SPDX-FileCopyrightText: Copyright (C) 2020-2025 VCAT authors and RoncaTech
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of VCAT.
 *
 * VCAT is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VCAT is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VCAT. If not, see <https://www.gnu.org/licenses/gpl-3.0.html>.
 *
 * For proprietary/commercial use cases, a written GPL-3.0 waiver or
 * a separate commercial license is required from RoncaTech LLC.
 *
 * All VCAT artwork is owned exclusively by RoncaTech LLC. Use of VCAT logos
 * and artwork is permitted for the purpose of discussing, documenting,
 * or promoting VCAT itself. Any other use requires prior written permission
 * from RoncaTech LLC.
 *
 * Contact: legal@roncatech.com
 */

package com.roncatech.vcat.tools;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

public class UriUtils {
    public static String fileNameFromURI(Uri uri){
        return new File(uri.getPath()).getName();
    }

    public static String fileNameFromURI(String uriString){
        String[] parts = uriString.split("/");
        return parts[parts.length - 1];
    }

    public static String makeBaseUrlWithUri(String url) {
        URI uri = URI.create(url);
        URI base = uri.resolve("./");   // drops everything after the last '/'
        return base.toString();         // includes trailing slash
    }

    /**
     * Starting at `dir`, descend into path components and return the final DocumentFile.
     * Returns null if any segment doesn't exist.
     */
    @Nullable
    private static DocumentFile findInTree(DocumentFile dir, String relativePath) {
        String normalizedPath = normalizeRelativePath(relativePath);
        String[] parts = normalizedPath.split("/");
        DocumentFile current = dir;
        for (String p : parts) {
            if (p.isEmpty()) continue;
            current = current.findFile(p);
            if (current == null) return null;
        }
        return current;
    }

    /**
     * Collapse “.” and “..” segments in a relative path.
     * E.g. "../manifests/../media/foo.mp4" → "media/foo.mp4"
     */
    private static String normalizeRelativePath(String assetUrl) {
        LinkedList<String> stack = new LinkedList<>();
        for (String segment : assetUrl.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                // skip
            } else if (segment.equals("..")) {
                if (!stack.isEmpty()) stack.removeLast();
            } else {
                stack.add(segment);
            }
        }
        return TextUtils.join("/", stack);
    }


    /**
     * Resolve a relative assetUrl against a baseUri, which may be:
     *  - an http(s) URL
     *  - a file:// URI
     *  - a SAF tree:// URI (content://.../tree/...)
     *
     * @param context  only needed for file→Uri conversions
     * @param baseUri  the folder or catalog URL
     * @param assetUrl a relative path like "manifests/foo.json" or "media/bar.mp4"
     * @return a Uri you can pass to OkHttp (if http/https) or ContentResolver.openInputStream()
     */
    public static Uri resolveUri(Context context, String baseUri, String assetUrl) throws IOException {
        return resolveUri(context, Uri.parse(baseUri), assetUrl);
    }
    public static Uri resolveUri(Context ctx, Uri baseUri, String assetUrl) throws IOException {
        String scheme = baseUri.getScheme();
        if ("http".equals(scheme) || "https".equals(scheme)) {
            // full-blown URL resolution
            URI b = URI.create(baseUri.toString());
            URI r = b.resolve(assetUrl);
            return Uri.parse(r.toString());
        }
        else if ("file".equals(scheme)) {
            // simple file on disk
            File dir = new File(baseUri.getPath());
            File child = new File(dir, assetUrl);
            return Uri.fromFile(child);
        }
        else if ("content".equals(scheme) && DocumentsContract.isTreeUri(baseUri)) {
            DocumentFile treeRoot = DocumentFile.fromTreeUri(ctx, baseUri);
            DocumentFile target = findInTree(treeRoot, assetUrl);
            if (target == null || !target.canRead()) {
                throw new IOException("Cannot find or read “" + assetUrl + "” under “" + baseUri + "”");
            }
            return target.getUri();
        }
        else {
            throw new IllegalArgumentException("Cannot resolve against URI: " + baseUri);
        }
    }
}
