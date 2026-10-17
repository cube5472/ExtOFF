/******************************************************************************
 *                                                                            *
 * Copyright (C) 2024  dyhkwong                                               *
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
 * along with this program. If not, see <https://www.gnu.org/licenses/>.      *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.group

import android.os.Build
import java.util.Locale

// NextClave: subscription client spoofing. Some panels bind subscriptions to a
// specific client application (v2RayTun / Happ / Incy) and reject or restrict
// requests that do not present that client's identity. When a spoof client is
// selected for a subscription, our fetch sends that client's User-Agent and the
// HWID headers from the Happ/Remnawave device-limit standard (x-hwid plus the
// optional x-device-os / x-ver-os / x-device-model / x-app-version hints).
object ClientSpoof {
    const val OFF = 0
    const val V2RAYTUN = 1
    const val HAPP = 2
    const val INCY = 3

    // Reported app versions; panels do not validate them, but real clients do
    // send them, so keep plausible current values.
    const val APP_VERSION_V2RAYTUN = "2.4.6"
    const val APP_VERSION_HAPP = "3.13.0"
    const val APP_VERSION_INCY = "3.3.9"

    fun clientName(client: Int): String = when (client) {
        V2RAYTUN -> "v2RayTun"
        HAPP -> "Happ"
        INCY -> "Incy"
        else -> "off"
    }

    fun userAgent(client: Int): String = when (client) {
        V2RAYTUN -> "v2raytun/android"
        HAPP -> "Happ/$APP_VERSION_HAPP"
        INCY -> "INCY/$APP_VERSION_INCY/android Dalvik/2.1.0"
        else -> ""
    }

    // Same device, different formatting per client: v2RayTun sends the id
    // uppercased, Happ as-is (lowercase), Incy uses its own dashed UUID —
    // for Incy the value is sent exactly as entered.
    fun formatHwid(hwid: String, client: Int): String {
        val stripped = hwid.trim().replace("-", "")
        return when (client) {
            V2RAYTUN -> stripped.uppercase()
            HAPP -> stripped.lowercase()
            else -> hwid.trim()
        }
    }

    fun headers(client: Int, hwid: String): List<Pair<String, String>> {
        val verOs = Build.VERSION.RELEASE ?: ""
        val model = Build.MODEL ?: ""
        return when (client) {
            V2RAYTUN -> listOf(
                "x-hwid" to formatHwid(hwid, client),
                "x-device-os" to "android",
                "x-ver-os" to verOs,
                "x-device-model" to model,
                "x-app-version" to APP_VERSION_V2RAYTUN,
            )
            HAPP -> listOf(
                "x-hwid" to formatHwid(hwid, client),
                "x-device-os" to "Android",
                "x-ver-os" to verOs,
                "x-device-model" to model,
                "x-device-locale" to Locale.getDefault().language,
            )
            INCY -> listOf(
                "x-hwid" to formatHwid(hwid, client),
                "x-client" to "INCY",
                "x-app-version" to APP_VERSION_INCY,
                "x-device-locale" to (Locale.getDefault().language + "_" + Locale.getDefault().country),
            )
            else -> emptyList()
        }
    }
}
