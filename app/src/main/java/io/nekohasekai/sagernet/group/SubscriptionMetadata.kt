/******************************************************************************
 *                                                                            *
 * Copyright (C) 2021 by nekohasekai <contact-sagernet@sekai.icu>             *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 *  the Free Software Foundation, either version 3 of the License, or         *
 *  (at your option) any later version.                                       *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 * GNU General Public License for more details.                               *
 *                                                                            *
 * You should have received a copy of the GNU General Public License          *
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.      *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.group

import java.util.Base64

/**
 * NextClave (adapted from Throne's SubscriptionMetadata.kt): what a subscription says
 * about itself. The response headers Subscription-UserInfo, profile-update-interval
 * (+ the x- prefixed variant), Profile-Title and Announce, then the body's leading
 * `#key: value` (or `//key: value`) comment lines for whatever the headers left out —
 * so plain text lists and `content://` links carry metadata too.
 */
internal object SubscriptionMetadata {

    class Info {
        /** -1 = not provided (quota fields stay "unknown" unless upload/download/total/expire appear). */
        var upload = -1L
        var download = -1L
        var total = -1L
        var expire = -1L // epoch seconds

        var title = ""

        /** Panel announcement, shown in the group header. */
        var announce = ""

        /** profile-update-interval in hours, 0 = none. */
        var serverInterval = 0

        val hasQuota: Boolean get() = upload > 0 || download > 0 || total > 0 || expire > 0
    }

    private const val HEAD_CHARS = 16384
    private const val MAX_INTERVAL_HOURS = 24L * 366

    /** [header] looks a response header up case-insensitively and returns "" when absent. */
    fun read(header: (String) -> String, body: String): Info {
        val info = Info()

        parseUserInfoHeader(header("Subscription-UserInfo"))?.let { (u, d, t, e) ->
            info.upload = u
            info.download = d
            info.total = t
            info.expire = e
        }

        parseUpdateInterval(header("profile-update-interval").ifEmpty { header("x-profile-update-interval") })
            ?.let { info.serverInterval = it }

        decodeHeaderValue(header("Profile-Title")).takeIf { it.isNotEmpty() }?.let { info.title = it }
        decodeHeaderValue(header("Announce")).takeIf { it.isNotEmpty() }?.let { info.announce = it }

        // The body's leading `#key: value` comments fill whatever the headers left out.
        // Only the head of the body is scanned (a subscription can be tens of MiB).
        var userInfoSeen = info.hasQuota
        for (rawLine in metadataHead(body).lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (!line.startsWith("#") && !line.startsWith("//")) break
            val comment = (if (line.startsWith("//")) line.substring(2) else line.substring(1)).trim()
            val colon = comment.indexOf(':')
            if (colon < 0) continue
            val key = comment.substring(0, colon).trim()
            val value = comment.substring(colon + 1).trim()
            when {
                !userInfoSeen && key.startsWith("subscription-userinfo", ignoreCase = true) -> {
                    parseUserInfoHeader(value)?.let { (u, d, t, e) ->
                        info.upload = u
                        info.download = d
                        info.total = t
                        info.expire = e
                        userInfoSeen = true
                    }
                }

                info.serverInterval == 0 && key.startsWith("profile-update-interval", ignoreCase = true) -> {
                    parseUpdateInterval(value)?.let { info.serverInterval = it }
                }

                info.title.isEmpty() && key.startsWith("profile-title", ignoreCase = true) -> {
                    info.title = decodeHeaderValue(value)
                }

                info.announce.isEmpty() &&
                        (key.startsWith("announce", ignoreCase = true) || key.startsWith("notice", ignoreCase = true)) -> {
                    info.announce = decodeHeaderValue(value)
                }
            }
        }
        return info
    }

    /** Parse `upload=1; download=2; total=3; expire=1700000000` (any subset, any order). */
    private fun parseUserInfoHeader(raw: String): LongArray? {
        var upload = -1L
        var download = -1L
        var total = -1L
        var expire = -1L
        var seen = false
        for (part in raw.split(';')) {
            val eq = part.indexOf('=')
            if (eq < 0) continue
            val key = part.substring(0, eq).trim().lowercase()
            val value = part.substring(eq + 1).trim().toLongOrNull() ?: continue
            when (key) {
                "upload" -> { upload = value; seen = true }
                "download" -> { download = value; seen = true }
                "total" -> { total = value; seen = true }
                "expire" -> {
                    // A value in milliseconds is taken as seconds.
                    expire = if (value > 1_000_000_000_000L) value / 1000 else value
                    seen = true
                }
            }
        }
        return if (seen) longArrayOf(upload, download, total, expire) else null
    }

    /** A `base64:` value, url-safe first, then lenient standard; anything else returns as is. */
    fun decodeHeaderValue(raw: String): String {
        val str = raw.trim()
        if (!str.startsWith("base64:", ignoreCase = true)) return str
        val payload = str.substring(7).trim()
        if (payload.isEmpty()) return ""
        val decoded = try {
            Base64.getUrlDecoder().decode(payload)
        } catch (_: Exception) {
            try {
                Base64.getMimeDecoder().decode(payload)
            } catch (_: Exception) {
                ByteArray(0)
            }
        }
        return if (decoded.isNotEmpty()) String(decoded, Charsets.UTF_8).trim() else ""
    }

    /** Hours; `h`/`d`/`s` suffixes are tolerated (`12h`, `1d`, `43200s`); null = not parseable. */
    fun parseUpdateInterval(raw: String): Int? {
        var text = raw.trim().replace("\"", "").replace("'", "").lowercase()
        var unit = 3600L
        when {
            text.endsWith("h") -> text = text.dropLast(1)
            text.endsWith("d") -> { text = text.dropLast(1); unit = 86400L }
            text.endsWith("s") -> { text = text.dropLast(1); unit = 1L }
        }
        val value = text.trim().toLongOrNull() ?: return null
        if (value <= 0) return null
        return (minOf(value, MAX_INTERVAL_HOURS * 3600) * unit / 3600).coerceIn(1L, MAX_INTERVAL_HOURS).toInt()
    }

    /** The body's head, decoded first when the body is one base64 blob (comments live in the decoded text). */
    private fun metadataHead(body: String): String {
        var start = 0
        while (start < body.length && (body[start].isWhitespace() || body[start] == '\uFEFF')) start++
        var end = body.length
        while (end > start && (body[end - 1].isWhitespace() || body[end - 1] == '\uFEFF')) end--
        val head = body.substring(start, minOf(end, start + HEAD_CHARS))
        val compact = head.filterNot { it.isWhitespace() }
        if (compact.length < 16) return head
        // Rough base64-blob check: only base64 alphabet, no `://` scheme, no newlines inside.
        if (compact.contains("://") || !compact.matches(Regex("[A-Za-z0-9+/=_-]+"))) return head
        return try {
            val bytes = try {
                Base64.getUrlDecoder().decode(compact.substring(0, compact.length and 3.inv()))
            } catch (_: Exception) {
                Base64.getMimeDecoder().decode(compact.substring(0, compact.length and 3.inv()))
            }
            String(bytes, Charsets.UTF_8)
        } catch (_: Exception) {
            head
        }
    }

}
