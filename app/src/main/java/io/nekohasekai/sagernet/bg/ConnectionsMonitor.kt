/******************************************************************************
 *                                                                            *
 * NextClave: live connections monitor                                        *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
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

package io.nekohasekai.sagernet.bg

import android.util.Log
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * NextClave: live feed of VPN connections, parsed from the routing core's
 * logcat lines (tag "exclave-core"). The Go core runs in-process and writes
 * one Info-level line per new connection (when uid dumping is enabled):
 *
 *   [TCP (10391/com.example.app)] 10.0.0.2:54321 ==> tcp:149.154.167.4:443
 *   [UDP (10391)] 10.0.0.2:54321 ==> udp:1.2.3.4:53
 *
 * plus routing hints:
 *
 *   sniffed domain: api.telegram.org for tcp:149.154.167.4:443
 *   taking detour [proxy] for [tcp:149.154.167.4:443]
 *   default route for tcp:149.154.167.4:443
 *   blocked TCP from 10.0.0.2:54321 to 149.154.167.4:443 by com.example.app (10391)
 *
 * The monitor runs `logcat -T 500 -v raw exclave-core:V *:S`: an application
 * can only read its own log output, which is exactly the scope we need.
 * All matching happens by strict regular expressions; anything else is
 * ignored, so unrelated core log lines can't break the feed.
 */
object ConnectionsMonitor {

    private const val TAG = "ConnectionsMonitor"

    // How long entries stay in the list, and how many are kept at most.
    private const val TTL_MS = 15 * 60 * 1000L
    private const val MAX_ENTRIES = 500

    class ConnEntry(
        val key: String,
        val proto: String, // "TCP" / "UDP"
        val src: String, // ip:port as reported by the core
        val dstAddr: String,
        val dstPort: Int,
        @Volatile var uid: Int,
        @Volatile var pkg: String?, // package name when known
        @Volatile var domain: String?, // sniffed host
        @Volatile var outbound: String?, // matched outbound tag
        @Volatile var isDefault: Boolean, // no routing rule matched
        @Volatile var isDns: Boolean,
        @Volatile var blocked: Boolean,
        val firstSeen: Long,
        @Volatile var lastSeen: Long,
    )

    private val reConn = Regex("""^\[(TCP|UDP) \((-?\d+)(?:/([^)]+))?\)\] (\S+) ==> (\S+)$""")
    private val reSniff = Regex("""^sniffed domain: (\S+) for (tcp|udp):(\S+)$""")
    private val reDetour = Regex("""^taking detour \[([^]]+)] for \[(tcp|udp):(\S+)]$""")
    private val reDefault = Regex("""^default route for (tcp|udp):(\S+)$""")
    private val reBlocked = Regex("""^blocked (TCP|UDP) from (\S+) to (\S+) by (.+)$""")

    private val lock = Any()
    private val store = HashMap<String, ConnEntry>()
    private val _connections = MutableStateFlow<List<ConnEntry>>(emptyList())
    val connections: StateFlow<List<ConnEntry>> get() = _connections

    private val running = AtomicBoolean(false)
    @Volatile private var process: Process? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        runOnDefaultDispatcher {
            while (running.get()) {
                val proc = try {
                    ProcessBuilder("logcat", "-T", "500", "-v", "raw", "exclave-core:V", "*:S")
                        .redirectErrorStream(true)
                        .start()
                } catch (e: Exception) {
                    Log.w(TAG, "logcat unavailable: ${e.message}")
                    null
                }
                if (proc == null) {
                    if (!running.get()) break
                    try {
                        Thread.sleep(1000)
                    } catch (_: InterruptedException) {
                    }
                    continue
                }
                process = proc
                try {
                    proc.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            if (!running.get()) break
                            parseLine(line.trim())
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "logcat stream ended: ${e.message}")
                }
                try {
                    proc.destroy()
                } catch (_: Exception) {
                }
                if (process === proc) process = null
                if (running.get()) {
                    try {
                        Thread.sleep(1000) // stream died — retry shortly
                    } catch (_: InterruptedException) {
                    }
                }
            }
        }
    }

    fun stop() {
        running.set(false)
        try {
            process?.destroy()
        } catch (_: Exception) {
        }
        process = null
    }

    fun clear() {
        synchronized(lock) {
            store.clear()
            _connections.value = emptyList()
        }
    }

    private fun splitAddrPort(s: String): Pair<String, Int>? {
        val idx = s.lastIndexOf(':')
        if (idx <= 0 || idx == s.length - 1) return null
        val port = s.substring(idx + 1).toIntOrNull() ?: return null
        return s.substring(0, idx) to port
    }

    private fun parseLine(line: String) {
        if (line.isEmpty()) return
        when {
            line.startsWith("[TCP (") || line.startsWith("[UDP (") -> parseConn(line)
            line.startsWith("sniffed domain: ") -> parseSniffed(line)
            line.startsWith("taking detour [") -> parseDetour(line)
            line.startsWith("default route for ") -> parseDefault(line)
            line.startsWith("blocked ") -> parseBlocked(line)
        }
    }

    private fun parseConn(line: String) {
        val m = reConn.find(line) ?: return
        val protoS = m.groupValues[1]
        val uid = m.groupValues[2].toIntOrNull() ?: -1
        val pkg = m.groupValues[3].ifEmpty { null }
        val src = m.groupValues[4]
        val dst = splitAddrPort(m.groupValues[5]) ?: return
        synchronized(lock) {
            val key = "${protoS.lowercase()}|$src|${dst.first}:${dst.second}"
            val now = System.currentTimeMillis()
            purgeLocked(now)
            val existing = store[key]
            if (existing != null) {
                existing.lastSeen = now
                if (pkg != null) existing.pkg = pkg
                if (uid >= 0) existing.uid = uid
                rebuildLocked()
                return
            }
            store[key] = ConnEntry(
                key = key,
                proto = protoS,
                src = src,
                dstAddr = dst.first,
                dstPort = dst.second,
                uid = uid,
                pkg = pkg,
                domain = null,
                outbound = outboundCache["${protoS.lowercase()}:${dst.first}:${dst.second}"],
                isDefault = false,
                isDns = dst.second == 53,
                blocked = false,
                firstSeen = now,
                lastSeen = now,
            )
            rebuildLocked()
        }
    }

    /** Remember routing decisions per destination so fresh connections can
     *  show the outbound chip immediately. */
    private val outboundCache = HashMap<String, String>()

    private fun parseSniffed(line: String) {
        val m = reSniff.find(line) ?: return
        val domain = m.groupValues[1]
        val dst = splitAddrPort(m.groupValues[3]) ?: return
        applyTo("${m.groupValues[2]}|${dst.first}:${dst.second}") { entry ->
            entry.domain = domain
        }
    }

    private fun parseDetour(line: String) {
        val m = reDetour.find(line) ?: return
        val tag = m.groupValues[1]
        val dst = splitAddrPort(m.groupValues[3]) ?: return
        val key = "${m.groupValues[2]}|${dst.first}:${dst.second}"
        synchronized(lock) {
            outboundCache[key] = tag
            var changed = false
            for (entry in store.values) {
                val entryKey = "${entry.key.substringBefore('|')}|${entry.dstAddr}:${entry.dstPort}"
                if (entryKey == key) {
                    if (tag == "dns-out") {
                        entry.isDns = true
                    }
                    entry.outbound = tag
                    changed = true
                }
            }
            if (changed) rebuildLocked()
        }
    }

    private fun parseDefault(line: String) {
        val m = reDefault.find(line) ?: return
        val dst = splitAddrPort(m.groupValues[2]) ?: return
        val key = "${m.groupValues[1]}|${dst.first}:${dst.second}"
        synchronized(lock) {
            var changed = false
            for (entry in store.values) {
                val entryKey = "${entry.key.substringBefore('|')}|${entry.dstAddr}:${entry.dstPort}"
                if (entryKey == key && entry.outbound == null && !entry.isDefault) {
                    entry.isDefault = true
                    changed = true
                }
            }
            if (changed) rebuildLocked()
        }
    }

    private fun parseBlocked(line: String) {
        val m = reBlocked.find(line) ?: return
        val protoS = m.groupValues[1]
        val src = m.groupValues[2]
        val dst = splitAddrPort(m.groupValues[3]) ?: return
        val by = m.groupValues[4].trim()
        var uid = -1
        var pkg: String? = null
        val paren = Regex("""^(.*) \((-?\d+)\)$""").find(by)
        if (paren != null) {
            pkg = paren.groupValues[1].ifEmpty { null }
            uid = paren.groupValues[2].toIntOrNull() ?: -1
        } else {
            uid = by.toIntOrNull() ?: -1
            if (uid == -1) pkg = by
        }
        synchronized(lock) {
            val now = System.currentTimeMillis()
            purgeLocked(now)
            val key = "${protoS.lowercase()}|$src|${dst.first}:${dst.second}"
            val existing = store[key]
            if (existing != null) {
                existing.blocked = true
                existing.lastSeen = now
                if (pkg != null) existing.pkg = pkg
                rebuildLocked()
                return
            }
            store[key] = ConnEntry(
                key = key,
                proto = protoS,
                src = src,
                dstAddr = dst.first,
                dstPort = dst.second,
                uid = uid,
                pkg = pkg,
                domain = null,
                outbound = null,
                isDefault = false,
                isDns = dst.second == 53,
                blocked = true,
                firstSeen = now,
                lastSeen = now,
            )
            rebuildLocked()
        }
    }

    private inline fun applyTo(destKey: String, apply: (ConnEntry) -> Unit) {
        synchronized(lock) {
            var changed = false
            for (entry in store.values) {
                val entryKey = "${entry.key.substringBefore('|')}|${entry.dstAddr}:${entry.dstPort}"
                if (entryKey == destKey) {
                    apply(entry)
                    changed = true
                }
            }
            if (changed) rebuildLocked()
        }
    }

    private fun purgeLocked(now: Long) {
        if (store.isEmpty()) return
        val it = store.entries.iterator()
        var removed = false
        while (it.hasNext()) {
            if (it.next().value.firstSeen < now - TTL_MS) {
                it.remove()
                removed = true
            }
        }
        while (store.size > MAX_ENTRIES) {
            var oldest: Map.Entry<String, ConnEntry>? = null
            for (e in store.entries) {
                if (oldest == null || e.value.firstSeen < oldest.value.firstSeen) oldest = e
            }
            if (oldest == null) break
            store.remove(oldest.key)
            removed = true
        }
        if (removed) {
            for (key in outboundCache.keys.toSet()) {
                // keep the routing cache small too
                if (outboundCache.size <= 1024) break
                outboundCache.remove(key)
            }
        }
    }

    private fun rebuildLocked() {
        if (store.isEmpty()) {
            _connections.value = emptyList()
            return
        }
        _connections.value = store.values.sortedByDescending { it.firstSeen }
    }

}
