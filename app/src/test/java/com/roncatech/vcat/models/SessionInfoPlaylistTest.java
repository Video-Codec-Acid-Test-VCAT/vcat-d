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
 * vcat-d is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with vcat-d. If not, see <https://www.gnu.org/licenses/gpl-3.0.html>.
 *
 * For proprietary/commercial use cases, a written GPL-3.0 waiver or
 * a separate commercial license is required from RoncaTech LLC.
 *
 * All vcat-d artwork is owned exclusively by RoncaTech LLC. Use of vcat-d logos
 * and artwork is permitted for the purpose of discussing, documenting,
 * or promoting vcat-d itself. Any other use requires prior written permission
 * from RoncaTech LLC.
 *
 * Contact: legal@roncatech.com
 */

package com.roncatech.vcat.models;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.roncatech.vcat.tools.PlaylistIdentity;

import org.junit.Test;

import static org.junit.Assert.*;

/** Covers the telemetry header's playlist field: the object form, and the legacy string form. */
public class SessionInfoPlaylistTest {

    private static final String PLAYLIST_ID = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";

    private static SessionInfo sessionInfo(SessionInfo.Playlist playlist) {
        return new SessionInfo(
                new SessionInfo.StartTime(1_700_000_000_000L, "2023-11-14", "22:13:20"),
                "1.2.3",
                new SessionInfo.Battery(4000, 3800, 0.95),
                playlist);
    }

    @Test
    public void writesPlaylistAsIdAndName() {
        String json = SessionInfo.gson.toJson(
                sessionInfo(new SessionInfo.Playlist(PLAYLIST_ID, "av2-1080p30.xspf")),
                SessionInfo.class);

        JsonObject playlist = JsonParser.parseString(json).getAsJsonObject()
                .getAsJsonObject("playlist");
        assertEquals(PLAYLIST_ID, playlist.get("id").getAsString());
        assertEquals("av2-1080p30.xspf", playlist.get("name").getAsString());
    }

    @Test
    public void roundTripsPlaylistIdentity() {
        SessionInfo original = sessionInfo(new SessionInfo.Playlist(PLAYLIST_ID, "av2-1080p30.xspf"));
        String json = SessionInfo.gson.toJson(original, SessionInfo.class);
        SessionInfo parsed = SessionInfo.gson.fromJson(json, SessionInfo.class);

        assertEquals(PLAYLIST_ID, parsed.playlist.id);
        assertEquals("av2-1080p30.xspf", parsed.playlist.name);
        assertEquals(original, parsed);
    }

    @Test
    public void readsLegacyBarePlaylistName() {
        // Logs written before playlists carried ids stored "playlist": "<filename>". These must
        // stay readable, with no identity, so existing results still open.
        String legacy = "{"
                + "\"battery\":{\"capacity_ma\":4000,\"initial_level_ma\":3800,\"initial_level_pct\":0.95},"
                + "\"playlist\":\"av2-1080p30.xspf\","
                + "\"start_time\":{\"local_date\":\"2023-11-14\",\"local_time\":\"22:13:20\","
                + "\"unix_time_ms\":1700000000000},"
                + "\"vcat_version\":\"1.2.3\"}";

        SessionInfo parsed = SessionInfo.gson.fromJson(legacy, SessionInfo.class);

        assertEquals("av2-1080p30.xspf", parsed.playlist.name);
        assertEquals(PlaylistIdentity.NIL, parsed.playlist.id);
        assertTrue(PlaylistIdentity.isNilOrEmpty(parsed.playlist.id));
    }

    @Test
    public void readsPlaylistObjectMissingFields() {
        String partial = "{"
                + "\"battery\":{\"capacity_ma\":4000,\"initial_level_ma\":3800,\"initial_level_pct\":0.95},"
                + "\"playlist\":{},"
                + "\"start_time\":{\"local_date\":\"2023-11-14\",\"local_time\":\"22:13:20\","
                + "\"unix_time_ms\":1700000000000},"
                + "\"vcat_version\":\"1.2.3\"}";

        SessionInfo parsed = SessionInfo.gson.fromJson(partial, SessionInfo.class);

        assertEquals(PlaylistIdentity.NIL, parsed.playlist.id);
        assertEquals("", parsed.playlist.name);
    }

    @Test
    public void nullPlaylistIdBecomesNil() {
        // A nil id must never look like a real identity to Resume's id comparison.
        assertEquals(PlaylistIdentity.NIL, new SessionInfo.Playlist(null, null).id);
        assertEquals("", new SessionInfo.Playlist(null, null).name);
    }
}
