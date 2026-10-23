/******************************************************************************
 *                                                                            *
 * NextClave: clash-r — use Clash rules / rule providers from the last        *
 * updated Clash subscription. Converted to Xray-style routing rules and      *
 * injected above the app's own route rules for any selected node.            *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 * (at your option) any later version.                                        *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.fmt.clashr

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.v2ray.V2RayConfig
import io.nekohasekai.sagernet.group.YAMLConstructor
import io.nekohasekai.sagernet.ktx.USER_AGENT
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.getLong
import io.nekohasekai.sagernet.ktx.getString
import io.nekohasekai.sagernet.ktx.getStringArray
import io.nekohasekai.sagernet.ktx.parseJson
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.utils.PackageCache
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import libexclavecore.Libexclavecore
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.representer.Representer
import org.yaml.snakeyaml.resolver.Resolver
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object ClashRules {

    private const val TAG = "clash-r"

    // NextClave: rule-set binary .mrs can not be parsed; probe text twins
    // (.yaml/.list/.txt/.json sing-box source) and the canonical MetaCubeX
    // categories for well-known provider names.
    private val NAME_FALLBACK = mapOf(
        "private-domains" to "geosite/private",
        "private-ips" to "geoip/private",
        "category-ads" to "geosite/category-ads-all",
        "category-ru" to "geosite/category-ru",
        "win-spy" to "geosite/win-spy",
        "torrent-domains" to "geosite/tracker",
        "torrent-trackers" to "geosite/tracker",
        "google-play" to "geosite/google-play",
        "youtube" to "geosite/youtube",
        "telegram" to "geosite/telegram",
        "github" to "geosite/github",
        "epicgames" to "geosite/epicgames",
        "origin" to "geosite/origin",
        "riot" to "geosite/riot",
        "escapefromtarkov" to "geosite/escapefromtarkov",
        "steam" to "geosite/steam",
        "faceit" to "geosite/faceit",
        "microsoft" to "geosite/microsoft",
        "apple" to "geosite/apple",
        "pinterest" to "geosite/pinterest",
        "discord_domains" to "geosite/discord",
    )

    private const val MAX_DOMAIN_ENTRIES = 200_000
    private const val MAX_IP_ENTRIES = 50_000
    private const val FETCH_TIMEOUT_MS = 20_000L

    private val dir: File get() = File(app.filesDir, "clashr")
    private val rulesFile: File get() = File(dir, "rules.json")
    private fun providerFile(name: String): File {
        val safe = name.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(dir, "provider_$safe.json")
    }

    @Volatile private var memoRules: Pair<String, JsonObject?>? = null
    private val memoFrags = ConcurrentHashMap<String, Pair<String, JsonObject?>>()
    private val fetchMutex = Mutex()

    // ------------------------------------------------------------------ //
    // Subscription hook                                                   //
    // ------------------------------------------------------------------ //

    fun onSubscriptionContent(content: String) {
        // NextClave: only "rules:" is required — routing-only Clash configs
        // (rules + rule-providers, no top-level proxies:) must register too.
        // parseAndStore bails out safely when there is no usable rule list.
        if (!content.contains("rules:")) return
        runCatching {
            parseAndStore(content)
        }.onFailure {
            Log.w(TAG, "failed to parse clash rules: ${it.message}")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseAndStore(content: String) {
        val loaderOptions = LoaderOptions()
        loaderOptions.codePointLimit = 32 * 1024 * 1024
        val dumperOptions = DumperOptions()
        val yaml = Yaml(YAMLConstructor(loaderOptions), Representer(dumperOptions), dumperOptions,
            object : Resolver() {
                override fun addImplicitResolver(
                    tag: org.yaml.snakeyaml.nodes.Tag,
                    regexp: java.util.regex.Pattern,
                    first: String?,
                    limit: Int
                ) {
                    when (tag) {
                        org.yaml.snakeyaml.nodes.Tag.FLOAT -> {}
                        org.yaml.snakeyaml.nodes.Tag.BOOL -> super.addImplicitResolver(
                            tag, java.util.regex.Pattern.compile("^(?:true|True|TRUE|false|False|FALSE)$"), "tTfF", limit)
                        else -> super.addImplicitResolver(tag, regexp, first, limit)
                    }
                }
            })
        val map = yaml.loadAs(content, Map::class.java) as? Map<Any?, Any?> ?: return
        val rules = map["rules"] as? List<Any?> ?: return
        if (rules.isEmpty()) return
        val providers = map["rule-providers"] as? Map<Any?, Any?> ?: emptyMap()

        val entries = JsonArray()
        var converted = 0
        for (raw in rules) {
            val entry = runCatching { convertRule(raw?.toString() ?: "") }.getOrNull()
            if (entry != null) {
                entries.add(entry)
                converted++
            }
        }
        if (converted == 0) return

        val provDefs = JsonObject()
        val deferredFetch = mutableListOf<JsonObject>()
        for ((k, v) in providers) {
            val def = v as? Map<Any?, Any?> ?: continue
            val name = k.toString()
            val type = def["type"]?.toString() ?: continue
            if (type != "http" && type != "inline" && type != "file") continue
            val obj = JsonObject().apply {
                addProperty("type", type)
                addProperty("behavior", def["behavior"]?.toString() ?: "classical")
                addProperty("format", def["format"]?.toString() ?: "yaml")
                addProperty("interval", (def["interval"]?.toString()?.toLongOrNull() ?: 86400L))
                if (type == "http") addProperty("url", def["url"]?.toString() ?: "")
            }
            provDefs.add(name, obj)
            if (type == "inline") {
                val payload = def["payload"] as? List<Any?> ?: emptyList()
                runCatching {
                    storeProvider(name, obj, payload.map { it?.toString() ?: "" }.joinToString("\n"),
                        obj.getString("url") ?: "")
                }.onFailure { Log.w(TAG, "inline provider $name failed: ${it.message}") }
            } else {
                deferredFetch.add(obj)
            }
        }

        val doc = JsonObject().apply {
            addProperty("version", 1)
            addProperty("updated", System.currentTimeMillis() / 1000)
            add("entries", entries)
            add("providers", provDefs)
        }
        dir.mkdirs()
        rulesFile.writeText(doc.toString())
        memoRules = null
        Log.i(TAG, "clash-r: stored $converted rules, ${provDefs.size()} providers from subscription")

        if (deferredFetch.isNotEmpty()) {
            runOnDefaultDispatcher { fetchAll(false) }
        }
    }

    private fun JsonObject.size(): Int = entrySet().size

    private fun JsonArray.hasString(s: String): Boolean =
        any { it is com.google.gson.JsonPrimitive && it.asString == s }

    private fun JsonArray.addIfAbsent(s: String) {
        if (!hasString(s)) add(s)
    }

    private fun JsonArray.hasObject(o: JsonObject): Boolean =
        any { it is JsonObject && it == o }

    // ------------------------------------------------------------------ //
    // Rule conversion                                                     //
    // ------------------------------------------------------------------ //

    // target kinds: PROXY (active node), DIRECT (bypass), REJECT (block)
    private fun convertRule(line0: String): JsonObject? {
        val line = line0.trim()
        if (line.isEmpty() || line.startsWith("#")) return null
        val parts = line.split(",").map { it.trim() }
        var end = parts.size - 1
        if (end < 1) return null
        if (parts[end].equals("no-resolve", true)) end--
        val target = targetOf(parts[end]) ?: return null
        val head = parts[0].uppercase()

        if (head == "AND") {
            val matcher = parseAnd(parts.subList(1, end).joinToString(",")) ?: return null
            return JsonObject().apply {
                addProperty("k", "a")
                addProperty("t", target)
                add("m", matcher)
            }
        }
        if (head == "RULE-SET") {
            if (end < 2) return null
            return JsonObject().apply {
                addProperty("k", "rs")
                addProperty("n", parts[1])
                addProperty("t", target)
            }
        }
        if (head == "MATCH") {
            // final rule: the app's default final outbound is the active node
            Log.d(TAG, "clash-r: MATCH skipped (final outbound is the selected node)")
            return null
        }
        if (head == "OR" || head == "NOT" || head == "SUB-RULE" || head == "PASS") return null
        val matcher = matchOf(head, parts.subList(0, end)) ?: run {
            Log.d(TAG, "clash-r: unsupported rule type $head skipped")
            return null
        }
        return JsonObject().apply {
            addProperty("k", "e")
            addProperty("t", target)
            add("m", matcher)
        }
    }

    private fun targetOf(t: String): String? = when {
        t.equals("PASS", true) -> null
        t.equals("DIRECT", true) -> "DIRECT"
        t.equals("REJECT", true) || t.equals("REJECT-DROP", true) || t.equals("REJECT-INSERT", true) -> "REJECT"
        t.equals("PROXY", true) || t.equals("GLOBAL", true) -> "PROXY"
        else -> "PROXY" // named proxy group -> active node
    }

    private fun matchOf(head: String, args: List<String>): JsonObject? {
        val arg = args.getOrNull(1) ?: return null
        return when (head) {
            "DOMAIN-SUFFIX" -> domains(listOf("domain:$arg"))
            "DOMAIN" -> domains(listOf("full:$arg"))
            "DOMAIN-KEYWORD" -> domains(listOf(arg))
            "DOMAIN-REGEX" -> domains(listOf("regexp:$arg"))
            "GEOSITE" -> domains(listOf("geosite:$arg"))
            "IP-CIDR", "IP-CIDR6", "IP-CIDR6L" -> ips(listOf(arg))
            "GEOIP" -> ips(listOf("geoip:$arg"))
            "DST-PORT" -> JsonObject().apply { addProperty("port", arg.replace(":", "-")) }
            "SRC-PORT" -> JsonObject().apply { addProperty("srcPort", arg.replace(":", "-")) }
            "NETWORK" -> JsonObject().apply { addProperty("network", arg.lowercase()) }
            "PROCESS-NAME" -> {
                // Android packages (dotted, not .exe) map to app uid rules;
                // desktop process names are skipped (Xray process matching
                // does not work for other apps on Android without root).
                if (arg.contains(".") && !arg.endsWith(".exe", true)) {
                    JsonObject().apply { add("packages", JsonArray().apply { add(arg) }) }
                } else null
            }
            else -> null
        }
    }

    private fun domains(l: List<String>): JsonObject = JsonObject().apply {
        add("domains", JsonArray().apply { l.forEach { add(it) } })
    }

    private fun ips(l: List<String>): JsonObject = JsonObject().apply {
        add("ips", JsonArray().apply { l.forEach { add(it) } })
    }

    private fun parseAnd(s0: String): JsonObject? {
        var s = s0.trim()
        if (s.startsWith("AND,", true)) s = s.substring(4).trim()
        if (!s.startsWith("((") || !s.endsWith("))")) return null
        val body = s.substring(2, s.length - 2)
        val segs = body.split("),(")
        if (segs.isEmpty()) return null
        val matcher = JsonObject()
        for (seg in segs) {
            val segParts = seg.split(",").map { it.trim() }
            val head = segParts.getOrNull(0)?.uppercase() ?: return null
            val m = matchOf(head, segParts) ?: return null
            for ((k, v) in m.entrySet()) {
                val acc = matcher.get(k)
                if (acc is JsonArray) {
                    (v as? JsonArray)?.forEach { if (!acc.contains(it)) acc.add(it) }
                } else if (acc is com.google.gson.JsonPrimitive) {
                    // conflicting scalar matchers -> unsupported
                    return null
                } else {
                    matcher.add(k, v)
                }
            }
        }
        return if (matcher.entrySet().isEmpty()) null else matcher
    }

    // ------------------------------------------------------------------ //
    // Provider conversion / fetch                                         //
    // ------------------------------------------------------------------ //

    private fun storeProvider(name: String, def: JsonObject, content: String, resolvedUrl: String) {
        val behavior = def.getString("behavior") ?: "classical"
        val domains = JsonArray()
        val ipsA = JsonArray()
        val ports = JsonArray()
        val srcPorts = JsonArray()
        val networks = JsonArray()
        val packages = JsonArray()
        val ands = JsonArray()
        var skipped = 0

        fun addDomainEntry(entry0: String) {
            val entry = entry0.trim()
            if (entry.isEmpty() || entry.startsWith("#")) return
            if (domains.size() >= MAX_DOMAIN_ENTRIES) return
            val clean = entry.substringBefore(" @").trim()
            if (clean.isEmpty()) return
            val mapped = when {
                clean.startsWith("+.") -> "domain:" + clean.substring(2)
                clean.startsWith("full:") -> clean
                clean.startsWith("keyword:") -> clean.substringAfter("keyword:")
                clean.startsWith("regexp:") -> clean
                clean.startsWith("domain:") -> clean
                clean.startsWith("geosite:") -> clean
                clean.contains(",") -> null // classical line in domain list
                else -> "domain:$clean"
            } ?: return
            domains.addIfAbsent(mapped)
        }

        fun addIpEntry(entry0: String) {
            val entry = entry0.trim()
            if (entry.isEmpty() || entry.startsWith("#")) return
            if (ipsA.size() >= MAX_IP_ENTRIES) return
            val mapped = when {
                entry.startsWith("geoip:") -> entry
                entry.contains("/") -> entry
                else -> null
            } ?: return
            ipsA.addIfAbsent(mapped)
        }

        fun addClassicalLine(line0: String) {
            val line = line0.trim()
            if (line.isEmpty() || line.startsWith("#")) return
            val parts = line.split(",").map { it.trim() }
            val head = parts.getOrNull(0)?.uppercase() ?: return
            val arg = parts.getOrNull(1) ?: run { skipped++; return }
            when (head) {
                "DOMAIN-SUFFIX" -> if (domains.size() < MAX_DOMAIN_ENTRIES) domains.addIfAbsent("domain:$arg")
                "DOMAIN" -> if (domains.size() < MAX_DOMAIN_ENTRIES) domains.addIfAbsent("full:$arg")
                "DOMAIN-KEYWORD" -> if (domains.size() < MAX_DOMAIN_ENTRIES) domains.addIfAbsent(arg)
                "DOMAIN-REGEX" -> if (domains.size() < MAX_DOMAIN_ENTRIES) domains.addIfAbsent("regexp:$arg")
                "GEOSITE" -> if (domains.size() < MAX_DOMAIN_ENTRIES) domains.addIfAbsent("geosite:$arg")
                "IP-CIDR", "IP-CIDR6" -> if (ipsA.size() < MAX_IP_ENTRIES) ipsA.addIfAbsent(arg)
                "GEOIP" -> if (ipsA.size() < MAX_IP_ENTRIES) ipsA.addIfAbsent("geoip:$arg")
                "DST-PORT" -> ports.addIfAbsent(arg)
                "SRC-PORT" -> srcPorts.addIfAbsent(arg)
                "NETWORK" -> networks.addIfAbsent(arg.lowercase())
                "AND" -> parseAnd(parts.joinToString(","))?.let { if (!ands.hasObject(it)) ands.add(it) }
                "PROCESS-NAME" -> {
                    if (arg.contains(".") && !arg.endsWith(".exe", true)) {
                        packages.addIfAbsent(arg)
                    } else skipped++
                }
                else -> skipped++
            }
        }

        val trimmed = content.trimStart()
        if (trimmed.startsWith("{")) {
            // sing-box source ruleset json (MetaCubeX .json twins)
            runCatching {
                val root = parseJson(content).asJsonObject
                val arr = root.get("rules")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?: error("no rules array")
                for (r0 in arr) {
                    val r = r0.asJsonObject
                    r.getStringArray("domain_suffix")?.forEach { addDomainEntry(it.removePrefix(".")) }
                    r.getStringArray("domain")?.forEach { addDomainEntry("full:$it") }
                    r.getStringArray("domain_keyword")?.forEach { addClassicalLine("DOMAIN-KEYWORD,$it") }
                    r.getStringArray("domain_regex")?.forEach { addClassicalLine("DOMAIN-REGEX,$it") }
                    r.getStringArray("ip_cidr")?.forEach { addIpEntry(it) }
                }
            }.onFailure {
                Log.w(TAG, "provider $name: json parse failed: ${it.message}")
                return
            }
        } else {
            val lines: List<String> = if (content.contains("payload:")) {
                val list = runCatching {
                    val loaderOptions = LoaderOptions()
                    loaderOptions.codePointLimit = 32 * 1024 * 1024
                    val dumperOptions = DumperOptions()
                    val y = Yaml(YAMLConstructor(loaderOptions), Representer(dumperOptions), dumperOptions, Resolver())
                    @Suppress("UNCHECKED_CAST")
                    (y.loadAs(content, Map::class.java) as? Map<Any?, Any?>)?.get("payload") as? List<Any?>
                }.getOrNull()
                if (list == null) {
                    Log.w(TAG, "provider $name: payload parse failed")
                    return
                }
                list.map { it?.toString() ?: "" }
            } else {
                content.lines()
            }
            for (line in lines) {
                when (behavior) {
                    "domain" -> addDomainEntry(line)
                    "ipcidr" -> addIpEntry(line)
                    else -> {
                        // classical: typed lines; plain domains / cidrs too
                        val head = line.trim().substringBefore(",").uppercase()
                        if (line.contains(",") && head in CLASSICAL_HEADS) addClassicalLine(line)
                        else if (line.trim().contains("/")) addIpEntry(line)
                        else addDomainEntry(line)
                    }
                }
            }
        }

        val frag = JsonObject().apply {
            addProperty("ts", System.currentTimeMillis() / 1000)
            addProperty("url", resolvedUrl)
            add("domains", domains)
            add("ips", ipsA)
            add("ports", ports)
            add("srcPorts", srcPorts)
            add("networks", networks)
            add("packages", packages)
            add("ands", ands)
        }
        val hasAny = domains.size() > 0 || ipsA.size() > 0 || ports.size() > 0 ||
            srcPorts.size() > 0 || networks.size() > 0 || packages.size() > 0 || ands.size() > 0
        if (hasAny) {
            dir.mkdirs()
            providerFile(name).writeText(frag.toString())
            memoFrags.remove(name)
            Log.i(TAG, "clash-r: provider $name <- ${resolvedUrl.substringAfterLast("/")} " +
                "(domains ${domains.size()}, ips ${ipsA.size()}, skipped $skipped)")
        } else {
            Log.w(TAG, "clash-r: provider $name produced no usable entries")
        }
    }

    private val CLASSICAL_HEADS = setOf(
        "DOMAIN", "DOMAIN-SUFFIX", "DOMAIN-KEYWORD", "DOMAIN-REGEX", "GEOSITE",
        "IP-CIDR", "IP-CIDR6", "GEOIP", "DST-PORT", "SRC-PORT", "NETWORK",
        "AND", "OR", "NOT", "PROCESS-NAME", "PROCESS-NAME-REGEX", "IP-ASN",
        "SRC-IP-CIDR", "IN-TYPE", "IN-NAME", "UID", "RULE-SET", "SUB-RULE",
    )

    private fun candidates(name: String, def: JsonObject): List<String> {
        val url = def.getString("url") ?: ""
        val out = mutableListOf<String>()
        val variants = mutableListOf<String>()
        if (url.isNotEmpty() && !url.endsWith(".mrs", true)) {
            variants.add(url)
        } else if (url.endsWith(".mrs", true)) {
            for (ext in listOf(".yaml", ".list", ".txt", ".json")) {
                variants.add(url.removeSuffix(".mrs") + ext)
            }
        }
        NAME_FALLBACK[name]?.let { ref ->
            variants.add("https://github.com/MetaCubeX/meta-rules-dat/raw/meta/geo/$ref.yaml")
        }
        for (v in variants) {
            out.add(v)
            mirror(v)?.let { out.add(it) }
        }
        return out.distinct()
    }

    private fun mirror(u: String): String? {
        Regex("https://github\\.com/([^/]+)/([^/]+)/raw/([^/]+)/(.+)$").find(u)?.let {
            val (user, repo, branch, path) = it.destructured
            return "https://cdn.jsdelivr.net/gh/$user/$repo@$branch/$path"
        }
        Regex("https://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/([^/]+)/(.+)$").find(u)?.let {
            val (user, repo, branch, path) = it.destructured
            return "https://cdn.jsdelivr.net/gh/$user/$repo@$branch/$path"
        }
        return null
    }

    private suspend fun fetchProvider(name: String, def: JsonObject): Boolean {
        for (url in candidates(name, def)) {
            runCatching {
                val response = withTimeout(FETCH_TIMEOUT_MS) {
                    val client = Libexclavecore.newHttpClient().apply {
                        keepAlive()
                        if (SagerNet.started && DataStore.startedProfile > 0) {
                            useUDS(SagerNet.deviceStorage.noBackupFilesDir.toString() + "/ipc.sock")
                        }
                    }
                    try {
                        client.newRequest().apply {
                            setURL(url)
                            setUserAgent(USER_AGENT)
                        }.execute()
                    } finally {
                        client.close()
                    }
                }
                val content = response.contentString
                if (content.length < 10 || content.contains("<html", true) ||
                    content.contains("404: Not Found", true)
                ) error("not a ruleset")
                storeProvider(name, def, content, url)
                return true
            }
        }
        Log.w(TAG, "clash-r: provider $name: all candidates failed, rule-set skipped")
        return false
    }

    suspend fun fetchAll(force: Boolean) {
        fetchMutex.withLock {
            val doc = loadRulesDoc() ?: return
            val providers = doc.getAsJsonObject("providers") ?: return
            for ((name, def0) in providers.entrySet()) {
                val def = def0 as? JsonObject ?: continue
                if (def.getString("type") != "http") continue
                val file = providerFile(name)
                val interval = def.getLong("interval") ?: 86400L
                if (!force && file.exists()) {
                    val frag = loadFrags(name)
                    if (frag != null) {
                        val ts = frag.getLong("ts") ?: 0L
                        if (System.currentTimeMillis() / 1000 - ts < interval) continue
                    }
                }
                runCatching { fetchProvider(name, def) }
            }
        }
    }

    /** Fire-and-forget refresh of missing/stale providers (called from config build). */
    fun ensureFresh() {
        runOnDefaultDispatcher {
            runCatching { fetchAll(false) }
        }
    }

    // ------------------------------------------------------------------ //
    // Config build                                                        //
    // ------------------------------------------------------------------ //

    private fun loadRulesDoc(): JsonObject? {
        if (!rulesFile.exists()) return null
        val key = "${rulesFile.lastModified()}:${rulesFile.length()}"
        memoRules?.let { if (it.first == key) return it.second }
        val doc = runCatching { parseJson(rulesFile.readText()).asJsonObject }.getOrNull()
        memoRules = key to doc
        return doc
    }

    private fun loadFrags(name: String): JsonObject? {
        val file = providerFile(name)
        if (!file.exists()) return null
        val key = "${file.lastModified()}:${file.length()}"
        memoFrags[name]?.let { if (it.first == key) return it.second }
        val doc = runCatching { parseJson(file.readText()).asJsonObject }.getOrNull()
        memoFrags[name] = key to doc
        return doc
    }

    private fun targetTag(t: String, tagProxy: String): String = when (t) {
        "DIRECT" -> io.nekohasekai.sagernet.fmt.TAG_BYPASS
        "REJECT" -> io.nekohasekai.sagernet.fmt.TAG_BLOCK
        else -> tagProxy // PROXY and named groups
    }

    private fun matcherRule(target: String, tagProxy: String, m: JsonObject): V2RayConfig.RoutingObject.RuleObject? {
        val doms = m.getStringArray("domains")
        val ipsL = m.getStringArray("ips")
        val pkgs = m.getStringArray("packages")
        val portV = m.getString("port")
        val srcPortV = m.getString("srcPort")
        val networkV = m.getString("network")
        if (doms.isNullOrEmpty() && ipsL.isNullOrEmpty() && pkgs.isNullOrEmpty() &&
            portV.isNullOrEmpty() && srcPortV.isNullOrEmpty() && networkV.isNullOrEmpty()
        ) return null
        return V2RayConfig.RoutingObject.RuleObject().apply {
            type = "field"
            outboundTag = targetTag(target, tagProxy)
            if (!doms.isNullOrEmpty()) domains = doms.toMutableList()
            if (!ipsL.isNullOrEmpty()) ip = ipsL.toMutableList()
            if (!portV.isNullOrEmpty()) port = portV
            if (!srcPortV.isNullOrEmpty()) sourcePort = srcPortV
            if (!networkV.isNullOrEmpty()) network = networkV
            if (!pkgs.isNullOrEmpty()) {
                PackageCache.awaitLoadSync()
                val uids = pkgs.mapNotNull { PackageCache[it] }
                if (uids.isNotEmpty()) uid = uids
            }
        }
    }

    /**
     * NextClave: seed the bundled ruleset from assets on first use, so the
     * clash-r toggle works out of the box — no subscription update needed.
     * Runs only when rules.json does not exist yet: once any Clash
     * subscription is updated, its rules replace the bundled set (delete
     * app data to return to the bundled default).
     */
    private fun ensureSeeded() {
        if (rulesFile.exists()) return
        runCatching {
            val root = app.assets.open("clashr_seed.json").bufferedReader().use {
                parseJson(it.readText()).asJsonObject
            }
            val rulesDoc = root.getAsJsonObject("rules") ?: return
            dir.mkdirs()
            rulesFile.writeText(rulesDoc.toString())
            memoRules = null
            val providers = root.getAsJsonObject("providers")
            providers?.entrySet()?.forEach { (name, el) ->
                (el as? JsonObject)?.let { frag ->
                    providerFile(name).writeText(frag.toString())
                    memoFrags.remove(name)
                }
            }
            Log.i(TAG, "clash-r: seeded bundled rules " +
                "(${rulesDoc.getAsJsonArray("entries")?.size()} entries, ${providers?.size() ?: 0} providers)")
        }.onFailure {
            Log.w(TAG, "clash-r: seed failed: ${it.message}")
        }
    }

    fun buildRoutingRules(tagProxy: String): List<V2RayConfig.RoutingObject.RuleObject> {
        if (!DataStore.clashRules) return emptyList()
        if (!rulesFile.exists()) ensureSeeded()
        val doc = loadRulesDoc() ?: return emptyList()
        val entries = doc.getAsJsonArray("entries") ?: return emptyList()
        // refresh stale/missing providers in background; missing ones are
        // skipped for this connection and appear after the next reconnect
        ensureFresh()

        val out = mutableListOf<V2RayConfig.RoutingObject.RuleObject>()
        val bDomains = mutableListOf<String>()
        val bIps = mutableListOf<String>()
        val bPkgs = mutableListOf<String>()
        var bTarget: String? = null

        fun flush() {
            val t = bTarget ?: return
            val tag = targetTag(t, tagProxy)
            if (bDomains.isNotEmpty()) {
                out.add(V2RayConfig.RoutingObject.RuleObject().apply {
                    type = "field"
                    outboundTag = tag
                    domains = bDomains.toList()
                })
            }
            if (bIps.isNotEmpty()) {
                out.add(V2RayConfig.RoutingObject.RuleObject().apply {
                    type = "field"
                    outboundTag = tag
                    ip = bIps.toList()
                })
            }
            if (bPkgs.isNotEmpty()) {
                PackageCache.awaitLoadSync()
                val uids = bPkgs.mapNotNull { PackageCache[it] }
                if (uids.isNotEmpty()) {
                    out.add(V2RayConfig.RoutingObject.RuleObject().apply {
                        type = "field"
                        outboundTag = tag
                        uid = uids
                    })
                }
            }
            bDomains.clear()
            bIps.clear()
            bPkgs.clear()
            bTarget = null
        }

        fun bucket(t: String, domains: List<String>?, ips: List<String>?, packages: List<String>?) {
            if (domains.isNullOrEmpty() && ips.isNullOrEmpty() && packages.isNullOrEmpty()) return
            if (bTarget != t) flush()
            bTarget = t
            domains?.let { bDomains.addAll(it) }
            ips?.let { bIps.addAll(it) }
            packages?.let { bPkgs.addAll(it) }
        }

        for (e0 in entries) {
            val e = e0.asJsonObject
            val kind = e.getString("k") ?: continue
            val target = e.getString("t") ?: continue
            when (kind) {
                "rs" -> {
                    val name = e.getString("n") ?: continue
                    val frags = loadFrags(name)
                    if (frags == null) {
                        Log.d(TAG, "clash-r: provider $name unavailable, skipped")
                        continue
                    }
                    bucket(target, frags.getStringArray("domains"), frags.getStringArray("ips"),
                        frags.getStringArray("packages"))
                    for (p in frags.getStringArray("ports") ?: emptyList()) {
                        flush()
                        out.add(V2RayConfig.RoutingObject.RuleObject().apply {
                            type = "field"
                            outboundTag = targetTag(target, tagProxy)
                            port = p.replace(":", "-")
                        })
                    }
                    for (p in frags.getStringArray("srcPorts") ?: emptyList()) {
                        flush()
                        out.add(V2RayConfig.RoutingObject.RuleObject().apply {
                            type = "field"
                            outboundTag = targetTag(target, tagProxy)
                            sourcePort = p.replace(":", "-")
                        })
                    }
                    for (n in frags.getStringArray("networks") ?: emptyList()) {
                        flush()
                        out.add(V2RayConfig.RoutingObject.RuleObject().apply {
                            type = "field"
                            outboundTag = targetTag(target, tagProxy)
                            network = n
                        })
                    }
                    for (a0 in frags.getAsJsonArray("ands") ?: JsonArray()) {
                        flush()
                        (a0 as? JsonObject)?.let { a ->
                            matcherRule(target, tagProxy, a)?.let { out.add(it) }
                        }
                    }
                }
                "e" -> {
                    val m = e.getAsJsonObject("m") ?: continue
                    bucket(target, m.getStringArray("domains"), m.getStringArray("ips"),
                        m.getStringArray("packages"))
                    m.getString("port")?.let {
                        flush()
                        out.add(V2RayConfig.RoutingObject.RuleObject().apply {
                            type = "field"
                            outboundTag = targetTag(target, tagProxy)
                            port = it.replace(":", "-")
                        })
                    }
                    m.getString("srcPort")?.let {
                        flush()
                        out.add(V2RayConfig.RoutingObject.RuleObject().apply {
                            type = "field"
                            outboundTag = targetTag(target, tagProxy)
                            sourcePort = it.replace(":", "-")
                        })
                    }
                    m.getString("network")?.let {
                        flush()
                        out.add(V2RayConfig.RoutingObject.RuleObject().apply {
                            type = "field"
                            outboundTag = targetTag(target, tagProxy)
                            network = it
                        })
                    }
                }
                "a" -> {
                    flush()
                    val m = e.getAsJsonObject("m") ?: continue
                    matcherRule(target, tagProxy, m)?.let { out.add(it) }
                }
            }
        }
        flush()
        Log.d(TAG, "clash-r: ${out.size} routing rules injected")
        return out
    }
}
