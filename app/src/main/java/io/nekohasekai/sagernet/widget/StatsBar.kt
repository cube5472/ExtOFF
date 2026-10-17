/******************************************************************************
 *                                                                            *
 * Copyright (C) 2021 by nekohasekai <contact-sagernet@sekai.icu>             *
 * Copyright (C) 2021 by Max Lv <max.c.lv@gmail.com>                          *
 * Copyright (C) 2021 by Mygod Studio <contact-shadowsocks-android@mygod.be>  *
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

package io.nekohasekai.sagernet.widget

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.util.Patterns
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnPreDraw
import com.google.android.material.bottomappbar.BottomAppBar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.internal.BalancerBean
import io.nekohasekai.sagernet.ktx.*
import libexclavecore.Libexclavecore
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.utils.FormatFileSizeCompat
import io.nekohasekai.sagernet.utils.Theme
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress

class StatsBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.bottomAppBarStyle,
) : BottomAppBar(context, attrs, defStyleAttr) {
    private var testJob: Job? = null
    private lateinit var statusText: TextView
    private lateinit var txText: TextView
    private lateinit var rxText: TextView
    private lateinit var behavior: YourBehavior

    companion object {
        // NextClave: last detected "flag + server address" line for the
        // currently selected profile, so it stays displayed after reconnects.
        private var cachedProfileId = 0L
        private var cachedAddressText: String? = null
        private var cachedCountryCode: String? = null
    }

    override fun getBehavior(): YourBehavior {
        if (!this::behavior.isInitialized) behavior = YourBehavior()
        return behavior
    }

    private val setNavigationBarColor = DataStore.experimentalFlagsProperties.getBooleanProperty("setNavigationBarColor")

    override fun performShow() {
        super.performShow()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !setNavigationBarColor
            && isNavigationBarAtBottom(this)) {
            val activity = context.findActivity<MainActivity>()!!
            val insetController = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
            insetController.isAppearanceLightNavigationBars =
                if (DataStore.appTheme == Theme.BLACK) !Theme.usingNightMode() else false
        }
    }

    override fun performHide() {
        super.performHide()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !setNavigationBarColor
            && isNavigationBarAtBottom(this)) {
            val activity = context.findActivity<MainActivity>()!!
            val insetController = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
            insetController.isAppearanceLightNavigationBars = !Theme.usingNightMode()
        }
    }

    private fun isNavigationBarAtBottom(view: View): Boolean {
        val insets = ViewCompat.getRootWindowInsets(view)!!
        val navigationBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
        return navigationBar.bottom > 0 && navigationBar.left == 0 && navigationBar.right == 0
    }

    class YourBehavior : Behavior() {
        private fun isNavigationBarAtBottom(child: View): Boolean {
            val insets = ViewCompat.getRootWindowInsets(child)!!
            val navigationBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            return navigationBar.bottom > 0 && navigationBar.left == 0 && navigationBar.right == 0
        }

        private val setNavigationBarColor = DataStore.experimentalFlagsProperties.getBooleanProperty("setNavigationBarColor")

        override fun onNestedScroll(
            coordinatorLayout: CoordinatorLayout, child: BottomAppBar, target: View,
            dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int,
            type: Int, consumed: IntArray,
        ) {
            super.onNestedScroll(
                coordinatorLayout,
                child,
                target,
                dxConsumed,
                dyConsumed + dyUnconsumed,
                dxUnconsumed,
                0,
                type,
                consumed
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !setNavigationBarColor
                && isNavigationBarAtBottom(child)) {
                val activity = child.context.findActivity<MainActivity>()!!
                val insetController = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                when {
                    dyConsumed + dyUnconsumed > 0 -> {
                        insetController.isAppearanceLightNavigationBars = !Theme.usingNightMode()
                    }
                    dyConsumed + dyUnconsumed < 0 -> {
                        insetController.isAppearanceLightNavigationBars =
                            if (DataStore.appTheme == Theme.BLACK) !Theme.usingNightMode() else false
                    }
                }
            }
        }
    }


    override fun setOnClickListener(l: OnClickListener?) {
        statusText = findViewById(R.id.status)
        txText = findViewById(R.id.tx)
        rxText = findViewById(R.id.rx)
        super.setOnClickListener(l)
    }

    private fun setStatus(text: CharSequence) {
        statusText.text = text
        TooltipCompat.setTooltipText(this, text)
    }

    fun changeState(state: BaseService.State) {
        if ((state == BaseService.State.Connected).also { hideOnScroll = it }) {
            doOnPreDraw {
                performShow()
                // NextClave: keep the detected node address visible across
                // reconnects instead of resetting to a generic "Connected".
                val cached = cachedAddressText?.takeIf { cachedProfileId == DataStore.selectedProxy }
                setStatus(cached ?: context.getText(R.string.vpn_connected))
            }
            isEnabled = true
        } else {
            doOnPreDraw {
                performHide()
            }
            testJob?.cancel()
            updateTraffic(0, 0)
            setStatus(
                context.getText(
                    when (state) {
                        BaseService.State.Connecting -> R.string.connecting
                        BaseService.State.Stopping -> R.string.stopping
                        else -> R.string.not_connected
                    }
                )
            )
        }
    }

    @SuppressLint("SetTextI18n")
    fun updateTraffic(txRate: Long, rxRate: Long) {
        txText.text = "▲  ${
            context.getString(
                R.string.speed, FormatFileSizeCompat.formatFileSize(context, txRate, DataStore.useIECUnit)
            )
        }"
        rxText.text = "▼  ${
            context.getString(
                R.string.speed, FormatFileSizeCompat.formatFileSize(context, rxRate, DataStore.useIECUnit)
            )
        }"
    }

    fun testConnection() {
        testJob?.cancel()
        val activity = context.findActivity<MainActivity>()!!
        isEnabled = false
        setStatus(context.getText(R.string.connection_test_testing))
        testJob = runOnDefaultDispatcher {
            try {
                val elapsed = activity.urlTest()
                onMainDispatcher {
                    isEnabled = true
                    setStatus(
                        context.getString(
                            if (DataStore.connectionTestURL.startsWith("https://", ignoreCase = true)) {
                                R.string.connection_test_available
                            } else {
                                R.string.connection_test_available_http
                            }, elapsed
                        )
                    )
                }

            } catch (e: Exception) {
                Logs.w(e)
                onMainDispatcher {
                    isEnabled = true
                    var msg = e.localizedMessage ?: e.readableMessage
                    when {
                        msg.contains("timeout") || msg.contains("deadline") -> {
                            msg = context.getString(R.string.connection_test_timeout)
                        }
                        msg.contains("refused") || msg.contains("closed pipe") -> {
                            msg = context.getString(R.string.connection_test_refused)
                        }
                    }
                    setStatus(context.getString(R.string.connection_test_error, msg))
                }
            }
        }
    }

    // NextClave: tap on the stats bar (and the auto-show on connect) resolve
    // the node's server address to an IP, look up its country for the flag
    // emoji and measure the latency. The result stays in the status line
    // (and is re-shown by changeState) until the profile changes.
    @SuppressLint("SetTextI18n")
    fun showNodeAddress() {
        testJob?.cancel()
        val activity = context.findActivity<MainActivity>()!!
        isEnabled = false
        setStatus(context.getText(R.string.connection_test_testing))
        val profileId = DataStore.selectedProxy
        testJob = runOnDefaultDispatcher {
            // NextClave: for a balancer the service identifies the member
            // that actually carries the test connection (its traffic
            // counters grow) and returns the latency of that very
            // connection — one call, one test, so the ping and the
            // flag/IP always belong to the same node.
            var fixedPing: Int? = null
            var bean: AbstractBean? = try {
                ProfileManager.getProfile(profileId)?.requireBean()
            } catch (e: Exception) {
                Logs.w(e)
                null
            }
            if (bean is BalancerBean) {
                val (memberId, elapsed) = withTimeoutOrNull(15_000L) {
                    activity.workingNodeInfo()
                } ?: (0L to -1)
                bean = if (memberId > 0L) {
                    ProfileManager.getProfile(memberId)?.requireBean()
                } else null
                fixedPing = elapsed.takeIf { it >= 0 }
            }

            val pingDeferred = if (fixedPing != null) null else async {
                try {
                    activity.urlTest()
                } catch (e: Exception) {
                    Logs.w(e)
                    null
                }
            }
            val addressDeferred = async {
                try {
                    val b = bean ?: return@async null
                    val host = b.serverAddress?.trim().orEmpty()
                    if (host.isEmpty() || host == "127.0.0.1" || host == "localhost" || host == "::1") {
                        return@async null
                    }
                    val ip = if (Patterns.IP_ADDRESS.matcher(host).matches()) host
                    else withTimeoutOrNull(10_000L) {
                        InetAddress.getAllByName(host).firstOrNull()?.hostAddress
                    } ?: return@async null
                    // NextClave: fresh lookup with several providers; if all
                    // of them fail, fall back to the country cached for this
                    // profile so the flag does not blink away.
                    val fresh = withTimeoutOrNull(20_000L) { lookupCountryCode(ip) }
                    if (fresh != null) {
                        cachedCountryCode = fresh
                        cachedProfileId = profileId
                    }
                    val country = fresh ?: cachedCountryCode?.takeIf { cachedProfileId == profileId }
                    ip to country
                } catch (e: Exception) {
                    Logs.w(e)
                    null
                }
            }
            val ping = fixedPing ?: pingDeferred?.await()
            val address = addressDeferred.await()
            val finalText = buildString {
                if (address != null) {
                    append(address.second?.let { flagEmoji(it) + " " }.orEmpty() + address.first)
                }
                if (ping != null) {
                    if (isNotEmpty()) append(" · ")
                    append(ping.toString() + " ms")
                }
            }
            onMainDispatcher {
                isEnabled = true
                if (finalText.isEmpty()) {
                    setStatus(context.getText(R.string.vpn_connected))
                } else {
                    setStatus(finalText)
                    cachedProfileId = profileId
                    cachedAddressText = finalText
                }
            }
        }
    }
    // NextClave: several free geo providers, each on its own HTTP client.
    // ip2location.io is the primary source: first through the tunnel (the
    // app's own direct network may be unusable), then the same API directly.
    // geojs.io and two more fallbacks follow. A single silent failure used
    // to lose the flag entirely.
    private suspend fun lookupCountryCode(ip: String): String? {
        if (SagerNet.started && DataStore.startedProfile > 0) {
            withTimeoutOrNull(6_000L) { ip2LocationCountry(ip, viaTunnel = true) }?.let { return it }
        }
        withTimeoutOrNull(6_000L) { ip2LocationCountry(ip, viaTunnel = false) }?.let { return it }
        if (SagerNet.started && DataStore.startedProfile > 0) {
            withTimeoutOrNull(6_000L) { geojsCountry(ip, viaTunnel = true) }?.let { return it }
        }
        withTimeoutOrNull(6_000L) { geojsCountry(ip, viaTunnel = false) }?.let { return it }
        withTimeoutOrNull(6_000L) { ipApiCountry(ip) }?.let { return it }
        withTimeoutOrNull(6_000L) { ipWhoIsCountry(ip) }?.let { return it }
        return null
    }

    // ip2location.io answer: {"ip":"1.2.3.4","country_code":"US",...}.
    // The free no-key endpoint is limited (e.g. 1000 queries/day); on
    // overload it replies with an error object that has no "country_code",
    // so the parse below just returns null and the next provider is tried.
    private suspend fun ip2LocationCountry(ip: String, viaTunnel: Boolean): String? = try {
        val client = Libexclavecore.newHttpClient().apply {
            if (viaTunnel) {
                useUDS(SagerNet.deviceStorage.noBackupFilesDir.toString() + "/ipc.sock")
            }
        }
        val body = client.newRequest().apply {
            setURL("https://api.ip2location.io/?ip=$ip")
        }.execute().contentString
        parseJson(body).asJsonObject.get("country_code")?.asString
            ?.takeIf { it.length == 2 }?.uppercase()
    } catch (e: Exception) {
        Logs.w(e)
        null
    }

    private suspend fun geojsCountry(ip: String, viaTunnel: Boolean): String? = try {
        val client = Libexclavecore.newHttpClient().apply {
            if (viaTunnel) {
                useUDS(SagerNet.deviceStorage.noBackupFilesDir.toString() + "/ipc.sock")
            }
        }
        val body = client.newRequest().apply {
            setURL("https://get.geojs.io/v1/ip/country/$ip.json")
        }.execute().contentString
        // geojs.io answer: {"country":"US","country_3":"USA","ip":...,"name":...}
        // the alpha-2 code lives in the "country" field (NOT "country_code") —
        // that mismatch is why the flag never showed.
        parseJson(body).asJsonObject.get("country")?.asString
            ?.takeIf { it.length == 2 }?.uppercase()
    } catch (e: Exception) {
        Logs.w(e)
        null
    }

    private suspend fun ipApiCountry(ip: String): String? = try {
        val client = Libexclavecore.newHttpClient()
        val body = client.newRequest().apply {
            setURL("http://ip-api.com/json/$ip?fields=countryCode")
        }.execute().contentString
        parseJson(body).asJsonObject.get("countryCode")?.asString
            ?.takeIf { it.length == 2 }?.uppercase()
    } catch (e: Exception) {
        Logs.w(e)
        null
    }

    private suspend fun ipWhoIsCountry(ip: String): String? = try {
        val client = Libexclavecore.newHttpClient()
        val body = client.newRequest().apply {
            setURL("https://ipwho.is/$ip")
        }.execute().contentString
        parseJson(body).asJsonObject.get("country_code")?.asString
            ?.takeIf { it.length == 2 }?.uppercase()
    } catch (e: Exception) {
        Logs.w(e)
        null
    }

    private fun flagEmoji(countryCode: String): String {
        if (countryCode.length != 2) return ""
        val codePoints = countryCode.uppercase()
            .map { 0x1F1E6 + (it - 'A') }
            .filter { it in 0x1F1E6..0x1F1FF }
            .toIntArray()
        if (codePoints.size != 2) return ""
        return String(codePoints, 0, 2)
    }

}
