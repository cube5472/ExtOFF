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

import android.os.Build
import android.system.Os
import android.system.OsConstants
import android.util.Log
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * NextClave: live feed of VPN connections. Three independent layers, so the
 * viewer keeps working even when one of them is unavailable on a device:
 *
 * 1. Core log lines (logcat tag "exclave-core", Info level, written by the
 *    in-process Go core when uid dumping is enabled):
 *
 *      [TCP (10391/com.example.app)] 10.0.0.2:54321 ==> 149.154.167.4:443
 *      [UDP (10391)] 10.0.0.2:54321 ==> 1.2.3.4:53
 *      sniffed domain: api.example.com for tcp:149.154.167.4:443
 *      taking detour [proxy] for [tcp:149.154.167.4:443]
 *      default route for tcp:149.154.167.4:443
 *      blocked TCP from 10.0.0.2:54321 to 149.154.167.4:443 by com.example.app (10391)
 *
 *    This layer is the ONLY source of sniffed domains / outbound tags /
 *    blocked flags, so it always runs. Parsing is tolerant to a leftover
 *    "[Info] "-style severity prefix.
 *
 * 2. Live socket table via netlink INET_DIAG (SOCK_DIAG_BY_FAMILY dumps of
 *    TCP/UDP for IPv4/IPv6 — the same mechanism PCAPdroid uses without root
 *    on Android 10+, where /proc/net is restricted). Rebuilt every second:
 *    entries disappear as soon as the socket closes, so the list reflects
 *    the CURRENT connections.
 *
 * 3. Fallback: /proc/net/{tcp,tcp6,udp,udp6} for devices where netlink is
 *    unavailable (some SELinux policies / old kernels).
 *
 * Logcat entries enrich the live ones (by "proto|src|dst" key) with domain,
 * outbound and blocked info; closed connections remain visible from the
 * history layer until they age out.
 */
object ConnectionsMonitor {

    private const val TAG = "ConnectionsMonitor"

    // How long closed entries stay in the list, and how many are kept at most.
    private const val TTL_MS = 15 * 60 * 1000L
    private const val MAX_ENTRIES = 500
    private const val POLL_INTERVAL_MS = 1000L

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
        @Volatile var live: Boolean, // socket still open right now
    )

    private val running = AtomicBoolean(false)
    @Volatile private var process: Process? = null
    @Volatile private var pollThread: Thread? = null

    private val lock = Any()
    private val store = HashMap<String, ConnEntry>() // logcat history + enrichment
    private val liveStore = HashMap<String, ConnEntry>() // currently open sockets
    private val _connections = MutableStateFlow<List<ConnEntry>>(emptyList())
    val connections: StateFlow<List<ConnEntry>> get() = _connections

    // ------------------------------------------------------------------ api

    fun start() {
        if (!running.compareAndSet(false, true)) return
        startPollThread()
        startLogcatThread()
    }

    fun stop() {
        running.set(false)
        try {
            process?.destroy()
        } catch (_: Exception) {
        }
        process = null
        pollThread = null
    }

    fun clear() {
        synchronized(lock) {
            store.clear()
            liveStore.clear()
            _connections.value = emptyList()
        }
    }

    // ------------------------------------------------------- logcat layer

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
            val key = "${protoS.lowercase()}|$src|${dst.first}:${dst.second}"
            val now = System.currentTimeMillis()
            purgeLocked(now)
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
                live = false,
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
                live = false,
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

    // -------------------------------------------------------- poll layer

    private class Sock(val proto: String, val src: String, val dst: String, val uid: Int)

    private fun startPollThread() {
        val t = Thread({
            while (running.get()) {
                var rows: List<Sock>? = null
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        rows = netlinkDump()
                    } catch (e: Throwable) {
                        Log.w(TAG, "netlink diag failed: ${e.message}")
                    }
                }
                if (rows == null || rows.isEmpty()) {
                    try {
                        rows = readProcSockets()
                    } catch (e: Throwable) {
                        Log.w(TAG, "proc sockets failed: ${e.message}")
                    }
                }
                if (rows != null) publishPoll(rows)
                try {
                    Thread.sleep(POLL_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "conn-monitor-poll")
        t.isDaemon = true
        t.start()
        pollThread = t
    }

    private fun publishPoll(rows: List<Sock>) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            purgeLocked(now)
            liveStore.clear()
            for (r in rows) {
                val key = "${r.proto}|${r.src}|${r.dst}"
                if (liveStore.containsKey(key)) continue
                val (dstAddr, dstPort) = splitAddrPort(r.dst) ?: continue
                val hist = store[key]
                val pkg = resolvePkg(r.uid) ?: hist?.pkg
                liveStore[key] = ConnEntry(
                    key = key,
                    proto = r.proto.uppercase(),
                    src = r.src,
                    dstAddr = dstAddr,
                    dstPort = dstPort,
                    uid = r.uid,
                    pkg = pkg,
                    domain = hist?.domain,
                    outbound = hist?.outbound,
                    isDefault = hist?.isDefault ?: false,
                    isDns = dstPort == 53 || (hist?.isDns ?: false),
                    blocked = hist?.blocked ?: false,
                    firstSeen = hist?.firstSeen ?: now,
                    lastSeen = now,
                    live = true,
                )
                if (hist == null) {
                    // remember the connection in the history layer so it
                    // survives (marked closed) after the socket is gone
                    store[key] = ConnEntry(
                        key = key,
                        proto = r.proto.uppercase(),
                        src = r.src,
                        dstAddr = dstAddr,
                        dstPort = dstPort,
                        uid = r.uid,
                        pkg = pkg,
                        domain = null,
                        outbound = null,
                        isDefault = false,
                        isDns = dstPort == 53,
                        blocked = false,
                        firstSeen = now,
                        lastSeen = now,
                        live = false,
                    )
                } else {
                    hist.uid = r.uid
                    if (hist.pkg == null && pkg != null) hist.pkg = pkg
                    hist.lastSeen = now
                }
            }
            publishLocked()
        }
    }

    private fun publishLocked() {
        val now = System.currentTimeMillis()
        val live = liveStore.values.sortedByDescending { it.firstSeen }
        val history = store.values
            .filter { !liveStore.containsKey(it.key) && it.lastSeen >= now - TTL_MS }
            .sortedByDescending { it.lastSeen }
        val merged = ArrayList<ConnEntry>(live.size + history.size)
        merged.addAll(live)
        merged.addAll(history)
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

    // ---------------------------------------------------- netlink INET_DIAG

    // netlink message types / flags
    private const val SOCK_DIAG_BY_FAMILY = 20
    private const val NLMSG_ERROR = 2
    private const val NLMSG_DONE = 3
    private const val NLM_F_REQUEST = 1
    private const val NLM_F_ROOT = 0x100
    private const val NLM_F_MATCH = 0x200

    /** android.system.NetlinkSocketAddress is a hidden API class — build it
     *  reflectively: ctor(int portId, int groups), extends SocketAddress. */
    private fun netlinkAddress(): java.net.SocketAddress {
        val cls = Class.forName("android.system.NetlinkSocketAddress")
        val ctor = cls.getConstructor(
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        )
        return ctor.newInstance(0, 0) as java.net.SocketAddress
    }

    private fun netlinkDump(): List<Sock> {
        val out = ArrayList<Sock>()
        netlinkDumpOne(OsConstants.AF_INET, OsConstants.IPPROTO_TCP, "tcp", out)
        netlinkDumpOne(OsConstants.AF_INET6, OsConstants.IPPROTO_TCP, "tcp", out)
        netlinkDumpOne(OsConstants.AF_INET, OsConstants.IPPROTO_UDP, "udp", out)
        netlinkDumpOne(OsConstants.AF_INET6, OsConstants.IPPROTO_UDP, "udp", out)
        return out
    }

    private fun netlinkDumpOne(family: Int, proto: Int, protoLc: String, out: MutableList<Sock>) {
        val fd = Os.socket(
            OsConstants.AF_NETLINK,
            OsConstants.SOCK_RAW or OsConstants.SOCK_CLOEXEC,
            OsConstants.NETLINK_INET_DIAG,
        )
        try {
            Os.bind(fd, netlinkAddress())
            val req = diagRequest(family, proto)
            Os.sendto(fd, req, 0, req.size, 0, netlinkAddress())
            val buf = ByteBuffer.allocateDirect(1 shl 16)
            while (true) {
                buf.clear()
                val n = try {
                    Os.read(fd, buf)
                } catch (e: Exception) {
                    break
                }
                if (n <= 0) break
                buf.flip()
                val data = ByteArray(n)
                buf.get(data)
                var off = 0
                var done = false
                while (off + 16 <= n) {
                    val len = le32(data, off)
                    val type = le16(data, off + 4)
                    if (len < 16 || off + len > n) {
                        done = true
                        break
                    }
                    if (type == NLMSG_ERROR || type == NLMSG_DONE) {
                        done = true
                        break
                    }
                    if (type == SOCK_DIAG_BY_FAMILY && len >= 16 + 72) {
                        parseDiag(data, off + 16, family, protoLc, out)
                    }
                    off += (len + 3) and 3.inv()
                }
                if (done) break
            }
        } finally {
            try {
                Os.close(fd)
            } catch (_: Exception) {
            }
        }
    }

    /** nlmsghdr + inet_diag_req_v2 for a full dump of `family`/`proto`. */
    private fun diagRequest(family: Int, proto: Int): ByteArray {
        val buf = ByteArray(72)
        // nlmsghdr (host byte order = little endian)
        putLe32(buf, 0, 72) // nlmsg_len
        putLe16(buf, 4, SOCK_DIAG_BY_FAMILY)
        putLe16(buf, 6, NLM_F_REQUEST or NLM_F_ROOT or NLM_F_MATCH)
        putLe32(buf, 8, 1) // nlmsg_seq
        putLe32(buf, 12, 0) // nlmsg_pid
        // inet_diag_req_v2
        buf[16] = family.toByte()
        buf[17] = proto.toByte()
        buf[18] = 0 // idiag_ext
        buf[19] = 0 // pad
        putLe32(buf, 20, 0xFFF) // idiag_states (all)
        // inet_diag_sockid @24: sport(2, BE), dport(2, BE), src[16], dst[16],
        // if(4), cookie[2] — all zeroes except the no-cookie marker
        putLe32(buf, 60, 0) // idiag_if
        putLe32(buf, 64, -1) // cookie[0] = INET_DIAG_NOCOOKIE
        putLe32(buf, 68, -1) // cookie[1]
        return buf
    }

    private fun putLe32(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
        b[off + 2] = ((v shr 16) and 0xFF).toByte()
        b[off + 3] = ((v shr 24) and 0xFF).toByte()
    }

    private fun putLe16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
    }

    private fun parseDiag(d: ByteArray, p: Int, family: Int, protoLc: String, out: MutableList<Sock>) {
        val state = d[p + 1].toInt() and 0xFF
        if (protoLc == "tcp" && state == 0x0A) return // LISTEN
        val sport = be16(d, p + 4)
        val dport = be16(d, p + 6)
        val uid = le32(d, p + 64)
        val isV4 = family == OsConstants.AF_INET
        val srcS = fmtAddr(d, p + 8, isV4, sport)
        val dstS = fmtAddr(d, p + 24, isV4, dport)
        out.add(Sock(protoLc, srcS, dstS, uid))
    }

    private fun le32(d: ByteArray, off: Int): Int {
        return (d[off].toInt() and 0xFF) or
            ((d[off + 1].toInt() and 0xFF) shl 8) or
            ((d[off + 2].toInt() and 0xFF) shl 16) or
            ((d[off + 3].toInt() and 0xFF) shl 24)
    }

    private fun le16(d: ByteArray, off: Int): Int {
        return (d[off].toInt() and 0xFF) or ((d[off + 1].toInt() and 0xFF) shl 8)
    }

    private fun be16(d: ByteArray, off: Int): Int {
        return ((d[off].toInt() and 0xFF) shl 8) or (d[off + 1].toInt() and 0xFF)
    }

    private fun fmtAddr(d: ByteArray, off: Int, isV4: Boolean, port: Int): String {
        return if (isV4) {
            "${d[off].toInt() and 0xFF}.${d[off + 1].toInt() and 0xFF}." +
                "${d[off + 2].toInt() and 0xFF}.${d[off + 3].toInt() and 0xFF}:$port"
        } else {
            "[${ip6(d, off)}]:$port"
        }
    }

    private fun ip6(d: ByteArray, off: Int): String {
        val groups = IntArray(8)
        for (i in 0 until 8) {
            groups[i] = ((d[off + i * 2].toInt() and 0xFF) shl 8) or (d[off + i * 2 + 1].toInt() and 0xFF)
        }
        // find the longest run of zero groups (>= 2) for "::" compression
        var bestStart = -1
        var bestLen = 0
        var i = 0
        while (i < 8) {
            if (groups[i] == 0) {
                var j = i
                while (j < 8 && groups[j] == 0) j++
                val len = j - i
                if (len > bestLen) {
                    bestLen = len
                    bestStart = i
                }
                i = j
            } else i++
        }
        if (bestLen < 2) bestStart = -1
        val sb = StringBuilder()
        i = 0
        while (i < 8) {
            if (i == bestStart) {
                sb.append("::")
                i += bestLen
                continue
            }
            if (sb.isNotEmpty() && !sb.endsWith(":")) sb.append(":")
            sb.append(Integer.toHexString(groups[i]))
            i++
        }
        if (sb.isEmpty()) sb.append("::")
        return sb.toString()
    }

    // ------------------------------------------------- /proc fallback layer

    private fun readProcSockets(): List<Sock> {
        val out = ArrayList<Sock>()
        parseProcFile("/proc/net/tcp", "tcp", out)
        parseProcFile("/proc/net/tcp6", "tcp", out)
        parseProcFile("/proc/net/udp", "udp", out)
        parseProcFile("/proc/net/udp6", "udp", out)
        return out
    }

    private fun parseProcFile(path: String, protoLc: String, out: MutableList<Sock>) {
        try {
            java.io.File(path).useLines { lines ->
                var first = true
                for (line in lines) {
                    if (first) {
                        first = false
                        continue // header
                    }
                    val f = line.trim().split(Regex("\\s+"))
                    if (f.size < 8) continue
                    val local = decodeProcAddr(f[1]) ?: continue
                    val remote = decodeProcAddr(f[2]) ?: continue
                    val state = f[3].toIntOrNull(16) ?: continue
                    if (protoLc == "tcp" && state == 0x0A) continue // LISTEN
                    val uid = f[7].toIntOrNull() ?: continue
                    out.add(Sock(protoLc, local, remote, uid))
                }
            }
        } catch (_: Exception) {
            // /proc/net restricted (Android 10+) — netlink layer covers us
        }
    }

    /** "0100007F:1F90" -> "127.0.0.1:8080"; v6 words are byte-reversed. */
    private fun decodeProcAddr(s: String): String? {
        val idx = s.lastIndexOf(':')
        if (idx <= 0 || idx == s.length - 1) return null
        val port = s.substring(idx + 1).toIntOrNull(16) ?: return null
        val hex = s.substring(0, idx)
        return when (hex.length) {
            8 -> {
                val b = ByteArray(4)
                for (i in 0 until 4) {
                    b[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
                "${b[3].toInt() and 0xFF}.${b[2].toInt() and 0xFF}." +
                    "${b[1].toInt() and 0xFF}.${b[0].toInt() and 0xFF}:$port"
            }
            32 -> {
                val b = ByteArray(16)
                for (w in 0 until 4) {
                    val word = hex.substring(w * 8, w * 8 + 8)
                    for (i in 0 until 4) {
                        b[w * 4 + i] = word.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                    }
                }
                // v6 words are printed in reverse byte order — un-reverse
                for (w in 0 until 4) {
                    var t = b[w * 4]
                    b[w * 4] = b[w * 4 + 3]
                    b[w * 4 + 3] = t
                    t = b[w * 4 + 1]
                    b[w * 4 + 1] = b[w * 4 + 2]
                    b[w * 4 + 2] = t
                }
                "[${ip6(b, 0)}]:$port"
            }
            else -> null
        }
    }

}
