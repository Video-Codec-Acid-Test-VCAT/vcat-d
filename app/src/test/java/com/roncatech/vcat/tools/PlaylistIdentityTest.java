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

import org.junit.Test;

import static org.junit.Assert.*;

public class PlaylistIdentityTest {

    private static final String UUID_STR = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";

    @Test
    public void toUrn_producesXspfIdentifierValue() {
        assertEquals("urn:uuid:" + UUID_STR, PlaylistIdentity.toUrn(UUID_STR));
    }

    @Test
    public void fromUrn_roundTripsToUrn() {
        assertEquals(UUID_STR, PlaylistIdentity.fromUrn(PlaylistIdentity.toUrn(UUID_STR)));
    }

    @Test
    public void fromUrn_toleratesSurroundingWhitespace() {
        // XML text nodes carry the indentation of the pretty-printed playlist.
        assertEquals(UUID_STR, PlaylistIdentity.fromUrn("\n      urn:uuid:" + UUID_STR + "\n    "));
    }

    @Test
    public void fromUrn_normalizesCaseOfSchemeAndUuid() {
        // Manifest uuids may arrive upper-case; ids are compared with equals() downstream, so the
        // parsed form has to be canonical.
        assertEquals(UUID_STR, PlaylistIdentity.fromUrn("URN:UUID:" + UUID_STR.toUpperCase()));
    }

    @Test
    public void fromUrn_rejectsNonConformantValues() {
        assertNull(PlaylistIdentity.fromUrn(null));
        assertNull(PlaylistIdentity.fromUrn(""));
        assertNull(PlaylistIdentity.fromUrn("urn:uuid:"));
        assertNull("bare uuid is not a URI, which XSPF requires",
                PlaylistIdentity.fromUrn(UUID_STR));
        assertNull("wrong URN namespace", PlaylistIdentity.fromUrn("urn:mbid:" + UUID_STR));
        assertNull("http identifiers are not playlist ids",
                PlaylistIdentity.fromUrn("http://example.com/" + UUID_STR));
        assertNull("malformed uuid", PlaylistIdentity.fromUrn("urn:uuid:not-a-uuid"));
    }

    @Test
    public void fromUrn_rejectsShortGroupsAcceptedByUuidFromString() {
        // UUID.fromString("1-2-3-4-5") parses happily; a playlist id must be a full uuid.
        assertNull(PlaylistIdentity.fromUrn("urn:uuid:1-2-3-4-5"));
    }

    @Test
    public void newId_isUniqueAndConformant() {
        String a = PlaylistIdentity.newId();
        String b = PlaylistIdentity.newId();
        assertNotEquals(a, b);
        assertEquals(a, PlaylistIdentity.fromUrn(PlaylistIdentity.toUrn(a)));
        assertFalse(PlaylistIdentity.isNilOrEmpty(a));
    }

    @Test
    public void canonicalize_rejectsWhatCannotBeComparedLater() {
        assertEquals(UUID_STR, PlaylistIdentity.canonicalize(UUID_STR.toUpperCase()));
        assertEquals(UUID_STR, PlaylistIdentity.canonicalize("  " + UUID_STR + "  "));
        assertNull(PlaylistIdentity.canonicalize("1-2-3-4-5"));
        assertNull(PlaylistIdentity.canonicalize("not-a-uuid"));
        assertNull(PlaylistIdentity.canonicalize(null));
    }

    @Test
    public void canonicalizeOrNew_alwaysYieldsAUsableId() {
        // The write path must never emit a playlist that its own reader would reject.
        assertEquals(UUID_STR, PlaylistIdentity.canonicalizeOrNew(UUID_STR.toUpperCase()));

        for (String unusable : new String[]{null, "", "1-2-3-4-5", "garbage", PlaylistIdentity.NIL}) {
            String id = PlaylistIdentity.canonicalizeOrNew(unusable);
            assertFalse("replacement for " + unusable, PlaylistIdentity.isNilOrEmpty(id));
            assertEquals("replacement for " + unusable + " must round-trip",
                    id, PlaylistIdentity.fromUrn(PlaylistIdentity.toUrn(id)));
        }
    }

    @Test
    public void isNilOrEmpty_flagsAbsentIdentity() {
        assertTrue(PlaylistIdentity.isNilOrEmpty(null));
        assertTrue(PlaylistIdentity.isNilOrEmpty(""));
        assertTrue(PlaylistIdentity.isNilOrEmpty(PlaylistIdentity.NIL));
        assertFalse(PlaylistIdentity.isNilOrEmpty(UUID_STR));
    }

    @Test
    public void nil_isTheStandardNilUuid() {
        // Must be UUID-shaped so consumers can parse it rather than special-casing a placeholder.
        assertEquals("00000000-0000-0000-0000-000000000000", PlaylistIdentity.NIL);
        assertEquals(PlaylistIdentity.NIL,
                PlaylistIdentity.fromUrn(PlaylistIdentity.toUrn(PlaylistIdentity.NIL)));
    }
}
