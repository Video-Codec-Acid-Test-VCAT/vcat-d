/*
 * vcat-d (Video Codec Acid Test)
 *
 * SPDX-FileCopyrightText: Copyright (C) 2020-2025 vcat-d authors and RoncaTech
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of vcat-d.
 *
 * vcat-d is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with vcat-d. If not, see <https://www.gnu.org/licenses/gpl-3.0.html>.
 *
 * For proprietary/commercial use cases, a written GPL-3.0 waiver or
 * a separate commercial license is required from RoncaTech LLC.
 *
 * Contact: legal@roncatech.com
 */

package com.roncatech.vcat.decoder_plugin_api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Handler;

import com.google.android.exoplayer2.Renderer;
import com.google.android.exoplayer2.decoder.DecoderException;
import com.google.android.exoplayer2.extractor.ExtractorInput;
import com.google.android.exoplayer2.video.VideoRendererEventListener;

import org.junit.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/** Verifies the decoder-plugin-api refactor: SPI hierarchy, the legacy bridge, and IVF parsing. */
public class DecoderPluginApiTest {

    // ---- #2/#3: assignability (compile-time; asserted at runtime for clarity) ----

    @Test
    public void legacyPluginIsAssignableToNewInterfaces() {
        LegacyMp4Plugin p = new LegacyMp4Plugin();
        assertTrue("VcatDecoderPlugin must be a VcatDecoder", p instanceof VcatDecoder);
        assertTrue("NonStdDecoderStsdParser must be a ContainerParser", p instanceof ContainerParser);
    }

    // ---- #6: legacy bridge exposes the plugin as an MP4 ContainerParser ----

    @Test
    public void legacyStsdPluginBridgesToNonDeprecatedContainerParser() {
        LegacyMp4Plugin p = new LegacyMp4Plugin();
        List<ContainerParser> parsers = p.getSupportedContainerParsers();
        assertEquals(1, parsers.size());
        assertSame(p, parsers.get(0));
        // The bridge exposes the plugin via the non-deprecated Mp4ParserExtension type.
        assertTrue(parsers.get(0) instanceof Mp4ParserExtension);
        assertEquals("video/mp4", parsers.get(0).getContainerMimeType());
    }

    // ---- new-style MP4 plugin: non-deprecated, no NonStdDecoderStsdParser involved ----

    @Test
    public void newMp4DecoderUsesNonDeprecatedApiOnly() {
        NewMp4Decoder d = new NewMp4Decoder();
        assertTrue(d instanceof Mp4ParserExtension);
        assertTrue(d instanceof ContainerParser);
        assertFalse("new MP4 plugin must not touch the deprecated SPI",
                ((Object) d) instanceof NonStdDecoderStsdParser);
        List<ContainerParser> parsers = d.getSupportedContainerParsers();
        assertEquals(1, parsers.size());
        assertSame(d, parsers.get(0));
        assertEquals("video/mp4", parsers.get(0).getContainerMimeType());
    }

    // ---- #5: a new VcatDecoder + IvfParserExtension compiles (see NewIvfDecoder below) ----
    // (IvfFileHeader.parse() is now host-internal — see VcatIvfExtractorTest.)

    @Test
    public void newIvfDecoderCompilesAndAdvertisesItsParser() {
        NewIvfDecoder d = new NewIvfDecoder();
        assertTrue(d instanceof VcatDecoder);
        assertTrue(d instanceof ContainerParser);
        assertEquals("video/ivf", d.getContainerMimeType());
    }

    // ===== stubs =====

    /** Legacy-style plugin: implements the deprecated SPI + STSD parser (like vvdec). */
    @SuppressWarnings("deprecation")
    static final class LegacyMp4Plugin implements VcatDecoderPlugin, NonStdDecoderStsdParser {
        @Override public String getId() { return "test.legacy"; }
        @Override public String getDisplayName() { return "legacy"; }
        @Override public String getVersion() { return "0"; }
        @Override public String getMimeType() { return "video/vvc"; }
        @Override public List<String> getSupportedProfiles() { return Collections.emptyList(); }
        @Override public Renderer createVideoRenderer(
                Context context, long allowedJoiningTimeMs, Handler eventHandler,
                VideoRendererEventListener eventListener, int threads) throws DecoderException {
            return null; // never invoked in these tests
        }
        @Override public int sampleEntry4ccCode() { return 0; }
        @Override public int codecConfiguration4ccCode() { return 0; }
        @Override public String mimeType() { return "video/vvc"; }
        @Override public VideoConfiguration parseStsd(byte[] data) { return null; }
    }

    /** New-style MP4 decoder on the non-deprecated SPI (no NonStdDecoderStsdParser). */
    static final class NewMp4Decoder implements VcatDecoder, Mp4ParserExtension {
        @Override public String getId() { return "test.mp4"; }
        @Override public String getDisplayName() { return "mp4"; }
        @Override public String getVersion() { return "0"; }
        @Override public String getMimeType() { return "video/vvc"; }
        @Override public int sampleEntry4ccCode() { return 0; }
        @Override public int codecConfiguration4ccCode() { return 0; }
        @Override public VideoConfiguration parseStsd(byte[] data) { return null; }
        @Override public List<ContainerParser> getSupportedContainerParsers() {
            return Collections.singletonList(this);
        }
        @Override public Renderer createVideoRenderer(
                Context context, long allowedJoiningTimeMs, Handler eventHandler,
                VideoRendererEventListener eventListener, int threads) throws DecoderException {
            return null;
        }
    }

    /** New-style decoder on the non-deprecated SPI, IVF-capable. */
    static final class NewIvfDecoder implements VcatDecoder, IvfParserExtension {
        @Override public String getId() { return "test.ivf"; }
        @Override public String getDisplayName() { return "ivf"; }
        @Override public String getVersion() { return "0"; }
        @Override public String getMimeType() { return "video/av01"; }
        @Override public int ivfFourCc() { return 0x31305641; } // "AV01"
        @Override public VideoConfiguration parseHeader(ExtractorInput input, int frameSize)
                throws IOException, InterruptedException {
            return null; // never invoked here
        }
        @Override public List<ContainerParser> getSupportedContainerParsers() {
            return Collections.singletonList(this);
        }
        @Override public Renderer createVideoRenderer(
                Context context, long allowedJoiningTimeMs, Handler eventHandler,
                VideoRendererEventListener eventListener, int threads) throws DecoderException {
            return null;
        }
    }
}
