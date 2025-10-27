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

package com.roncatech.vcat.test_vectors;

import android.os.Environment;
import android.util.Log;

import com.roncatech.vcat.models.TestVectorManifests;
import com.roncatech.vcat.models.TestVectorMediaAsset;
import com.roncatech.vcat.tools.StorageManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class SetupLocalVectors {

    private final static String TAG = "SetupLocalVectors";

    /**
     * Moves each temp‑downloaded media asset into /sdcard/vcat/media/…
     * preserving any sub‑folders under “media/”.  If the file already exists,
     * it’s checksum‑verified; if it matches, we reuse it, otherwise we fail.
     *
     * @param tempAssets the list of media assets (manifest + tempFile)
     * @return a new list of TestVectorMediaAsset pointing to the permanent files
     * @throws IOException on any I/O or checksum mismatch
     */
    public static Map<UUID, TestVectorMediaAsset> relocateMediaAssets(TestVectorManifests.PlaylistManifest playlist,
            Map<UUID, TestVectorMediaAsset> assets
    ) {
        Map<UUID, TestVectorMediaAsset> result = new HashMap<>();

        File baseDir = StorageManager.getFolder(StorageManager.VCATFolder.MEDIA);

        for(TestVectorManifests.PlaylistAsset cur : playlist.mediaAssets){
            TestVectorMediaAsset curAsset = assets.get(cur.uuid);
            TestVectorManifests.VideoManifest vm = curAsset.manifest;
            String assetUrl = vm.mediaAsset.url;

            // 1) compute relative path under “media/…”
            String relPath;
            int idx = assetUrl.indexOf("/media/");
            if (idx >= 0) {
                relPath = assetUrl.substring(idx + "/media/".length());
            } else {
                relPath = vm.mediaAsset.name;
            }

            // 2) dest = /sdcard/vcat/media/<relPath>
            File dest = new File(baseDir, relPath);

            // ensure parent dirs exist
            File parent = dest.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                //throw new IOException("Failed to create dir: " + parent);
                Log.e(TAG, "Unable to create folder(s)");
                return result;
            }

            // 3) if dest exists, verify checksum
            if (dest.exists()) {
                if (!DownloadTestVectors.verifyChecksum(dest, vm.mediaAsset.checksum)) {
                    Log.e(TAG, "Local file exists and chacksum does not match:  "+ dest.toString());
                    return result;
                    //throw new IOException("Checksum mismatch on existing file: " + dest);
                }
            } else {
                // 4) copy temp → dest
                try {
                    copyFile(curAsset.localPath, dest);
                    // 5) verify freshly copied
                    if (!DownloadTestVectors.verifyChecksum(dest, vm.mediaAsset.checksum)) {
                        Log.e(TAG, "Unexpected checksum error after copying file");
                        // should delete the copy
                        return result;
                        //throw new IOException("Checksum mismatch after copy: " + dest);
                    }
                } catch (IOException e){
                    Log.e(TAG, "Exception during copy of "+
                            curAsset.localPath.toString() + ". "+
                            e.getLocalizedMessage()
                    );
                }
            }

            // 6) add to result list
            result.put(cur.uuid,new TestVectorMediaAsset(vm, dest));
        }

        return result;
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
            }
        }
    }
}

