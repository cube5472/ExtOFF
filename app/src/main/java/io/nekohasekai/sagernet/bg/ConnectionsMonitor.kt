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
import io.nekohasekai.sagernet.ACCESS_LOG_FILE
import io.nekohasekai.sagernet.ERROR_LOG_FILE
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * NextClave: live feed of VPN connections. Two independent layers, so the
 * viewer keeps working even when one of them is unavailable on a device:
 *
 * 1. PRIMARY — the core access log file (cacheDir/access.log, configured in
 *    ConfigBuilder). exclave-core is a v2fly/v2ray-core fork, and v2ray
 *    access lines have NO "from" keyword (that is Xray):
 *
 *      2026/10/23 14:20:05 172.19.0.2:41234 accepted tcp:host.com:443 [proxy]
 *      2026/10/23 14:20:05 172.19.0.2:41234 rejected tcp:1.2.3.4:25 <reason…>
 *
 *    The file is truncated by ProxyInstance on every VPN start and tailed
 *    here at ~1 Hz. This works on every Android version: the file belongs
 *    to the app itself, so SELinux / logcat restrictions do not apply.
 *    (History: v25–v29 parsed logcat and dumped netlink // /proc/net —
 *    both dead ends on Android 10+; v30 had the right channel but parsed
 *    the lines with an Xray-shaped regex, which rejected every line.)
 *
 * 2. SECONDARY — the core error log file (cacheDir/core_error.log, also
 *    configured in ConfigBuilder while the viewer is on). At debug level it
 *    carries GeneralMessage lines (timestamp prefix added by the writer):
 *
 *      2026/10/23 14:20:06 sniffed domain: api.example.com for tcp:149.154.167.4:443
 *      2026/10/23 14:20:06 taking detour [proxy] for [tcp:149.154.167.4:443]
 *      2026/10/23 14:20:06 default route for tcp:149.154.167.4:443
 *
 *    These enrich the access-log entries (domain, outbound chip); parsing
 *    is tolerant to leftover "[Info] "-style severity prefixes. The logcat
 *    channel is kept as a third enrichment source for other core builds.
 *
 * The core emits exactly one access line per connection (on accept), so
 * "live" is emulated: an entry stays active for LIVE_TCP_MS / LIVE_UDP_MS
 * after it was seen, then it is shown as closed until it ages out.
 */
object ConnectionsMonitor {

    private const val TAG = "ConnectionsMonitor"

    // How long closed entries stay in the list, and how many are kept at most.
    private const val TTL_MS = 15 * 60 * 1000L
    private const val MAX_ENTRIES = 500
    private const val TAIL_INTERVAL_MS = 700L
    // Never read more than this in one step (first read after install etc.).
    private const val MAX_TAIL_STEP = 4L * 1024 * 1024

    /** Diagnostic counters, shown in the empty state so a broken channel is
     *  obvious from the screen itself instead of requiring a repro report. */
    private val diag = Diag()

    private class Diag {
        @Volatile var accessBytes: Long = 0
        @Volatile var accessLines: Long = 0
        @Volatile var accessParsed: Long = 0
        @Volatile var errBytes: Long = 0
        @Volatile var errLines: Long = 0
        @Volatile var sampleRejected: String? = null
    }

    fun debugStats(): String {
        val d = diag
        val sb = StringBuilder("access.log: ")
        sb.append(d.accessBytes).append("B / ").append(d.accessLines)
            .append(" lines / ").append(d.accessParsed).append(" ok")
        val ef = errorFile()
        if (ef.exists()) {
            sb.append("\nerror.log: ").append(d.errBytes).append("B / ")
                .append(d.errLines).append(" lines")
        }
        val sample = d.sampleRejected
        if (d.accessLines > 0 && d.accessParsed == 0L && sample != null) {
            sb.append("\nsample: ").append(sample.take(120))
        }
        return sb.toString()
    }

    // Access log has no close events; emulate liveness by entry age.
    private const val LIVE_TCP_MS = 5 * 60 * 1000L
    private const val LIVE_UDP_MS = 2 * 60 * 1000L

    class ConnEntry(
        val key: String,
        val proto: String, // "TCP" / "UDP"
        val src: String, // ip:port as reported (TUN client side)
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
        @Volatile var live: Boolean, // shown as active right now
    )

    private val running = AtomicBoolean(false)
    @Volatile private var process: Process? = null

    /** Per-file tail positions; reset automatically when a file shrinks. */
    private class TailState(@Volatile var offset: Long)
    private val accessTail = TailState(0L)
    private val errorTail = TailState(0L)

    private val lock = Any()
    private val store = HashMap<String, ConnEntry>() // access-log + logcat history
    private val _connections = MutableStateFlow<List<ConnEntry>>(emptyList())
    val connections: StateFlow<List<ConnEntry>> get() = _connections

    // ------------------------------------------------------------------ api

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val tsFormat = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US)
        startTail({ accessFile() }, accessTail) { line -> parseAccessLine(line, tsFormat) }
        startTail({ errorFile() }, errorTail, ::parseErrorLine)
        startLogcatThread()
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

    private fun accessFile(): File =
        File(SagerNet.application.cacheDir, ACCESS_LOG_FILE)

    private fun errorFile(): File =
        File(SagerNet.application.cacheDir, ERROR_LOG_FILE)

    private fun liveTtl(proto: String): Long =
        if (proto.equals("UDP", ignoreCase = true)) LIVE_UDP_MS else LIVE_TCP_MS

    // --------------------------------------------------------- tail engine

    private fun startTail(fileProvider: () -> File, state: TailState, onLine: (String) -> Unit) {
        runOnDefaultDispatcher {
            while (running.get()) {
                try {
                    tailOnce(fileProvider(), state, onLine)
                } catch (e: Throwable) {
                    Log.w(TAG, "log tail failed: ${e.message}")
                }
                try {
                    Thread.sleep(TAIL_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun tailOnce(f: File, state: TailState, onLine: (String) -> Unit) {
        if (!f.exists()) return
        val size = f.length()
        var from = state.offset
        if (size < from) {
            // file was truncated (fresh VPN session) — start over
            from = 0L
            state.offset = 0L
        }
        if (size == from) return
        if (size - from > MAX_TAIL_STEP) from = size - MAX_TAIL_STEP
        val lines = ArrayList<String>()
        RandomAccessFile(f, "r").use { raf ->
            raf.seek(from)
            val buf = ByteArray((size - from).toInt())
            raf.readFully(buf)
            var start = 0
            for (i in buf.indices) {
                if (buf[i] == '\n'.code.toByte()) {
                    lines.add(String(buf, start, i - start))
                    start = i + 1
                }
            }
            // only advance past complete lines
            state.offset = from + start
        }
        for (line in lines) onLine(line.trim())
    }

    // exclave-core = v2fly/v2ray-core fork. v2ray access format (NO "from",
    // no "in -> out" pair — that is Xray; keep both tolerated anyway):
    //   <ts> <src> accepted <proto>:<dst> [<detour>] [email: <e>]
    //   <ts> <src> rejected <proto>:<dst> <free-text reason…>
    private val reAccess = Regex(
        """^(\d{4}/\d{2}/\d{2} \d{2}:\d{2}:\d{2}) (?:from )?(\S+) (accepted|rejected) (tcp|udp):(\S+)(?: \[([^\]]+)\])?(?: email: (\S+))?"""
    )

    private fun parseAccessLine(line: String, tsFormat: SimpleDateFormat) {
        if (line.isEmpty()) return
        diag.accessLines += 1
        diag.accessBytes += line.length + 1
        val m = reAccess.find(line)
        if (m == null) {
            diag.sampleRejected = line
            return
        }
        diag.accessParsed += 1
        val src = m.groupValues[2]
        val accepted = m.groupValues[3] == "accepted"
        val protoS = m.groupValues[4]
        val dst = splitAddrPort(m.groupValues[5]) ?: return
        val outbound = m.groupValues[6].ifEmpty { null }
        val ts = try {
            tsFormat.parse(m.groupValues[1])?.time ?: System.currentTimeMillis()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
        synchronized(lock) {
            val now = System.currentTimeMillis()
            purgeLocked(now)
            val key = "${protoS.lowercase()}|$src|${dst.first}:${dst.second}"
            val existing = store[key]
            if (existing != null) {
                existing.lastSeen = now
                if (!accepted) existing.blocked = true
                if (outbound != null) {
                    if (outbound == "dns-out") existing.isDns = true
                    existing.outbound = outbound
                }
                publishLocked()
                return
            }
            store[key] = ConnEntry(
                key = key,
                proto = protoS.uppercase(),
                src = src,
                dstAddr = dst.first,
                dstPort = dst.second,
                uid = -1,
                pkg = null,
                domain = null,
                outbound = outbound,
                isDefault = outbound == null,
                isDns = dst.second == 53 || outbound == "dns-out",
                blocked = !accepted,
                firstSeen = ts,
                lastSeen = now,
                live = true,
            )
            publishLocked()
        }
    }

    // ------------------------------------------------- error log / logcat layer

    // v2ray file writers prefix every line with the timestamp.
    private val reTsPrefix = Regex("""^\d{4}/\d{2}/\d{2} \d{2}:\d{2}:\d{2} """)

    private fun parseErrorLine(raw: String) {
        if (raw.isEmpty()) return
        diag.errLines += 1
        diag.errBytes += raw.length + 1
        parseLine(reTsPrefix.replaceFirst(raw, ""))
    }

    private val reConn = Regex("""^\[(TCP|UDP) \((-?\d+)(?:/([^)]+))?\)\] (\S+) ==> (\S+)$""")
    private val reSniff = Regex("""^sniffed domain: (\S+) for (tcp|udp):(\S+)$""")
    private val reDetour = Regex("""^taking detour \[([^]]+)] for \[(tcp|udp):(\S+)]$""")
    private val reDefault = Regex("""^default route for (tcp|udp):(\S+)$""")
    private val reBlocked = Regex("""^blocked (TCP|UDP) from (\S+) to (\S+) by (.+)$""")

    private val prefixes = arrayOf("[Debug] ", "[Info] ", "[Warning] ", "[Error] ")

    private fun startLogcatThread() {
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

    private fun parseLine(rawLine: String) {
        if (rawLine.isEmpty()) return
        // Some cores/OEM loggers keep the severity prefix; tolerate any.
        var line = rawLine
        for (p in prefixes) {
            if (line.startsWith(p)) {
                line = line.substring(p.length)
                break
            }
        }
        when {
            line.startsWith("[TCP (") || line.startsWith("[UDP (") -> parseConn(line)
            line.startsWith("sniffed domain: ") -> parseSniffed(line)
            line.startsWith("taking detour [") -> parseDetour(line)
            line.startsWith("default route for ") -> parseDefault(line)
            line.startsWith("blocked ") -> parseBlocked(line)
        }
    }

    private fun splitAddrPort(s: String): Pair<String, Int>? {
        val idx = s.lastIndexOf(':')
        if (idx <= 0 || idx == s.length - 1) return null
        val port = s.substring(idx + 1).toIntOrNull() ?: return null
        return s.substring(0, idx) to port
    }

    private fun parseConn(line: String) {
        val m = reConn.find(line) ?: return
        val protoS = m.groupValues[1]
        val uid = m.groupValues[2].toIntOrNull() ?: -1
        val pkg = m.groupValues[3].ifEmpty { null }
        val src = m.groupValues[4]
        val dst = splitAddrPort(m.groupValues[5]) ?: return
        synchronized(lock) {
            val now = System.currentTimeMillis()
            purgeLocked(now)
            val key = "${protoS.lowercase()}|$src|${dst.first}:${dst.second}"
            val existing = store[key]
            if (existing != null) {
                existing.lastSeen = now
                if (pkg != null) existing.pkg = pkg
                if (uid >= 0) existing.uid = uid
                publishLocked()
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
                live = true,
            )
            publishLocked()
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
            if (changed) publishLocked()
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
            if (changed) publishLocked()
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
                publishLocked()
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
                live = true,
            )
            publishLocked()
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
            if (changed) publishLocked()
        }
    }

    // -------------------------------------------------------- publishing

    private fun publishLocked() {
        val now = System.currentTimeMillis()
        val active = ArrayList<ConnEntry>()
        val closed = ArrayList<ConnEntry>()
        for (e in store.values) {
            if (e.lastSeen < now - TTL_MS) continue
            if (now - e.firstSeen < liveTtl(e.proto)) {
                e.live = true
                active.add(e)
            } else {
                e.live = false
                closed.add(e)
            }
        }
        active.sortByDescending { it.firstSeen }
        closed.sortByDescending { it.lastSeen }
        val merged = ArrayList<ConnEntry>(active.size + closed.size)
        merged.addAll(active)
        merged.addAll(closed)
        _connections.value = if (merged.size > MAX_ENTRIES) {
            merged.take(MAX_ENTRIES)
        } else merged
    }

    private fun purgeLocked(now: Long) {
        if (store.isEmpty()) return
        val it = store.entries.iterator()
        var removed = false
        while (it.hasNext()) {
            if (it.next().value.lastSeen < now - TTL_MS) {
                it.remove()
                removed = true
            }
        }
        while (store.size > MAX_ENTRIES) {
            var oldest: Map.Entry<String, ConnEntry>? = null
            for (e in store.entries) {
                if (oldest == null || e.value.lastSeen < oldest.value.lastSeen) oldest = e
            }
            if (oldest == null) break
            store.remove(oldest.key)
            removed = true
        }
        if (removed) {
            for (key in outboundCache.keys.toSet()) {
                if (outboundCache.size <= 1024) break
                outboundCache.remove(key)
            }
        }
    }

    // ----------------------------------------------------- pkg resolution

    private val pkgCache = HashMap<Int, String>()

    private fun resolvePkg(uid: Int): String? {
        if (uid < 10000) return null
        val cached = pkgCache[uid]
        if (cached != null) return cached.ifEmpty { null }
        return try {
            val names = SagerNet.application.packageManager.getPackagesForUid(uid)
            val name = names?.firstOrNull { it.isNotEmpty() }
            pkgCache[uid] = name ?: ""
            name
        } catch (e: Exception) {
            ""
        }
    }

}
