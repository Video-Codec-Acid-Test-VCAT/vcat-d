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

import android.content.Context;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Verifies that every playlist in the VCAT playlist folder conforms to the format VCAT requires,
 * which today means one thing: it carries a playlist-scope {@code <identifier>} holding a
 * {@code urn:uuid:} id (see {@link PlaylistIdentity}).
 *
 * <p>Checked on every app launch. Non-conformant playlists are omitted from the UI entirely — they
 * cannot be played, edited, or resumed — because without an id there is no way to say which
 * playlist a telemetry log came from, which is the ambiguity the id exists to remove. Playlists
 * that are merely missing an id can be repaired in place, so the user is offered a one-tap update;
 * declining leaves them hidden rather than silently running them.
 *
 * <p>All methods here perform SAF I/O (one or more IPC round trips per playlist) and must be called
 * off the UI thread.
 */
public final class PlaylistConformance {
    private static final String TAG = "PlaylistConformance";

    private PlaylistConformance() {}

    /** A playlist file and its resolved identity. Field values are cached to keep SAF off the UI thread. */
    public static final class Entry {
        public final DocumentFile doc;
        public final String name;
        public final String uriStr;
        /** The playlist id, or null when the playlist is non-conformant. */
        public final String playlistId;

        Entry(DocumentFile doc, String name, String uriStr, String playlistId) {
            this.doc = doc;
            this.name = name;
            this.uriStr = uriStr;
            this.playlistId = playlistId;
        }

        public boolean isConformant() {
            return !PlaylistIdentity.isNilOrEmpty(this.playlistId);
        }
    }

    /** Result of a folder scan: what the UI may show, and what needs updating first. */
    public static final class Scan {
        /** Playlists carrying a valid id, sorted by name. Only these may be shown or played. */
        public final List<Entry> conformant;
        /** Playlists with no usable id, sorted by name. Hidden from the UI until repaired. */
        public final List<Entry> nonConformant;

        Scan(List<Entry> conformant, List<Entry> nonConformant) {
            this.conformant = Collections.unmodifiableList(conformant);
            this.nonConformant = Collections.unmodifiableList(nonConformant);
        }

        public boolean hasNonConformant() {
            return !this.nonConformant.isEmpty();
        }

        /** Display names of the non-conformant playlists, for the update prompt. */
        public List<String> nonConformantNames() {
            List<String> names = new ArrayList<>(this.nonConformant.size());
            for (Entry e : this.nonConformant) {
                names.add(e.name);
            }
            return names;
        }
    }

    /** An empty scan, for when the playlist folder is missing or inaccessible. */
    public static final Scan EMPTY = new Scan(new ArrayList<>(), new ArrayList<>());

    /**
     * Scans the playlist folder and partitions every {@code .xspf} file by conformance.
     *
     * <p>A file whose identifier cannot be read is reported as non-conformant regardless of the
     * reason — absent element, malformed value, or an unreadable file. The repair attempt in
     * {@link #repair} distinguishes them in practice: a genuinely damaged file fails to be stamped
     * and simply stays hidden.
     */
    public static Scan scan(Context ctx) {
        DocumentFile playlistDir = StorageManager.getFolder(ctx, StorageManager.VCATFolder.PLAYLIST);
        if (playlistDir == null) {
            Log.w(TAG, "Playlist folder missing or inaccessible; treating as empty");
            return EMPTY;
        }

        DocumentFile[] files = playlistDir.listFiles();
        if (files == null) {
            return EMPTY;
        }

        List<Entry> conformant = new ArrayList<>();
        List<Entry> nonConformant = new ArrayList<>();
        for (DocumentFile f : files) {
            String name = f.getName();               // one IPC per file
            if (name == null || !name.endsWith(".xspf")) {
                continue;
            }
            String id = XspfParser.parseIdentifier(ctx, f.getUri());
            Entry entry = new Entry(f, name, f.getUri().toString(), id);
            (entry.isConformant() ? conformant : nonConformant).add(entry);
        }

        conformant.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        nonConformant.sort((a, b) -> a.name.compareToIgnoreCase(b.name));

        if (!nonConformant.isEmpty()) {
            Log.i(TAG, nonConformant.size() + " non-conformant playlist(s) hidden from the UI");
        }
        return new Scan(conformant, nonConformant);
    }

    /**
     * Stamps a fresh id into each given playlist, in place.
     *
     * <p>Repair is per-file and independent: a file that cannot be rewritten leaves the others
     * already updated, so a partial run is safe to re-attempt on the next launch.
     *
     * @return the number of playlists that are now conformant.
     */
    public static int repair(Context ctx, List<Entry> entries) {
        int repaired = 0;
        for (Entry e : entries) {
            if (XSPFPlaylistCreator.stampIdentifier(ctx, e.doc.getUri()) != null) {
                repaired++;
            } else {
                Log.w(TAG, "Could not update playlist: " + e.name);
            }
        }
        Log.i(TAG, "Updated " + repaired + " of " + entries.size() + " playlist(s)");
        return repaired;
    }
}
