/******************************************************************************
 *                                                                            *
 * Copyright (C) 2021 by nekohasekai <contact-sagernet@sekai.icu>             *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 * (at your option) any later version.                                        *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 * GNU General Public License for more details.                               *
 *                                                                            *
 * You should have received a copy of the GNU General Public License          *
 * along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.internal.BalancerBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.internal.ConfigBean
import io.nekohasekai.sagernet.ktx.Logs

/**
 * NextClave v34: per-profile JSON override ("Переопределение").
 *
 * Each ProxyEntity may carry a raw Xray-JSON object fragment. After the whole
 * config has been serialized, the fragment is deep-merged into the outbound
 * object that belongs to this profile (located by outbound tag through
 * outboundTagsAll, which covers every built outbound: the main profile, chain
 * hops, rule outbounds and fork targets alike).
 *
 * Merge semantics:
 *  - object  into object  -> recursive merge (per key)
 *  - null   in the override -> the key is DELETED from the outbound
 *  - array / primitive     -> replaces the existing value wholesale
 *
 * A syntactically invalid fragment can not be saved from the editor (it is
 * validated there), but a runtime failure is never allowed to break starting
 * the VPN: the config is returned unmodified and the error is logged.
 */
object NodeOverrides {

    fun apply(configJson: String, tags: Map<String, ProxyEntity>): String {
        if (tags.isEmpty()) return configJson

        val overrides = HashMap<String, JsonElement>()
        for ((tag, entity) in tags) {
            val text = entity.ncOverrideJson.trim()
            if (text.isEmpty()) continue
            when (entity.requireBean()) {
                is ChainBean, is BalancerBean, is ConfigBean -> continue
            }
            try {
                val parsed = JsonParser.parseString(text)
                if (parsed is JsonObject) {
                    overrides[tag] = parsed
                } else {
                    Logs.w("NodeOverrides: override of tag '$tag' is not a JSON object — skipped")
                }
            } catch (e: Exception) {
                Logs.w("NodeOverrides: invalid override JSON for tag '$tag' — skipped: ${e.message}")
            }
        }
        if (overrides.isEmpty()) return configJson

        return try {
            val root = JsonParser.parseString(configJson).asJsonObject
            val outbounds = root.getAsJsonArray("outbounds") ?: return configJson
            var applied = 0
            for (element in outbounds) {
                val outbound = element as? JsonObject ?: continue
                val tag = outbound.get("tag")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
                val override = overrides[tag] ?: continue
                merge(outbound, override)
                applied++
            }
            if (applied > 0) {
                Logs.i("NodeOverrides: merged JSON overrides into $applied outbound(s)")
                root.toString()
            } else {
                configJson
            }
        } catch (e: Exception) {
            Logs.w("NodeOverrides: failed to apply overrides — config left unmodified: ${e.message}")
            configJson
        }
    }

    private fun merge(dst: JsonObject, src: JsonElement) {
        if (src !is JsonObject) return
        for ((key, value) in src.entrySet()) {
            val current = dst.get(key)
            if (value is JsonNull) {
                if (current != null) dst.remove(key)
            } else if (current is JsonObject && value is JsonObject) {
                merge(current, value)
            } else {
                dst.add(key, value.deepCopy())
            }
        }
    }

}
