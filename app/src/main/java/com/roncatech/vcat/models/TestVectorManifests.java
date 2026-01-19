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

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.util.List;
import java.util.UUID;

public class TestVectorManifests {

    // Base class AssetBase
    public static class AssetBase {
        public final String name;
        public final String url;
        public final String checksum;

        @SerializedName("length_bytes")
        public final long lengthBytes;

        public AssetBase(String name, String url, String checksum, long lengthBytes) {
            this.name = name;
            this.url = url;
            this.checksum = checksum;
            this.lengthBytes = lengthBytes;
        }

        // Static method to deserialize from JSON
        public static AssetBase fromJson(String json) {
            return new Gson().fromJson(json, AssetBase.class);
        }

        // Static method to serialize to JSON
        public static String toJson(AssetBase asset) {
            return new Gson().toJson(asset);
        }
    }

    // Derived class VideoAsset
    public static class VideoAsset extends AssetBase {
        @SerializedName("video_mime_type")
        public final String videoMimeType;

        @SerializedName("duration_ms")
        public final Integer durationMs;

        @SerializedName("resolution_x_y")
        public final String resolutionXY;

        @SerializedName("frame_rate")
        public final String frameRate;

        public VideoAsset(String name, String url, String checksum, long lengthBytes, String videoMimeType, Integer durationMs, String resolutionXY, String frameRate) {
            super(name, url, checksum, lengthBytes);
            this.videoMimeType = videoMimeType;
            this.durationMs = durationMs;
            this.resolutionXY = resolutionXY;
            this.frameRate = frameRate;
        }

        // Static method to deserialize from JSON
        public static VideoAsset fromJson(String json) {
            return new Gson().fromJson(json, VideoAsset.class);
        }

        // Static method to serialize to JSON
        public static String toJson(VideoAsset videoAsset) {
            return new Gson().toJson(videoAsset);
        }
    }

    // Header
    public static class Header {
        public final String name;
        public final String description;

        @SerializedName("created_by")
        public final String createdBy;  // Java field `createdBy` will be serialized as `created_by` in JSON

        public final String uuid;

        @SerializedName("created_at")
        public final String createdAt;

        public Header(String name, String description, String createdBy) {
            this.name = name;
            this.description = description;
            this.createdBy = createdBy;
            this.uuid = UUID.randomUUID().toString();
            this.createdAt = java.time.LocalDateTime.now().toString();
        }

        // Static method to deserialize from JSON
        public static Header fromJson(String json) {
            return new Gson().fromJson(json, Header.class);
        }

        // Static method to serialize to JSON
        public static String toJson(Header header) {
            return new Gson().toJson(header);
        }
    }

    // VideoManifest (top-level container for header and video assets)
    public static class VideoManifest {
        @SerializedName("vcat_testvector_header")
        public final Header header;

        @SerializedName("media_asset")
        public final VideoAsset mediaAsset;

        public VideoManifest(Header header, VideoAsset mediaAsset) {
            this.header = header;
            this.mediaAsset = mediaAsset;
        }

        // Static method to deserialize from JSON
        public static VideoManifest fromJson(String json) {
            return new Gson().fromJson(json, VideoManifest.class);
        }

        // Static method to serialize to JSON
        public static String toJson(VideoManifest videoManifest) {
            return new Gson().toJson(videoManifest);
        }
    }

    // PlaylistAsset (extends AssetBase)
    public static class PlaylistAsset extends AssetBase {
        public final UUID uuid;
        public final String description;

        public PlaylistAsset(String name, String url, String checksum, long lengthBytes, UUID uuid, String description) {
            super(name, url, checksum, lengthBytes);
            this.uuid = uuid;
            this.description = description;
        }

        // Static method to deserialize from JSON
        public static PlaylistAsset fromJson(String json) {
            return new Gson().fromJson(json, PlaylistAsset.class);
        }

        // Static method to serialize to JSON
        public static String toJson(PlaylistAsset playlistAsset) {
            return new Gson().toJson(playlistAsset);
        }
    }

    // VcatTestVectorPlaylistManifest (container for the header and list of assets)
    public static class PlaylistManifest {
        @SerializedName("vcat_testvector_header")
        public final Header header;

        @SerializedName("media_assets")
        public final List<PlaylistAsset> mediaAssets;

        public PlaylistManifest(Header header, List<PlaylistAsset> mediaAssets) {
            this.header = header;
            this.mediaAssets = mediaAssets;
        }

        // Static method to deserialize from JSON
        public static PlaylistManifest fromJson(String json) {
            return new Gson().fromJson(json, PlaylistManifest.class);
        }

        // Static method to serialize to JSON
        public static String toJson(PlaylistManifest playlistManifest) {
            return new Gson().toJson(playlistManifest);
        }
    }

    // Catalog (contains playlists)
    public static class Catalog {
        @SerializedName("vcat_testvector_header")
        public final Header header;

        @SerializedName("playlists")
        public final List<PlaylistAsset> playlists;

        public Catalog(Header header, List<PlaylistAsset> playlists) {
            this.header = header;
            this.playlists = playlists;
        }

        // Static method to deserialize from JSON
        public static Catalog fromJson(String json) {
            return new Gson().fromJson(json, Catalog.class);
        }

        // Static method to serialize to JSON
        public static String toJson(Catalog playlistCatalog) {
            return new Gson().toJson(playlistCatalog);
        }
    }

    // CatalogAsset (reference to a catalog, extends AssetBase)
    public static class CatalogAsset extends AssetBase {
        public final UUID uuid;
        public final String description;

        public CatalogAsset(String name, String url, String checksum, long lengthBytes, UUID uuid, String description) {
            super(name, url, checksum, lengthBytes);
            this.uuid = uuid;
            this.description = description;
        }

        // Static method to deserialize from JSON
        public static CatalogAsset fromJson(String json) {
            return new Gson().fromJson(json, CatalogAsset.class);
        }

        // Static method to serialize to JSON
        public static String toJson(CatalogAsset catalogAsset) {
            return new Gson().toJson(catalogAsset);
        }
    }

    // CatalogIndex (contains references to multiple catalogs)
    public static class CatalogIndex {
        @SerializedName("vcat_testvector_header")
        public final Header header;

        @SerializedName("catalogs")
        public final List<CatalogAsset> catalogs;

        public CatalogIndex(Header header, List<CatalogAsset> catalogs) {
            this.header = header;
            this.catalogs = catalogs;
        }

        // Static method to deserialize from JSON
        public static CatalogIndex fromJson(String json) {
            return new Gson().fromJson(json, CatalogIndex.class);
        }

        // Static method to serialize to JSON
        public static String toJson(CatalogIndex catalogIndex) {
            return new Gson().toJson(catalogIndex);
        }
    }
}
