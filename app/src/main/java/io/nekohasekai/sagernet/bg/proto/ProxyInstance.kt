/******************************************************************************
 *                                                                            *
 * Copyright (C) 2021 by nekohasekai <contact-sagernet@sekai.icu>             *
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
 * along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.bg.proto

import com.github.exclavenetwork.exclave.core.app.observatory.OutboundStatus
import io.nekohasekai.sagernet.ACCESS_LOG_FILE
import io.nekohasekai.sagernet.ERROR_LOG_FILE
import io.nekohasekai.sagernet.LogLevel
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.TAG_SOCKS
import io.nekohasekai.sagernet.fmt.internal.BalancerBean
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import libexclavecore.Libexclavecore
import libexclavecore.ObservatoryStatusUpdateListener
import java.io.File
import java.io.IOException
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.timerTask

class ProxyInstance(profile: ProxyEntity, val service: BaseService.Interface) : V2RayInstance(
    profile
),
    ObservatoryStatusUpdateListener {

    lateinit var observatoryJob: Job

    override suspend fun init() {
        // NextClave: start every VPN session with fresh log files so the
        // requests viewer does not replay entries from a previous run.
        runCatching {
            val f = File(SagerNet.application.cacheDir, ACCESS_LOG_FILE)
            if (f.length() > 0L) f.writeText("")
            val ef = File(SagerNet.application.cacheDir, ERROR_LOG_FILE)
            if (ef.length() > 0L) ef.writeText("")
        }
        super.init()

        if (DataStore.logLevel == LogLevel.DEBUG) {
            Logs.d(config.config)
            pluginConfigs.forEach { (_, plugin) ->
                val (_, content) = plugin
                Logs.d(content)
            }
        }
    }

    override fun launch() {
        super.launch()

        if (config.observerTag.isNotEmpty()) {
            v2rayPoint.setStatusUpdateListener(config.observerTag, this)
            observatoryJob = runOnDefaultDispatcher {
                sendInitStatuses()
            }
        }

        SagerNet.started = true
    }

    fun sendInitStatuses() {
        val time = (System.currentTimeMillis() / 1000) - 300
        for (observatoryTag in config.observatoryTags) {
            val profileId = if (observatoryTag.contains("global-")) {
                observatoryTag.substringAfter("global-")
            } else {
                observatoryTag.substringAfter("chain-")
            }
            val id = profileId.toLongOrNull()
            if (id == null) {
                continue
            }
            val profile = when {
                id == profile.id -> profile
                statsOutbounds.containsKey(id) -> statsOutbounds[id]!!.proxyEntity
                else -> SagerDatabase.proxyDao.getById(id)
            } ?: continue

            if (profile.status > 0) v2rayPoint.updateStatus(
                config.observerTag,
                OutboundStatus.newBuilder()
                    .setOutboundTag(observatoryTag)
                    .setAlive(profile.status == 1)
                    .setDelay(profile.ping.toLong())
                    .setLastErrorReason(profile.error ?: "")
                    .setLastTryTime(time)
                    .setLastSeenTime(time)
                    .build()
                    .toByteArray()
            )
        }
    }

    val updateTimer = lazy { Timer("Observatory Timer") }
    val updateTasks by lazy { ConcurrentHashMap<Long, TimerTask>() }

    @Throws(Exception::class)
    override fun onUpdateObservatoryStatus(statusPb: ByteArray?) {
        if (statusPb == null || statusPb.isEmpty()) {
            return
        }
        val status = OutboundStatus.parseFrom(statusPb)
        val profileId = if (status.outboundTag.contains("global-")) {
            status.outboundTag.substringAfter("global-")
        } else {
            status.outboundTag.substringAfter("chain-")
        }
        val id = profileId.toLongOrNull()
        if (id != null) {
            val profile = when {
                id == profile.id -> profile
                statsOutbounds.containsKey(id) -> statsOutbounds[id]!!.proxyEntity
                else -> {
                    SagerDatabase.proxyDao.getById(id)
                }
            }

            if (profile != null) {
                val newStatus = if (status.alive) 1 else 3
                val newDelay = status.delay.toInt()
                val newErrorReason = status.lastErrorReason

                if (profile.status != newStatus || profile.ping != newDelay || profile.error != newErrorReason) {
                    profile.status = newStatus
                    profile.ping = newDelay
                    profile.error = newErrorReason

                    SagerDatabase.proxyDao.updateProxy(profile)

                    Logs.d("Send result for #$profileId ${profile.displayName()}")

                    val groupId = profile.groupId
                    val task = timerTask {
                        if (updateTasks[groupId] == this) {
                            runOnDefaultDispatcher {
                                service.data.binder.broadcast {
                                    it.observatoryResultsUpdated(groupId)
                                }
                            }
                            updateTasks.remove(groupId)
                        }
                    }
                    updateTimer.value.schedule(task, 2000L)
                    updateTasks.put(groupId, task)?.cancel()
                }
            } else {
                Logs.d("Profile with id #$profileId not found")
            }
        } else {
            Logs.d("Persist skipped on outbound ${status.outboundTag}")
        }
    }

    override fun close() {
        SagerNet.started = false

        persistStats()
        super.close()

        if (updateTimer.isInitialized()) updateTimer.value.cancel()
        if (::observatoryJob.isInitialized) observatoryJob.cancel()
    }

// ------------- stats -------------

    private fun queryStats(tag: String, direct: String): Long {
        return v2rayPoint.queryStats(tag, direct)
    }

    private val currentTags by lazy {
        mapOf(* config.outboundTagsCurrent.map {
            it to config.outboundTagsAll[it]
        }.toTypedArray())
    }

    private val statsTags by lazy {
        mapOf(*  config.outboundTags.toMutableList().apply {
            removeAll(config.outboundTagsCurrent)
        }.map {
            it to config.outboundTagsAll[it]
        }.toTypedArray())
    }

    private val interTags by lazy {
        config.outboundTagsAll.filterKeys { !config.outboundTags.contains(it) }
    }

    class OutboundStats(
        val proxyEntity: ProxyEntity, var uplinkTotal: Long = 0L, var downlinkTotal: Long = 0L
    )

    private val statsOutbounds = hashMapOf<Long, OutboundStats>()
    private fun registerStats(
        proxyEntity: ProxyEntity, uplink: Long? = null, downlink: Long? = null
    ) {
        if (proxyEntity.id == outboundStats.proxyEntity.id) return
        val stats = statsOutbounds.getOrPut(proxyEntity.id) {
            OutboundStats(proxyEntity)
        }
        if (uplink != null) {
            stats.uplinkTotal += uplink
        }
        if (downlink != null) {
            stats.downlinkTotal += downlink
        }
    }

    var uplinkProxy = 0L
    var downlinkProxy = 0L
    var uplinkTotalDirect = 0L
    var downlinkTotalDirect = 0L

    private val outboundStats = OutboundStats(profile)
    fun outboundStats(): Pair<OutboundStats, HashMap<Long, OutboundStats>> {
        if (!isInitialized()) return outboundStats to statsOutbounds
        uplinkProxy = 0L
        downlinkProxy = 0L

        val currentUpLink = currentTags.map { (tag, profile) ->
            queryStats(tag, "uplink").apply {
                profile?.also {
                    registerStats(
                        it, uplink = this
                    )
                }
            }
        }
        val currentDownLink = currentTags.map { (tag, profile) ->
            queryStats(tag, "downlink").apply {
                profile?.also {
                    registerStats(it, downlink = this)
                }
            }
        }
        uplinkProxy += currentUpLink.fold(0L) { acc, l -> acc + l }
        downlinkProxy += currentDownLink.fold(0L) { acc, l -> acc + l }

        outboundStats.uplinkTotal += uplinkProxy
        outboundStats.downlinkTotal += downlinkProxy

        if (statsTags.isNotEmpty()) {
            uplinkProxy += statsTags.map { (tag, profile) ->
                queryStats(tag, "uplink").apply {
                    profile?.also {
                        registerStats(it, uplink = this)
                    }
                }
            }.fold(0L) { acc, l -> acc + l }
            downlinkProxy += statsTags.map { (tag, profile) ->
                queryStats(tag, "downlink").apply {
                    profile?.also {
                        registerStats(it, downlink = this)
                    }
                }
            }.fold(0L) { acc, l -> acc + l }
        }

        if (interTags.isNotEmpty()) {
            interTags.map { (tag, profile) ->
                queryStats(tag, "uplink").also { registerStats(profile, uplink = it) }
            }
            interTags.map { (tag, profile) ->
                queryStats(tag, "downlink").also {
                    registerStats(profile, downlink = it)
                }
            }
        }

        return outboundStats to statsOutbounds
    }

    fun bypassStats(direct: String): Long {
        if (!isInitialized()) return 0L
        return queryStats(config.bypassTag, direct)
    }

    fun uplinkDirect() = bypassStats("uplink").also {
        uplinkTotalDirect += it
    }

    fun downlinkDirect() = bypassStats("downlink").also {
        downlinkTotalDirect += it
    }

    // ------------- balancer -------------

    // NextClave: the balancer profile itself has no server address, so the
    // stats bar could not show "flag + IP" for it. To find out which member
    // the core is actually using we take a snapshot of every member's
    // traffic counters, push ONE real test connection through the running
    // tunnel (the core picks a member for it and that member's counters
    // grow), then take a second snapshot. Works right after connect and on
    // an idle link — no user traffic needed. Returns the member id plus the
    // latency of that very test connection (so the ping and the flag/IP
    // always belong to the same node), or 0/-1 when detection fails.
    fun workingMemberId(): Pair<Long, Int> {
        val balancer = profile.balancerBean ?: return profile.id to -1

        // Member ids, resolved the same way ConfigBuilder does it.
        val memberIds = HashSet<Long>()
        try {
            if (balancer.type == BalancerBean.TYPE_LIST) {
                memberIds.addAll(balancer.proxies)
            } else {
                SagerDatabase.proxyDao.getByGroup(balancer.groupId).forEach { entity ->
                    val name = entity.requireBean().name
                    if (balancer.nameFilter.isEmpty() || !Regex(balancer.nameFilter).containsMatchIn(name)) {
                        if (balancer.nameFilter1.isEmpty() || Regex(balancer.nameFilter1).containsMatchIn(name)) {
                            memberIds.add(entity.id)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Logs.w(e)
            return 0L to -1
        }
        if (memberIds.isEmpty()) return 0L to -1

        // tag -> member id; every member gets its own outbound tag
        // ("$TAG_AGENT-global-<id>" normally, "...-chain-<id>" with a
        // front/landing proxy), see buildChain(). Sum duplicates just in
        // case a member is reused in several places.
        val tagsByMember = HashMap<Long, MutableList<String>>()
        for ((tag, entity) in config.outboundTagsAll) {
            if (entity.id in memberIds) {
                tagsByMember.getOrPut(entity.id) { mutableListOf() }.add(tag)
            }
        }
        if (tagsByMember.isEmpty()) return 0L to -1

        fun sample(): Map<Long, Long> {
            val totals = HashMap<Long, Long>()
            for ((id, tags) in tagsByMember) {
                var sum = 0L
                for (tag in tags) {
                    try {
                        sum += v2rayPoint.queryStats(tag, "uplink")
                        sum += v2rayPoint.queryStats(tag, "downlink")
                    } catch (e: Exception) {
                        Logs.w(e)
                    }
                }
                totals[id] = sum
            }
            return totals
        }

        return try {
            val first = sample()
            // Push a real test connection through the running balancer —
            // same call as BaseService.urlTest(); the core routes it
            // through exactly one member (random/leastPing/...).
            val elapsed = try {
                Libexclavecore.urlTest(
                    v2rayPoint, TAG_SOCKS, DataStore.connectionTestURL, 5000
                )
            } catch (e: Exception) {
                Logs.w(e)
                -1
            }
            val second = sample()
            val growing = second.mapValues { (id, total) -> total - (first[id] ?: 0L) }
                .filterValues { it > 0L }
            val id = if (growing.isNotEmpty()) {
                // busiest member of the window wins (random may spread
                // concurrent user connections over several members)
                growing.maxByOrNull { it.value }!!.key
            } else {
                // test produced no deltas (e.g. it failed instantly) —
                // fall back to whoever carried traffic since startup
                second.filterValues { it > 0L }.maxByOrNull { it.value }?.key ?: 0L
            }
            id to elapsed
        } catch (e: Exception) {
            Logs.w(e)
            0L to -1
        }
    }

    fun persistStats() {
        runBlocking {
            try {
                outboundStats()

                if (outboundStats.uplinkTotal + outboundStats.downlinkTotal != 0L) {
                    SagerDatabase.proxyDao.updateTraffic(
                        profile.id,
                        profile.tx + outboundStats.uplinkTotal,
                        profile.rx + outboundStats.downlinkTotal
                    )
                }

                statsOutbounds.values.forEach {
                    if (it.uplinkTotal + it.downlinkTotal != 0L) {
                        SagerDatabase.proxyDao.updateTraffic(
                            it.proxyEntity.id,
                            it.proxyEntity.tx + it.uplinkTotal,
                            it.proxyEntity.rx + it.downlinkTotal
                        )
                    }
                }
            } catch (e: IOException) {
                throw e
            }
        }
    }

}