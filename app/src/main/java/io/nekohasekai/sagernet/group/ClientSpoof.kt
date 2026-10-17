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
// selected in the subscription dropdown, our fetch sends that client's
// User-Agent and HWID headers automatically — both are built into the mod,
// nothing has to be typed by hand. The HWIDs are one device bound to the
// user's panel; each client reports the same underlying id in its own exact
// format (case / dashes preserved verbatim).
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

    // Built-in device ids, one per client, sent verbatim (case / dashes are
    // part of the format each client uses):
    //   v2RayTun — uppercased hex, no dashes
    //   Happ     — lowercase hex, no dashes
    //   Incy     — dashed UUID, mixed case
    const val HWID_V2RAYTUN = "852847EEAE408361"
    const val HWID_HAPP = "852847eeae408361"
    const val HWID_INCY = "852847EE-AE40-8361-1AF1-6519B26E857F"

    fun hwid(client: Int): String = when (client) {
        V2RAYTUN -> HWID_V2RAYTUN
        HAPP -> HWID_HAPP
        INCY -> HWID_INCY
        else -> ""
    }

    fun headers(client: Int): List<Pair<String, String>> {
        val verOs = Build.VERSION.RELEASE ?: ""
        val model = Build.MODEL ?: ""
        return when (client) {
            V2RAYTUN -> listOf(
                "x-hwid" to HWID_V2RAYTUN,
                "x-device-os" to "android",
                "x-ver-os" to verOs,
                "x-device-model" to model,
                "x-app-version" to APP_VERSION_V2RAYTUN,
            )
            HAPP -> listOf(
                "x-hwid" to HWID_HAPP,
                "x-device-os" to "Android",
                "x-ver-os" to verOs,
                "x-device-model" to model,
                "x-device-locale" to Locale.getDefault().language,
            )
            INCY -> listOf(
                "x-hwid" to HWID_INCY,
                "x-client" to "INCY",
                "x-app-version" to APP_VERSION_INCY,
                "x-device-locale" to (Locale.getDefault().language + "_" + Locale.getDefault().country),
            )
            else -> emptyList()
        }
    }
}
