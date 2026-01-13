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

package com.roncatech.vcat.models;

import android.util.Log;

import com.google.android.exoplayer2.util.MimeTypes;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import com.roncatech.vcat.BuildConfig;
import com.roncatech.vcat.tools.VideoDecoderEnumerator;
import com.roncatech.vcat.video.StrictRenderersFactoryV2;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;


public class DecoderConfig {

    // Decoder Configuration Map (Empty = Use App Default)
    public final Map<VideoDecoderEnumerator.MimeType, String> decoderConfig = new HashMap<>();

    public DecoderConfig() {
        // default all decoders to the first provided by ExoPlayer except AV1 is vcat-dav1d

        decoderConfig.put(VideoDecoderEnumerator.MimeType.AV1, StrictRenderersFactoryV2.VCAT_DAV1D);
        decoderConfig.put(VideoDecoderEnumerator.MimeType.VVC, StrictRenderersFactoryV2.VCAT_VVDEC);
        VideoDecoderEnumerator.DecoderSet vp9Set = VideoDecoderEnumerator.getDecodersForMimeType(VideoDecoderEnumerator.MimeType.VP9);

        if (vp9Set.decoders.size() > 0) {
            decoderConfig.put(VideoDecoderEnumerator.MimeType.VP9, vp9Set.decoders.get(0));
        }

        VideoDecoderEnumerator.DecoderSet hevcSet = VideoDecoderEnumerator.getDecodersForMimeType(VideoDecoderEnumerator.MimeType.H265);

        if(hevcSet.decoders.size() > 0){
            decoderConfig.put(VideoDecoderEnumerator.MimeType.H265, hevcSet.decoders.get(0));
        }

        VideoDecoderEnumerator.DecoderSet h264Set = VideoDecoderEnumerator.getDecodersForMimeType(VideoDecoderEnumerator.MimeType.H264);

        if(h264Set.decoders.size() > 0){
            decoderConfig.put(VideoDecoderEnumerator.MimeType.H264, h264Set.decoders.get(0));
        }

    }
    public DecoderConfig(DecoderConfig copyFrom){
        this.decoderConfig.putAll(copyFrom.decoderConfig);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DecoderConfig)) return false;
        DecoderConfig that = (DecoderConfig) o;
        return Objects.equals(this.decoderConfig, that.decoderConfig);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(decoderConfig);
    }

    // Get decoder for a MIME type (returns "default" if not set)
    public String getDecoder(VideoDecoderEnumerator.MimeType mimeType) {
        if(decoderConfig.containsKey(mimeType)) {
            return decoderConfig.get(mimeType);
        }

        return "";
    }

    public String getDecoder(String mimeType){
        switch (mimeType) {
            case MimeTypes.VIDEO_H264:
                return getDecoder(VideoDecoderEnumerator.MimeType.H264);
            case MimeTypes.VIDEO_H265:
                return getDecoder(VideoDecoderEnumerator.MimeType.H265);
            case MimeTypes.VIDEO_AV1:
                return getDecoder(VideoDecoderEnumerator.MimeType.AV1);
            case MimeTypes.VIDEO_VP9:
                return getDecoder(VideoDecoderEnumerator.MimeType.VP9);
            case "video/vvc":
                return getDecoder(VideoDecoderEnumerator.MimeType.VVC);
        }

        return "";
    }

    // Set a custom decoder for a MIME type
    public void setDecoder(VideoDecoderEnumerator.MimeType mimeType, String decoder) {
        decoderConfig.put(mimeType, decoder);
    }

    public void removeDecoder(VideoDecoderEnumerator.MimeType mimeType){
        if (contains(mimeType)) {
            this.decoderConfig.remove(mimeType);
        }
    }

    public boolean contains(VideoDecoderEnumerator.MimeType mimeType){
        return decoderConfig.containsKey(mimeType);
    }


    // ✅ Static nested Comparator class
    public static class Comparator implements java.util.Comparator<DecoderConfig> {
        @Override
        public int compare(DecoderConfig config1, DecoderConfig config2) {
            // Compare sizes first (quick exit if different)
            if (config1.decoderConfig.size() != config2.decoderConfig.size()) {
                return Integer.compare(config1.decoderConfig.size(), config2.decoderConfig.size());
            }

            // Compare actual decoder assignments
            for (Map.Entry<VideoDecoderEnumerator.MimeType, String> entry : config1.decoderConfig.entrySet()) {
                VideoDecoderEnumerator.MimeType mimeType = entry.getKey();
                String decoder1 = entry.getValue();
                String decoder2 = config2.decoderConfig.get(mimeType);

                //ensure that never null
                decoder2 = decoder2 == null ? "" : decoder2;

                if (!Objects.equals(decoder1, decoder2)) {
                    return decoder1.compareTo(decoder2);
                }
            }

            // Check if config2 has extra keys that config1 does not
            for (VideoDecoderEnumerator.MimeType mimeType : config2.decoderConfig.keySet()) {
                if (!config1.decoderConfig.containsKey(mimeType)) {
                    return -1;
                }
            }

            return 0;  // Both configs are identical
        }
    }

    public String getDecoderCfg() {
        return decoderConfig.toString();
    }

    public static final Comparator comparator = new Comparator();

    // ✅ Convert object to JSON string
    public String toJson() {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        return gson.toJson(this);
    }

    // ✅ Load object from JSON string
    public DecoderConfig fromJson(String json) {
        if (json != null && !json.isEmpty()) {
            Gson gson = new Gson();
            return gson.fromJson(json, DecoderConfig.class);
        }
        return new DecoderConfig();
    }


}