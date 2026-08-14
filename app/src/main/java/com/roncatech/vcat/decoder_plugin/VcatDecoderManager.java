package com.roncatech.vcat.decoder_plugin;

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

import androidx.annotation.Nullable;

import com.roncatech.vcat.decoder_plugin_api.ContainerParser;
import com.roncatech.vcat.decoder_plugin_api.IvfParserExtension;
import com.roncatech.vcat.decoder_plugin_api.Mp4ParserExtension;
import com.roncatech.vcat.decoder_plugin_api.VcatDecoder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Central registry for VCAT decoder plugins (last-wins). */
public final class VcatDecoderManager {

    private static final VcatDecoderManager INSTANCE = new VcatDecoderManager();
    private final ConcurrentMap<String, VcatDecoder> decoders = new ConcurrentHashMap<>();

    private VcatDecoderManager() {
    }

    public static VcatDecoderManager getInstance() {
        return INSTANCE;
    }

    /** Register/replace a decoder by its ID (last registration wins). */
    public boolean registerDecoder(VcatDecoder decoder) {
        Objects.requireNonNull(decoder, "decoder");
        String id = Objects.requireNonNull(decoder.getId(), "decoder.getId()");

        if(!this.decoders.containsKey(id)) {
            decoders.put(id, decoder); // first wins
            return true;
        }

        return false;
    }

    public VcatDecoder getDecoder(String id){
        return this.decoders.getOrDefault(id, null);
    }

    /** Snapshot list of all registered decoders. */
    public List<VcatDecoder> getDecoders() {
        return Collections.unmodifiableList(new ArrayList<>(decoders.values()));
    }

    /** Snapshot list of all registered decoders for specified mime type. */
    public List<VcatDecoder> getDecodersForMimeType(String mimeType) {
        List<VcatDecoder> decodersForMimeType = new ArrayList<>();

        for(VcatDecoder curDecoder : decoders.values()){
            if(curDecoder.getMimeType().equals(mimeType)){
                decodersForMimeType.add(curDecoder);
            }
        }
        return Collections.unmodifiableList(decodersForMimeType);
    }

    public List<VcatDecoder> getAllDecoderse() {
        return Collections.unmodifiableList(new ArrayList<>(decoders.values()));
    }

    public Map<Integer, Mp4ParserExtension> getNonStandardDecoders(){
        Map<Integer, Mp4ParserExtension> nonStandardDecoders = new HashMap();

        for(VcatDecoder curDecoder : decoders.values()){
            if(curDecoder instanceof Mp4ParserExtension){
                Mp4ParserExtension nonStd = (Mp4ParserExtension) curDecoder;
                nonStandardDecoders.put(nonStd.sampleEntry4ccCode(), nonStd);
            }
        }
        return Collections.unmodifiableMap(nonStandardDecoders);
    }

    /** Find the registered IVF parser handling {@code fourCc}, or {@code null} if none. */
    @Nullable
    public IvfParserExtension findIvfPlugin(int fourCc) {
        for (VcatDecoder decoder : getDecoders()) {
            for (ContainerParser parser : decoder.getSupportedContainerParsers()) {
                if (parser instanceof IvfParserExtension) {
                    IvfParserExtension ivf = (IvfParserExtension) parser;
                    if (ivf.ivfFourCc() == fourCc) {
                        return ivf;
                    }
                }
            }
        }
        return null;
    }
}
