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

package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.RemoteException
import android.provider.Settings
import android.text.util.Linkify
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.IdRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.setPadding
import androidx.drawerlayout.widget.DrawerLayout
import androidx.preference.PreferenceDataStore
import com.google.android.material.bottomappbar.BottomAppBar.FAB_ALIGNMENT_MODE_CENTER
import com.google.android.material.bottomappbar.BottomAppBar.FAB_ALIGNMENT_MODE_END
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.aidl.AppStats
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.TrafficStats
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.bg.test.V2RayTestInstance
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.databinding.LayoutMainBinding
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.Alerts
import io.nekohasekai.sagernet.fmt.PluginEntry
import io.nekohasekai.sagernet.group.GroupInterfaceAdapter
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.plugin.PluginManager
import io.nekohasekai.sagernet.utils.PackageCache
import kotlin.math.min
import kotlinx.coroutines.delay
import io.noties.markwon.Markwon
import libexclavecore.Libexclavecore

class MainActivity : ThemedActivity(),
    SagerConnection.Callback,
    OnPreferenceDataStoreChangeListener {

    lateinit var binding: LayoutMainBinding

    val userInterface by lazy { GroupInterfaceAdapter(this) }

    // NextClave: one entry per subscription group row of the rebuilt drawer
    private class DrawerGroupRow(
        val title: String,
        val count: Int,
        val groupId: Long,
        val row: View,
    )

    private val drawerGroups = ArrayList<DrawerGroupRow>()
    private val moreRows = HashMap<Int, View>()
    private var drawerAccent = 0
    private var moreSelection = 0
    private var drawerFxStarted = false

    override val onBackPressedCallback = object : OnBackPressedCallback(enabled = false) {
        override fun handleOnBackPressed() {
            displayFragmentWithId(R.id.nav_configuration)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = LayoutMainBinding.inflate(layoutInflater)
        when (DataStore.fabStyle) {
            FabStyle.SagerNet -> {
                binding.stats.fabAlignmentMode = FAB_ALIGNMENT_MODE_END
                binding.stats.fabCradleMargin = 0F
                binding.stats.fabCradleRoundedCornerRadius = 0F
                binding.stats.cradleVerticalOffset = dp2px(8).toFloat()
            }
            FabStyle.Shadowsocks -> {
                binding.stats.fabAlignmentMode = FAB_ALIGNMENT_MODE_CENTER
                binding.stats.fabCradleMargin = dp2px(5).toFloat()
                binding.stats.fabCradleRoundedCornerRadius = dp2px(6).toFloat()
                binding.stats.cradleVerticalOffset = 0F
            }
        }

        binding.fab.initProgress(binding.fabProgress)

        // NextClave: rebuilt glass drawer - cap the panel width by the screen
        // like the approved preview (floating panel, never full-width), then
        // build its static parts (badge, accent rim, mode presets, "more")
        val panelLp = binding.drawerPanel.layoutParams as DrawerLayout.LayoutParams
        panelLp.width = min(dp2px(330), resources.displayMetrics.widthPixels - dp2px(96))
        binding.drawerPanel.layoutParams = panelLp
        buildDrawer()

        // NextClave: glass drawer - scale + dim the content behind the drawer,
        // blur it on Android 12+ (RenderEffect), restore everything on close.
        // Numbers mirror the approved preview: content scale(.955) shift(5%),
        // scrim rgba(0,0,0,.34); blur + item cascade fire early in the slide.
        binding.drawerLayout.setScrimColor(Color.argb(0x57, 0, 0, 0))
        binding.drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                val scale = 1f - 0.045f * slideOffset
                binding.coordinator.scaleX = scale
                binding.coordinator.scaleY = scale
                val dir = if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) -1f else 1f
                binding.coordinator.translationX = binding.coordinator.width * 0.05f * slideOffset * dir
                if (slideOffset > 0.08f && !drawerFxStarted) {
                    drawerFxStarted = true
                    applyGlassBlur(true)
                    populateDrawerGroups()
                }
            }

            override fun onDrawerClosed(drawerView: View) {
                drawerFxStarted = false
                applyGlassBlur(false)
                binding.coordinator.scaleX = 1f
                binding.coordinator.scaleY = 1f
                binding.coordinator.translationX = 0f
            }
        })
        populateDrawerGroups()
        // NextClave: float the panel in from the screen edges, clear of the
        // status bar, navigation bar and display cutout
        ViewCompat.setOnApplyWindowInsetsListener(binding.drawerPanel) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
                        or WindowInsetsCompat.Type.displayCutout()
            )
            val lp = v.layoutParams as DrawerLayout.LayoutParams
            val gap = dp2px(12)
            if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                lp.rightMargin = bars.right + gap
            } else {
                lp.leftMargin = bars.left + gap
            }
            lp.topMargin = bars.top + gap
            lp.bottomMargin = bars.bottom + gap
            v.layoutParams = lp
            insets
        }

        if (savedInstanceState == null) {
            displayFragmentWithId(R.id.nav_configuration)
        }

        binding.fab.setOnClickListener {
            if (state.canStop) SagerNet.stopService() else connect.launch(
                null
            )
        }
        // NextClave: tap on the bottom bar detects the node IP + country flag
        // (with latency) and keeps the result displayed.
        binding.stats.setOnClickListener { if (state == BaseService.State.Connected) binding.stats.showNodeAddress() }

        setContentView(binding.root)

        // NextClave: glass menu popups - toolbar menu popups (overflow,
        // add-profile and every nested submenu level) are separate windows
        // with a translucent glass background, so the activity content shows
        // through them; blur that content with the same drawer recipe while
        // a popup holds the window focus and restore it when the focus is
        // back. Menu popups are modal (focusable), so window focus loss is
        // the exact "a popup is up" signal; honoring glassEffects, power
        // saving and pre-S fallbacks stays inside applyGlassBlur.
        window.decorView.viewTreeObserver.addOnWindowFocusChangeListener { hasFocus ->
            if (isFinishing || isDestroyed) return@addOnWindowFocusChangeListener
            if (hasFocus) applyGlassBlur(false) else applyGlassBlur(true)
        }

        changeState(BaseService.State.Idle)
        connection.connect(this, this)
        DataStore.configurationStore.registerChangeListener(this)

        if (intent?.action == Intent.ACTION_VIEW) {
            onNewIntent(intent)
        }

        runOnMainDispatcher {
            fun getLicenseKeyName(only: Boolean): String {
                return if (only) "gplv3OnlyAccepted" else "gplv3OrLaterAccepted"
            }
            val only = Libexclavecore.buildWithClash()
            if (DataStore.configurationStore.getBoolean(getLicenseKeyName(only)) != true) {
                DataStore.configurationStore.putBoolean(getLicenseKeyName(only), true)
                DataStore.configurationStore.remove(getLicenseKeyName(!only))
                AlertDialog.Builder(this@MainActivity).apply {
                    setTitle(R.string.license)
                    setView(
                        TextView(this@MainActivity).apply {
                            setPadding(dp2px(16))
                            text = getString(if (only) {
                                R.string.license_gpl_v3_only
                            } else {
                                R.string.license_gpl_v3_or_later
                            })
                            setTextIsSelectable(true)
                            Linkify.addLinks(this, Linkify.EMAIL_ADDRESSES or Linkify.WEB_URLS)
                        }
                    )
                    setPositiveButton(android.R.string.ok) { _, _ ->
                        requestPermissions()
                    }
                    setOnCancelListener { _ ->
                        requestPermissions()
                    }
                }.show()
            } else {
                requestPermissions()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        val uri = intent.data ?: return

        runOnDefaultDispatcher {
            if (uri.scheme == "exclave" && uri.host == "subscription") {
                uri.getQueryParameter("url")?.let {
                    importSubscription(it)
                }
            } else {
                importProfile(uri)
            }
        }
    }

    private fun requestPermissions() {
        if (!DataStore.getInstalledPackagesInited) {
            // For bullshit Chinese OEMs
            DataStore.getInstalledPackagesInited = true
            runOnDefaultDispatcher {
                PackageCache.register()
            }
        }
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!DataStore.postNotificationsPermissionRequested) {
                DataStore.postNotificationsPermissionRequested = true
                if (app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            if (!DataStore.accessLocalNetworkPermissionRequested) {
                DataStore.accessLocalNetworkPermissionRequested = true
                if (app.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
                    permissions.add(Manifest.permission.ACCESS_LOCAL_NETWORK)
                }
            }
        }
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this@MainActivity, permissions.toTypedArray(), 0)
        }
    }

    fun urlTest(): Int {
        if (state != BaseService.State.Connected || connection.service == null) {
            error("not started")
        }
        return connection.service!!.urlTest()
    }

    // NextClave: id of the proxy the balancer is currently routing traffic
    // through + latency of the test connection used to detect it
    // (0, -1 = unknown / not a balancer session). Used by the stats bar to
    // show the flag + IP of the node that is actually in use.
    fun workingNodeInfo(): Pair<Long, Int> {
        if (state != BaseService.State.Connected || connection.service == null) return 0L to -1
        return try {
            val raw = connection.service!!.workingNodeInfo().split("|")
            ((raw.getOrNull(0)?.toLongOrNull() ?: 0L) to
                    (raw.getOrNull(1)?.toIntOrNull() ?: -1))
        } catch (e: Exception) {
            Logs.w(e)
            0L to -1
        }
    }

    suspend fun importSubscription(uri: String) {
        val group = ProxyGroup(type = GroupType.SUBSCRIPTION).apply {
            subscription = SubscriptionBean().apply {
                link = uri
                name = getString(R.string.subscription)
            }
        }

        onMainDispatcher {

            displayFragmentWithId(R.id.nav_group)

            MaterialAlertDialogBuilder(this@MainActivity).setTitle(R.string.subscription_import)
                .setMessage(getString(R.string.subscription_import_message, uri))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    runOnDefaultDispatcher {
                        finishImportSubscription(group)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()

        }

    }

    private suspend fun finishImportSubscription(subscription: ProxyGroup) {
        GroupManager.createGroup(subscription)
        // GroupUpdater.startUpdate(subscription, true)
    }

    suspend fun importProfile(uri: Uri) {
        val profile = try {
            parseShareLinks(uri.toString()).getOrNull(0) ?: error(getString(R.string.no_proxies_found))
        } catch (e: Exception) {
            onMainDispatcher {
                alert(e.readableMessage).show()
            }
            return
        }

        onMainDispatcher {
            MaterialAlertDialogBuilder(this@MainActivity).setTitle(R.string.profile_import)
                .setMessage(getString(R.string.profile_import_message, profile.displayName()))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    runOnDefaultDispatcher {
                        finishImportProfile(profile)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

    }

    private suspend fun finishImportProfile(profile: AbstractBean) {
        val targetId = DataStore.selectedGroupForImport()

        ProfileManager.createProfile(targetId, profile)

        onMainDispatcher {
            displayFragmentWithId(R.id.nav_configuration)

            snackbar(resources.getQuantityString(R.plurals.added, 1, 1)).show()
        }
    }

    override fun missingPlugin(profileName: String, pluginName: String) {
        val pluginEntity = PluginEntry.find(pluginName)
        if (pluginEntity == null) {
            snackbar(getString(R.string.plugin_unknown, pluginName)).show()
            return
        }

        MaterialAlertDialogBuilder(this).setTitle(R.string.missing_plugin)
            .setMessage(
                getString(
                    R.string.profile_requiring_plugin, profileName, getString(pluginEntity.nameId)
                )
            ).show()
    }

    // NextClave: build the static part of the rebuilt glass drawer - accent
    // color, brand badge, right rim, the clickable "groups" header and the
    // "more" rows. Group rows arrive asynchronously from populateDrawerGroups().
    private fun buildDrawer() {
        drawerAccent = drawerAccentColor()
        binding.drawerHeader.drawerBadge.setTextColor(drawerAccent)
        // NextClave: the "/" badge is the entry point to the "about" screen.
        // The legacy drawer opened it from the version footer, which the
        // glass rebuild dropped, leaving the screen unreachable; the brand
        // badge takes over its role. nav_about has no row in the "more"
        // section, so displayFragmentWithId clears the row highlight by
        // itself (syncMoreSelection(0)) and repeated taps simply re-open
        // the screen.
        binding.drawerHeader.drawerBadge.setOnClickListener {
            displayFragmentWithId(R.id.nav_about)
        }
        applyDrawerRim()
        // NextClave: the "groups" section header opens the groups editor
        binding.drawerGroupsLabel.setOnClickListener {
            displayFragmentWithId(R.id.nav_group)
        }
        addMoreRow(R.id.nav_logcat, R.string.menu_log, R.drawable.ic_baseline_timelapse_24)
        addMoreRow(R.id.nav_settings, R.string.settings, R.drawable.ic_action_settings)
        addMoreRow(R.id.nav_route, R.string.menu_route, R.drawable.ic_baseline_multiple_stop_24)
        addMoreRow(R.id.nav_traffic, R.string.menu_traffic, R.drawable.ic_device_data_usage)
        addMoreRow(R.id.nav_tools, R.string.menu_tools, R.drawable.baseline_construction_24)
    }

    // NextClave: resolve a theme attribute color (following ?attr chains);
    // returns 0 when the attribute is missing or transparent
    private fun resolveAttrColor(attr: Int): Int {
        var current = attr
        repeat(4) {
            val tv = TypedValue()
            if (!theme.resolveAttribute(current, tv, true)) return 0
            when (tv.type) {
                TypedValue.TYPE_ATTRIBUTE -> current = tv.data
                in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT -> return tv.data
                else -> return try {
                    ContextCompat.getColor(this, tv.resourceId)
                } catch (_: Exception) {
                    0
                }
            }
        }
        return 0
    }

    // NextClave: the selected Coal accent (coalBorderColor is re-defined by
    // every accent overlay); falls back to colorPrimary, then to Coal Pink
    private fun drawerAccentColor(): Int {
        val accent = resolveAttrColor(R.attr.coalBorderColor)
        if (accent != 0 && Color.alpha(accent) != 0) return accent
        val primary = resolveAttrColor(androidx.appcompat.R.attr.colorPrimary)
        if (primary != 0 && Color.alpha(primary) != 0) return primary
        return 0xFFC11D5E.toInt()
    }

    // NextClave: dimmed copy of the primary text color for idle row icons
    private fun dimPrimary(alpha: Int): Int {
        var base = resolveAttrColor(android.R.attr.textColorPrimary)
        if (base == 0 || Color.alpha(base) == 0) base = 0xFFE8EAED.toInt()
        return ColorUtils.setAlphaComponent(base, alpha)
    }

    // NextClave: the accent rim on the right edge of the panel - vertical
    // accent gradient fading at both ends, rounded on the right, exactly
    // like the approved preview
    private fun applyDrawerRim() {
        val r = dp2px(26).toFloat()
        val a = drawerAccent
        val rim = GradientDrawable().apply {
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            colors = intArrayOf(
                ColorUtils.setAlphaComponent(a, 0x2E),
                ColorUtils.setAlphaComponent(a, 0xB4),
                ColorUtils.setAlphaComponent(a, 0xB4),
                ColorUtils.setAlphaComponent(a, 0x28),
            )
            setCornerRadii(floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f))
        }
        binding.drawerRim.background = rim
    }

    private fun paintRowBar(row: View, visible: Boolean) {
        val bar = row.findViewById<View>(R.id.drawer_row_bar)
        bar.background.mutate().setTint(drawerAccent)
        bar.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun addMoreRow(navId: Int, titleRes: Int, iconRes: Int) {
        val row = layoutInflater.inflate(
            R.layout.layout_drawer_row, binding.drawerMoreContainer, false
        )
        row.findViewById<TextView>(R.id.drawer_row_title).setText(titleRes)
        row.findViewById<TextView>(R.id.drawer_row_count).visibility = View.GONE
        val icon = row.findViewById<ImageView>(R.id.drawer_row_icon)
        icon.setImageResource(iconRes)
        icon.imageTintList = ColorStateList.valueOf(dimPrimary(0xB8))
        paintRowBar(row, false)
        row.setOnClickListener {
            if (moreSelection == navId) {
                binding.drawerLayout.closeDrawers()
            } else {
                displayFragmentWithId(navId)
            }
        }
        binding.drawerMoreContainer.addView(row)
        moreRows[navId] = row
    }

    private fun paintGroupIcon(row: View, checked: Boolean) {
        val icon = row.findViewById<ImageView>(R.id.drawer_row_icon)
        if (checked) {
            val fill = AppCompatResources.getDrawable(this, R.drawable.ic_drawer_cb_fill)!!
                .mutate()
            fill.setTint(drawerAccent)
            val tick = AppCompatResources.getDrawable(this, R.drawable.ic_drawer_tick)!!
                .mutate()
            tick.setTint(Color.WHITE)
            icon.imageTintList = null
            icon.setImageDrawable(LayerDrawable(arrayOf(fill, tick)))
        } else {
            icon.setImageResource(R.drawable.ic_drawer_cb_off)
            icon.imageTintList = ColorStateList.valueOf(dimPrimary(0xB8))
        }
        paintRowBar(row, checked)
    }

    // NextClave: rebuild the subscription group rows of the glass drawer
    // (name + node counter), mirroring the group tabs. The rebuild is skipped
    // when nothing changed, so opening the drawer never churns the rows
    // mid-layout.
    private fun populateDrawerGroups() {
        runOnDefaultDispatcher {
            val entries = ArrayList<Triple<String, Int, Long>>()
            for (group in SagerDatabase.groupDao.allGroups()) {
                val count = SagerDatabase.proxyDao.countByGroup(group.id).toInt()
                if (group.ungrouped && count == 0) continue
                entries.add(Triple(group.displayName(), count, group.id))
            }
            onMainDispatcher {
                val unchanged = entries.size == drawerGroups.size &&
                        entries.withIndex().all { (i, entry) ->
                            drawerGroups[i].groupId == entry.third &&
                                    drawerGroups[i].title == entry.first &&
                                    drawerGroups[i].count == entry.second
                        }
                if (unchanged) return@onMainDispatcher
                binding.drawerGroupsContainer.removeAllViews()
                drawerGroups.clear()
                for ((idx, entry) in entries.withIndex()) {
                    val row = layoutInflater.inflate(
                        R.layout.layout_drawer_row, binding.drawerGroupsContainer, false
                    )
                    row.findViewById<TextView>(R.id.drawer_row_title).text = entry.first
                    val counter = row.findViewById<TextView>(R.id.drawer_row_count)
                    if (entry.second > 0) {
                        counter.text = entry.second.toString()
                        counter.visibility = View.VISIBLE
                    } else {
                        counter.visibility = View.GONE
                    }
                    paintRowBar(row, false)
                    row.setOnClickListener { selectDrawerGroup(idx) }
                    binding.drawerGroupsContainer.addView(row)
                    drawerGroups.add(DrawerGroupRow(entry.first, entry.second, entry.third, row))
                }
                // NextClave: exactly four subscription rows stay visible in the
                // block - with more groups the block scrolls in place (its
                // height is capped at four 52dp rows), with fewer it shrinks so
                // the lower sections never drift down
                binding.drawerGroupsScroll.layoutParams.height =
                    dp2px(52) * minOf(binding.drawerGroupsContainer.childCount, 4)
                binding.drawerGroupsScroll.requestLayout()
                syncDrawerActiveGroup()
            }
        }
    }

    // NextClave: subscription group tapped in the drawer - switch the main
    // list to that group tab FROM ANY SCREEN: when another fragment is
    // displayed, the selection is stored and the main list is shown; the
    // fresh adapter reads DataStore.selectedGroup and opens the same tab.
    private fun selectDrawerGroup(idx: Int) {
        if (idx !in drawerGroups.indices) return
        val groupId = drawerGroups[idx].groupId
        for ((gi, g) in drawerGroups.withIndex()) {
            paintGroupIcon(g.row, gi == idx)
        }
        binding.drawerLayout.closeDrawers()
        val fragment = supportFragmentManager
            .findFragmentById(R.id.fragment_holder) as? ConfigurationFragment
        // NextClave: a live main list switches the tab in place; anything
        // else (no fragment / view not ready yet) stores the selection and
        // shows the main list - the fresh adapter opens the stored group tab
        // itself. (adapter is assigned in onViewCreated, so a created view
        // guarantees it is initialized; ::adapter.isInitialized is NOT usable
        // here - the backing field of another class is not accessible.)
        if (fragment != null && fragment.view != null) {
            val position = fragment.adapter.groupList.indexOfFirst { it.id == groupId }
            if (position >= 0) {
                fragment.groupPager.currentItem = position
            }
            DataStore.selectedGroup = groupId
        } else {
            DataStore.selectedGroup = groupId
            displayFragment(ConfigurationFragment())
        }
        // NextClave: the main list has no row in the "more" section - after
        // switching to it from another screen the stale highlight of that
        // screen must be cleared, otherwise its row still counts itself as
        // active and swallows its own tap (just closes the drawer) until
        // some other row is picked first (reported as "настройки не
        // открываются повторно после выбора подписки")
        syncMoreSelection(0)
    }

    // NextClave: highlight the drawer row of the currently selected group tab
    private fun syncDrawerActiveGroup() {
        for (g in drawerGroups) {
            paintGroupIcon(g.row, g.groupId == DataStore.selectedGroup)
        }
    }

    // NextClave: highlight the "more" row of the screen on display
    // (0 = none of them)
    private fun syncMoreSelection(navId: Int) {
        moreSelection = navId
        for ((id, row) in moreRows) {
            val selected = id == navId
            val icon = row.findViewById<ImageView>(R.id.drawer_row_icon)
            icon.imageTintList = ColorStateList.valueOf(
                if (selected) drawerAccent else dimPrimary(0xB8)
            )
            paintRowBar(row, selected)
        }
    }

    // NextClave: blur the content behind the drawer (Android 12+). Honors the
    // user toggle and power saving; older versions keep the scrim only.
    private fun applyGlassBlur(enabled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (!enabled) {
            binding.coordinator.setRenderEffect(null)
            return
        }
        if (!DataStore.glassEffects) return
        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager != null && powerManager.isPowerSaveMode) return
        val radius = dp2px(18).toFloat()
        val blur = RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
        // saturate(1.55) brightness(1.06) chained after the blur - the exact
        // glass recipe of the approved preview
        val s = 1.55f
        val b = 1.06f
        val matrix = floatArrayOf(
            b * (0.213f + 0.787f * s), b * (0.715f - 0.715f * s), b * (0.072f - 0.072f * s), 0f, 0f,
            b * (0.213f - 0.213f * s), b * (0.715f + 0.285f * s), b * (0.072f - 0.072f * s), 0f, 0f,
            b * (0.213f - 0.213f * s), b * (0.715f - 0.715f * s), b * (0.072f + 0.928f * s), 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
        val color = RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(ColorMatrix(matrix)))
        binding.coordinator.setRenderEffect(RenderEffect.createChainEffect(color, blur))
    }

    // NextClave: entrance animation removed - it replayed after the drawer
    // was already fully open, so the brand header visibly jumped (reported
    // as "верхняя строка дергается при открытии"). The header is static now.
    fun displayFragment(fragment: ToolbarFragment) {
        if (fragment !is LogcatFragment) {
            binding.fab.show()
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_holder, fragment)
            .commitAllowingStateLoss()
        binding.drawerLayout.closeDrawers()
    }

    fun displayFragmentWithId(@IdRes id: Int): Boolean {
        when (id) {
            R.id.nav_configuration -> {
                displayFragment(ConfigurationFragment())
                connection.bandwidthTimeout = connection.bandwidthTimeout
            }
            R.id.nav_group -> displayFragment(GroupFragment())
            R.id.nav_route -> displayFragment(RouteFragment())
            R.id.nav_settings -> displayFragment(SettingsFragment())
            R.id.nav_traffic -> {
                displayFragment(TrafficFragment())
                connection.trafficTimeout = connection.trafficTimeout
            }
            R.id.nav_tools -> displayFragment(ToolsFragment())
            R.id.nav_logcat -> displayFragment(LogcatFragment())
            R.id.nav_about -> displayFragment(AboutFragment())
            else -> return false
        }
        // NextClave: highlight the matching "more" row (Журнал/Настройки/
        // Маршруты/Трафик/Инструменты); screens without a drawer row clear
        // the highlight
        syncMoreSelection(
            if (id == R.id.nav_logcat || id == R.id.nav_settings ||
                id == R.id.nav_route || id == R.id.nav_traffic ||
                id == R.id.nav_tools
            ) {
                id
            } else {
                0
            }
        )
        return true
    }

    fun ruleCreated() {
        syncMoreSelection(R.id.nav_route)
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_holder, RouteFragment())
            .commitAllowingStateLoss()
        if (SagerNet.started) {
            snackbar(getString(R.string.restart)).setAction(R.string.apply) {
                SagerNet.reloadService()
            }.show()
        }
    }

    var state = BaseService.State.Idle

    private fun changeState(
        state: BaseService.State,
        msg: String? = null,
        animate: Boolean = false,
    ) {
        val started = state == BaseService.State.Connected

        if (!started) {
            statsUpdated(emptyList())
        }

        binding.fab.changeState(state, this.state, animate)
        binding.stats.changeState(state)
        if (msg != null) snackbar(msg).show()
        this.state = state

        when (state) {
            BaseService.State.Connected, BaseService.State.Stopped -> {
                statsUpdated(emptyList())
                runOnDefaultDispatcher {
                    // refresh view
                    ProfileManager.postUpdate(DataStore.currentProfile)
                }
                // NextClave: node connected => refresh its URL test on its card
                if (state == BaseService.State.Connected) {
                    testConnectedNode()
                    // NextClave: auto-show the node line (flag + IP + ping)
                    // on connect instead of waiting for a tap
                    runOnDefaultDispatcher {
                        delay(800) // let the connection settle first
                        onMainDispatcher { binding.stats.showNodeAddress() }
                    }
                }
            }
            else -> {}
        }
    }

    // NextClave: dynamic URL test for the connected node. When a node gets
    // connected, its card immediately shows a fresh latency instead of the
    // stale value from the last manual group test. Same rules as the manual
    // urlTest(): same link, same 1500 ms timeout, protected dial on system TUN.
    @Volatile
    private var testingConnectedNode = false

    private fun testConnectedNode() {
        if (testingConnectedNode) return
        testingConnectedNode = true
        runOnDefaultDispatcher {
            try {
                delay(500) // let the connection settle first
                val profileId = DataStore.startedProfile
                if (profileId <= 0 || !SagerNet.started) return@runOnDefaultDispatcher
                val profile = SagerDatabase.proxyDao.getById(profileId)
                    ?: return@runOnDefaultDispatcher
                val vpnRunning = SagerNet.started && DataStore.startedProfile > 0
                        && DataStore.serviceMode == Key.MODE_VPN
                val protectedDial = vpnRunning
                        && DataStore.tunImplementation == TunImplementation.SYSTEM
                try {
                    val instance = if (protectedDial) {
                        V2RayTestInstance(
                            profile, DataStore.connectionTestURL, 1500,
                            protectPath = SagerNet.deviceStorage.noBackupFilesDir.toString() + "/protect_path"
                        )
                    } else {
                        V2RayTestInstance(profile, DataStore.connectionTestURL, 1500)
                    }
                    val result = instance.use { it.doTest() }
                    profile.status = 1
                    profile.ping = result
                } catch (e: PluginManager.PluginNotFoundException) {
                    profile.status = -1
                    profile.error = e.readableMessage
                } catch (e: Exception) {
                    profile.status = 3
                    profile.error = e.readableMessage
                }
                ProfileManager.updateProfile(profile)
                Logs.i("NextClave urltest: [connected] ${profile.displayName()} -> " +
                        "${profile.ping} ms (status=${profile.status})")
            } catch (e: Exception) {
                Logs.w(e)
            } finally {
                testingConnectedNode = false
            }
        }
    }

    override fun snackbarInternal(text: CharSequence): Snackbar {
        return Snackbar.make(binding.coordinator, text, Snackbar.LENGTH_LONG).apply {
            anchorView = binding.fab
        }
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        changeState(state, msg, true)

        (supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ConfigurationFragment)
            ?.refreshAllSelections()
    }

    override fun statsUpdated(stats: List<AppStats>) {
        (supportFragmentManager.findFragmentById(R.id.fragment_holder) as? TrafficFragment)?.emitStats(
            stats
        )
    }

    override fun routeAlert(type: Int, routeName: String) {
        val markwon = Markwon.create(this)
        when (type) {
            Alerts.ROUTE_ALERT_NOT_VPN -> {
                val message = markwon.toMarkdown(getString(R.string.route_need_vpn, routeName))
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
            Alerts.ROUTE_ALERT_NEED_COARSE_LOCATION_ACCESS -> {
                val message = markwon.toMarkdown(getString(R.string.route_need_coarse_location, routeName))
                MaterialAlertDialogBuilder(this).setTitle(R.string.missing_permission)
                    .setMessage(message)
                    .setNeutralButton(R.string.open_settings) { _, _ ->
                        try {
                            startActivity(Intent().apply {
                                action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                                data = Uri.fromParts(
                                    "package", packageName, null
                                )
                            })
                        } catch (e: Exception) {
                            snackbar(e.readableMessage).show()
                        }
                    }
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            Alerts.ROUTE_ALERT_NEED_FINE_LOCATION_ACCESS -> {
                val message = markwon.toMarkdown(getString(R.string.route_need_fine_location, routeName))
                MaterialAlertDialogBuilder(this).setTitle(R.string.missing_permission)
                    .setMessage(message)
                    .setNeutralButton(R.string.open_settings) { _, _ ->
                        try {
                            startActivity(Intent().apply {
                                action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                                data = Uri.fromParts(
                                    "package", packageName, null
                                )
                            })
                        } catch (e: Exception) {
                            snackbar(e.readableMessage).show()
                        }
                    }
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            Alerts.ROUTE_ALERT_NEED_BACKGROUND_LOCATION_ACCESS -> {
                val message = markwon.toMarkdown(getString(R.string.route_need_background_location, routeName))
                MaterialAlertDialogBuilder(this).setTitle(R.string.missing_permission)
                    .setMessage(message)
                    .setNeutralButton(R.string.open_settings) { _, _ ->
                        try {
                            startActivity(Intent().apply {
                                action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                                data = Uri.fromParts(
                                    "package", packageName, null
                                )
                            })
                        } catch (e: Exception) {
                            snackbar(e.readableMessage).show()
                        }
                    }
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            Alerts.ROUTE_ALERT_LOCATION_DISABLED -> {
                val message = markwon.toMarkdown(getString(R.string.route_need_location_enabled, routeName))
                MaterialAlertDialogBuilder(this).setTitle(R.string.location_disabled)
                    .setMessage(message)
                    .setNeutralButton(R.string.open_settings) { _, _ ->
                        try {
                            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                        } catch (e: Exception) {
                            snackbar(e.readableMessage).show()
                        }
                    }
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            Alerts.ROUTE_ALERT_ALL_PACKAGES_UNINSTALLED -> {
                val message = markwon.toMarkdown("Ignored $routeName because all packages are uninstalled.")
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    val connection = SagerConnection(true)
    override fun onServiceConnected(service: ISagerNetService) = changeState(try {
        BaseService.State.entries[service.state].also {
            SagerNet.started = it.canStop
        }
    } catch (_: RemoteException) {
        BaseService.State.Idle
    })

    override fun onServiceDisconnected() = changeState(BaseService.State.Idle)
    override fun onBinderDied() {
        connection.disconnect(this)
        connection.connect(this, this)
    }

    private val connect = registerForActivityResult(VpnRequestActivity.StartService()) {
        if (it) snackbar(R.string.vpn_permission_denied).show()
    }

    override fun trafficUpdated(profileId: Long, stats: TrafficStats, isCurrent: Boolean) {
        if (profileId == 0L) return

        if (isCurrent) binding.stats.updateTraffic(
            stats.txRateProxy, stats.rxRateProxy
        )

        runOnDefaultDispatcher {
            ProfileManager.postTrafficUpdated(profileId, stats)
        }
    }

    override fun profilePersisted(profileId: Long) {
        runOnDefaultDispatcher {
            ProfileManager.postUpdate(profileId)
        }
    }

    override fun observatoryResultsUpdated(groupId: Long) {
        runOnDefaultDispatcher {
            GroupManager.postReload(groupId)
        }
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        when (key) {
            Key.SERVICE_MODE -> onBinderDied()
            Key.PROXY_APPS, Key.BYPASS_MODE, Key.INDIVIDUAL -> {
                if (state.canStop) {
                    snackbar(getString(R.string.restart)).setAction(R.string.apply) {
                        SagerNet.reloadService()
                    }.show()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        connection.bandwidthTimeout = 1000
        GroupManager.userInterface = userInterface
    }

    override fun onStop() {
        connection.bandwidthTimeout = 0
        GroupManager.userInterface = null
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        DataStore.configurationStore.unregisterChangeListener(this)
        connection.disconnect(this)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (super.onKeyDown(keyCode, event)) return true
                binding.drawerLayout.open()
                binding.drawerPanel.requestFocus()
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (binding.drawerLayout.isOpen) {
                    binding.drawerLayout.close()
                    return true
                }
            }
        }

        if (super.onKeyDown(keyCode, event)) return true
        if (binding.drawerLayout.isOpen) return false

        val fragment = supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
        return fragment != null && fragment.onKeyDown(keyCode, event)
    }

}