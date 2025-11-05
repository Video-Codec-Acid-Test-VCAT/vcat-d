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

package com.roncatech.vcat.legal;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class TermsRepository {

    // ─────────────────────────── Public API ───────────────────────────

    /** Local fallback version (bump if you change LOCAL_TERMS_HTML meaningfully). */
    public int localVersion() { return LOCAL_VERSION; }

    /** Version the user has already accepted (0 if never). */
    public int getAcceptedVersion() {
        return prefs.getInt(KEY_ACCEPTED_VERSION, 0);
    }

    /** Persist acceptance (version + content hash + timestamp). */
    public void storeAccepted(int version, String html) {
        prefs.edit()
                .putInt(KEY_ACCEPTED_VERSION, version)
                .putString(KEY_ACCEPTED_HASH, sha256(html == null ? "" : html))
                .putLong(KEY_ACCEPTED_AT, System.currentTimeMillis())
                .apply();
    }

    /** Returns a local fallback payload (works fully offline). */
    public TermsPayload localFallback() {
        return new TermsPayload(LOCAL_VERSION, LOCAL_TERMS_HTML, null);
    }

    /**
     * Fetch latest Terms JSON.
     * Supports:
     *   { "version": 7, "html": "<h2>...</h2>..." }
     *   { "version": "0.0.1.0", "html_url": "https://.../vcat-terms.html" }
     *
     * - Uses ETag for the JSON fetch.
     * - Caches HTML + version for offline / 304 reuse.
     * - Falls back to LOCAL_TERMS_HTML.
     */
    public TermsPayload fetchLatestOrFallback() {
        final String url       = prefs.getString(KEY_REMOTE_URL, DEFAULT_URL);
        final String priorEtag = prefs.getString(KEY_REMOTE_ETAG, null);

        final Request.Builder rb = new Request.Builder().url(url);
        if (priorEtag != null && !priorEtag.isEmpty()) {
            rb.header("If-None-Match", priorEtag);
        }

        try (Response resp = http.newCall(rb.build()).execute()) {
            final int code = resp.code();
            if (code == 200) {
                final String body = resp.body() != null ? resp.body().string() : "";
                final String etag = resp.header("ETag");

                if (etag != null && !etag.isEmpty()) {
                    prefs.edit().putString(KEY_REMOTE_ETAG, etag).apply();
                }

                final JSONObject json = new JSONObject(body);
                final int versionCode = parseVersionCode(json);     // supports int or "0.0.1.0"
                final String html     = resolveHtml(json);          // supports "html" or "html_url"

                // Cache for offline & 304 reuse
                prefs.edit()
                        .putInt(KEY_CACHED_VERSION, versionCode)
                        .putString(KEY_CACHED_HTML, html)
                        .apply();

                return new TermsPayload(versionCode, html, etag);

            } else if (code == 304) {
                // Not modified — reuse cache
                final int v = prefs.getInt(KEY_CACHED_VERSION, 0);
                final String html = prefs.getString(KEY_CACHED_HTML, null);
                if (v > 0 && html != null) {
                    return new TermsPayload(v, html, priorEtag);
                }
                return localFallback();

            } else {
                return localFallback();
            }
        } catch (Exception e) {
            return localFallback();
        }
    }

    /** Compare accepted vs latest to decide if the user must accept again. */
    public static boolean needsAcceptance(int latestVersion, int acceptedVersion) {
        return acceptedVersion < latestVersion;
    }

    // ─────────────────────────── Impl / helpers ───────────────────────────

    private static final String PREFS = "vcat_prefs";

    // Keys
    private static final String KEY_REMOTE_URL       = "terms.remote.url";
    private static final String KEY_REMOTE_ETAG      = "terms.remote.etag";
    private static final String KEY_CACHED_VERSION   = "terms.cached.version";
    private static final String KEY_CACHED_HTML      = "terms.cached.html";
    private static final String KEY_ACCEPTED_VERSION = "terms.accepted.version";
    private static final String KEY_ACCEPTED_HASH    = "terms.accepted.hash";
    private static final String KEY_ACCEPTED_AT      = "terms.accepted.at";

    // Your public Gist RAW URL (auto-updates when you edit the Gist)
    private static final String DEFAULT_URL =
            "https://gist.githubusercontent.com/jonathannah/8f792473d5548449f4eef2c059fa7c17/raw/vcat-terms.json";

    // Local fallback HTML used when fetch fails (keep short or your full text)
    // If you materially change LOCAL_TERMS_HTML, bump this local version
    private static final int LOCAL_VERSION = 1;

    public static final String LOCAL_TERMS_HTML =
            "<h2>IMPORTANT – ACCEPTANCE OF TERMS</h2>\n" +
                    "<p>\n" +
                    "  By downloading, installing, or using VCAT (the “Software”), you acknowledge that you have read,\n" +
                    "  understood, and agree to be bound by the terms set forth in these Release Notes, the LICENSE\n" +
                    "  (GPL-3.0-only with commercial-waiver option), and any accompanying documentation\n" +
                    "  (including limitations of liability and disclaimers).\n" +
                    "</p>\n" +
                    "\n" +
                    "<h3>License</h3>\n" +
                    "<p>\n" +
                    "  <strong>VCAT is released under the GNU General Public License, version 3 (GPL-3.0-only).</strong>\n" +
                    "  By installing or using VCAT, you agree to comply with the GPL-3.0 terms. Any use outside the GPL-3.0\n" +
                    "  — <em>e.g., “proprietary use” such as distributing VCAT or derivative works without providing source\n" +
                    "  code under the GPL, re-licensing under more restrictive terms, or embedding it into closed-source\n" +
                    "  products for distribution</em> — requires a prior <strong>written GPL-3.0 waiver or commercial license</strong>\n" +
                    "  from RoncaTech, LLC.\n" +
                    "</p>\n" +
                    "<p>\n" +
                    "  Full license: <a href=\"https://www.gnu.org/licenses/gpl-3.0.html\" target=\"_blank\" rel=\"noopener\">GPL-3.0 License (gnu.org)</a><br>\n" +
                    "  Licensing inquiries: <a href=\"mailto:legal@roncatech.com\">Contact RoncaTech Legal</a>\n" +
                    "</p>\n" +
                    "\n" +
                    "<h3>Privacy</h3>\n" +
                    "<p>\n" +
                    "  <strong>VCAT does not collect, store, or transmit personal data or usage analytics.</strong>\n" +
                    "  No telemetry, no tracking, no crash analytics, and no advertising identifiers are gathered by VCAT.\n" +
                    "</p>\n" +
                    "<p>The only network requests VCAT performs are:</p>\n" +
                    "<ul>\n" +
                    "  <li><strong>Fetching updated Terms text</strong> from a public host (e.g.,\n" +
                    "      <a href=\"https://gist.github.com/\" target=\"_blank\" rel=\"noopener\">GitHub Gist</a>) to notify you of changes.</li>\n" +
                    "  <li><strong>Importing VCAT Test Vector sets</strong> consisting solely of <em>video assets and playlists</em>\n" +
                    "      from sources you choose/configure. These requests fetch media files and playlist metadata only; they do not\n" +
                    "      include user-identifying information.</li>\n" +
                    "</ul>\n" +
                    "<p>\n" +
                    "  VCAT does not send any data to RoncaTech servers. Network calls go directly to the sources you configure or the public\n" +
                    "  Terms host. If a source requires authentication, any credentials are used only to access that source and are not\n" +
                    "  transmitted to RoncaTech.\n" +
                    "</p>\n" +
                    "<p>\n" +
                    "  <em>Third-party platforms</em> (e.g., Google Play, device OEM services, or your configured content hosts) may process\n" +
                    "  data independently under their own terms. VCAT does not receive that data.<br>\n" +
                    "  Privacy questions: <a href=\"mailto:privacy@roncatech.com\">Contact Privacy</a>\n" +
                    "</p>\n" +
                    "\n" +
                    "<h3>Disclaimer of Suitability</h3>\n" +
                    "<p>\n" +
                    "  VCAT is provided for general benchmarking and evaluation purposes only. RoncaTech makes no representations or\n" +
                    "  guarantees that VCAT is suitable for any particular purpose, environment, or workflow. You are solely responsible\n" +
                    "  for determining whether VCAT meets your needs. Under no circumstances should reliance on VCAT substitute for your\n" +
                    "  own testing, validation, or professional judgment.\n" +
                    "</p>\n" +
                    "\n" +
                    "<h3>Limitation of Liability</h3>\n" +
                    "<p>\n" +
                    "  <strong>TO THE MAXIMUM EXTENT PERMITTED BY APPLICABLE LAW</strong>, IN NO EVENT WILL RONCATECH, LLC OR ITS\n" +
                    "  AFFILIATES, CONTRIBUTORS, OR SUPPLIERS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, CONSEQUENTIAL,\n" +
                    "  OR PUNITIVE DAMAGES, INCLUDING BUT NOT LIMITED TO LOSS OF PROFITS, REVENUE, DATA, OR USE, ARISING OUT OF OR IN\n" +
                    "  CONNECTION WITH YOUR USE OF VCAT, EVEN IF RONCATECH HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGES.\n" +
                    "</p>\n" +
                    "<p>\n" +
                    "  You agree that your sole and exclusive remedy for any claim under or related to VCAT will be to discontinue\n" +
                    "  using the software. \n" +
                    "</p>\n" +
                    "\n" +
                    "<h3>Patent Notice (No Patent Rights Granted)</h3>\n" +
                    "<p>\n" +
                    "VCAT and libvcat are distributed under GPL-3.0-or-later. Nothing in this README, the source code, or the license grants you any rights under third-party patents, including without limitation patents essential to implement or use media codecs and container formats (e.g., AVC/H.264, HEVC/H.265, VVC/H.266, MPEG-2, AAC, etc.).\n" +
                    "\n" +
                    " <ul>\n" +
                    "  <li>You are solely responsible for determining whether your use, distribution, or deployment of VCAT/libvcat requires patent licenses from any third party (including patent pools or individual patent holders) and for obtaining any such licenses.</li>\n" +
                    "  <li>Contributions to this project may include a limited patent grant from contributors as specified by GPL-3.0-or-later, but no additional patent rights are provided, and no rights are granted on behalf of any third party.</li>\n" +
                    "  <li>Use of bundled or integrated decoders/parsers does not imply or provide patent clearance for any jurisdiction. Your compliance with all applicable intellectual property laws remains your responsibility.</li>\n" +
                    "</ul>\n" +
                    "</p>\n";


    private final SharedPreferences prefs;
    private final OkHttpClient http;

    public TermsRepository(Context ctx) {
        this.prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.http = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .callTimeout(8, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build();

        // Seed remote url if not set
        if (!prefs.contains(KEY_REMOTE_URL)) {
            prefs.edit().putString(KEY_REMOTE_URL, DEFAULT_URL).apply();
        }
    }

    /**
     * Reads "version" as either an integer OR a semantic string like "0.0.1.0" and returns a
     * monotonic integer "versionCode" suitable for comparison and storage.
     *
     * Encoding: a.b.c.d  ->  a*1_000_000 + b*10_000 + c*100 + d
     * (each part clamped to 0..99 to avoid overflow; supports up to ~99.99.99.99)
     */
    private static int parseVersionCode(JSONObject json) {
        try {
            // If it's already a number, just use it
            if (json.get("version") instanceof Number) {
                return json.getInt("version");
            }
            // Otherwise parse "a.b.c.d"
            String v = json.getString("version").trim();
            String[] parts = v.split("\\.");
            int a = part(parts, 0), b = part(parts, 1), c = part(parts, 2), d = part(parts, 3);
            a = clamp(a); b = clamp(b); c = clamp(c); d = clamp(d);
            return a * 1_000_000 + b * 10_000 + c * 100 + d;
        } catch (Exception e) {
            // Fallback: treat as 0 so we don't accidentally skip acceptance
            return 0;
        }
    }

    private static int part(String[] parts, int idx) {
        if (idx >= parts.length) return 0;
        try { return Integer.parseInt(parts[idx]); } catch (Exception ignored) { return 0; }
    }

    private static int clamp(int x) {
        if (x < 0) return 0;
        if (x > 99) return 99;
        return x;
    }

    /**
     * Returns HTML body either from inline "html" or by downloading "html_url".
     */
    private String resolveHtml(JSONObject json) throws Exception {
        if (json.has("html")) {
            return json.getString("html");
        }
        if (json.has("html_url")) {
            String htmlUrl = json.getString("html_url");
            Request htmlReq = new Request.Builder().url(htmlUrl).build();
            try (Response htmlResp = http.newCall(htmlReq).execute()) {
                if (!htmlResp.isSuccessful()) {
                    throw new IllegalStateException("HTML HTTP " + htmlResp.code());
                }
                return htmlResp.body() != null ? htmlResp.body().string() : "";
            }
        }
        throw new IllegalStateException("Terms JSON must include 'html' or 'html_url'");
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] out = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : out) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
