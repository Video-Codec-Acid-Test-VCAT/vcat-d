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

package com.roncatech.vcat.decoder_plugin_api;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.util.MimeTypes;

import java.util.ArrayList;
import java.util.List;

public class VideoConfiguration {
    public final String mimeType;
    public final int nalUnitLengthFieldLength;
    public final int width;
    public final int height;
    public final @Nullable String codecs;
    public final @C.ColorSpace int colorSpace;
    public final @C.ColorRange int colorRange;
    public final @C.ColorTransfer int colorTransfer;
    public final float pixelWidthHeightRatio;
    public final List<byte[]> initializationData;

    private VideoConfiguration(
            String mimeType,
            int nalUnitLengthFieldLength,
            int width,
            int height,
            int colorSpace,
            int colorRange,
            int colorTransfer,
            float pixelWidthHeightRatio,
            String codecs,
            List<byte[]> initializationData){
        this.mimeType = mimeType;
        this.nalUnitLengthFieldLength = nalUnitLengthFieldLength;
        this.width = width;
        this.height = height;
        this.colorSpace = colorSpace;
        this.colorRange = colorRange;
        this.colorTransfer = colorTransfer;
        this.pixelWidthHeightRatio = pixelWidthHeightRatio;
        this.codecs = codecs;
        this.initializationData = new ArrayList<>(initializationData);
    }

    public static class Builder{
        public String mimeType = MimeTypes.VIDEO_UNKNOWN;
        public int nalUnitLengthFieldLength = C.LENGTH_UNSET;
        public int width = Format.NO_VALUE;
        public int height = Format.NO_VALUE;
        public @Nullable String codecs = "";
        public @C.ColorSpace int colorSpace = Format.NO_VALUE;
        public @C.ColorRange int colorRange = Format.NO_VALUE;
        public @C.ColorTransfer int colorTransfer = Format.NO_VALUE;
        public float pixelWidthHeightRatio = 1;
        public List<byte[]> initializationData = new ArrayList<>();

        public VideoConfiguration build(){
            return new VideoConfiguration(mimeType,
                    nalUnitLengthFieldLength,
                    width, height,
                    colorSpace,
                    colorRange,
                    colorTransfer,
                    pixelWidthHeightRatio,
                    codecs,
                    initializationData);
        }
    }
}
