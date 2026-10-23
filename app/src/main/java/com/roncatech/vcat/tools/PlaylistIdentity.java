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

package com.roncatech.vcat.tools;

import java.util.UUID;

/**
 * Playlist identity: the UUID that identifies an XSPF playlist independently of its file name.
 *
 * <p>The id lives in the playlist as the XSPF-standard playlist-scope {@code <identifier>} element,
 * whose value is required by the spec to be a URI — hence the {@code urn:uuid:} form:
 * <pre>
 *   &lt;identifier&gt;urn:uuid:3f2504e0-4f89-41d3-9a0c-0305e82c3301&lt;/identifier&gt;
 * </pre>
 *
 * <p>A file name is not an identity: two folders can hold different playlists with the same name,
 * and renaming a playlist used to change what the telemetry logs said produced a run. The id is
 * what telemetry records ({@code session_info.playlist.id}) and what Resume matches on.
 */
public final class PlaylistIdentity {

    /** Standard nil UUID, used for logs written before playlists carried an id. */
    public static final String NIL = "00000000-0000-0000-0000-000000000000";

    private static final String URN_PREFIX = "urn:uuid:";

    private PlaylistIdentity() {}

    /** A fresh random playlist id, in bare (non-URN) form. */
    public static String newId() {
        return UUID.randomUUID().toString();
    }

    /** Wraps a bare UUID as the URN required by XSPF's {@code <identifier>}. */
    public static String toUrn(String uuid) {
        return URN_PREFIX + uuid;
    }

    /**
     * The canonical (lower-case, fully grouped) form of a UUID, or null if it is not one.
     *
     * <p>{@code UUID.fromString} accepts short groups such as {@code 1-2-3-4-5}, so the value is
     * round-tripped to reject anything that would not compare equal to a real id later.
     */
    public static String canonicalize(String uuid) {
        if (uuid == null) return null;
        String bare = uuid.trim();
        try {
            UUID parsed = UUID.fromString(bare);
            return parsed.toString().equalsIgnoreCase(bare) ? parsed.toString() : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The canonical form of {@code uuid}, or a fresh id when it is missing or unusable. Used on the
     * write path so every playlist VCAT writes is conformant, whatever the caller supplied.
     */
    public static String canonicalizeOrNew(String uuid) {
        String canonical = canonicalize(uuid);
        return canonical != null && !NIL.equals(canonical) ? canonical : newId();
    }

    /**
     * Extracts the bare UUID from an XSPF {@code <identifier>} value.
     *
     * @return the lower-case UUID, or null if the value is absent, is not a {@code urn:uuid:} URN,
     *         or does not parse as a UUID. A null return is what makes a playlist non-conformant.
     */
    public static String fromUrn(String identifier) {
        if (identifier == null) return null;
        String trimmed = identifier.trim();
        if (trimmed.length() <= URN_PREFIX.length()) return null;
        if (!trimmed.regionMatches(true, 0, URN_PREFIX, 0, URN_PREFIX.length())) return null;
        return canonicalize(trimmed.substring(URN_PREFIX.length()));
    }

    /** True for a missing/placeholder id, i.e. anything that must not be treated as an identity. */
    public static boolean isNilOrEmpty(String uuid) {
        return uuid == null || uuid.isEmpty() || NIL.equals(uuid);
    }
}
