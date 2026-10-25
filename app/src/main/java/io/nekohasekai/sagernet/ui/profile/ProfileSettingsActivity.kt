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

package io.nekohasekai.sagernet.ui.profile

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.component1
import androidx.activity.result.component2
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.LayoutRes
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.QuickToggleShortcut
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.internal.BalancerBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.internal.ConfigBean
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ktx.byteBuffer
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ui.ProfileSelectActivity
import io.nekohasekai.sagernet.ui.ThemedActivity
import java.io.ByteArrayOutputStream
import kotlin.properties.Delegates

@Suppress("UNCHECKED_CAST")
abstract class ProfileSettingsActivity<T : AbstractBean>(
    @LayoutRes resId: Int = R.layout.layout_config_settings,
) : ThemedActivity(resId),
    OnPreferenceDataStoreChangeListener {

    override val onBackPressedCallback = object : OnBackPressedCallback(enabled = false) {
        override fun handleOnBackPressed() {
            MaterialAlertDialogBuilder(this@ProfileSettingsActivity)
                .setTitle(R.string.unsaved_changes_prompt)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    runOnDefaultDispatcher {
                        saveAndExit()
                    }
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    finish()
                }
                .show()
        }
    }

    // NextClave v32: the profile chosen as the chain entry hop ("dial through").
    // Kept in the in-memory profile cache (Key.NC_CHAIN_VIA) while the editor is
    // open and persisted to ProxyEntity.chainVia on save.
    protected var chainPref: Preference? = null

    private val selectViaProfile =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { (resultCode, data) ->
            if (resultCode == Activity.RESULT_OK) runOnDefaultDispatcher {
                val profileId = data?.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0L) ?: 0L
                if (profileId != 0L && profileId == DataStore.editingId) {
                    onMainDispatcher {
                        MaterialAlertDialogBuilder(this@ProfileSettingsActivity)
                            .setTitle(R.string.chain_via_not_allowed)
                            .setMessage(R.string.chain_via_self_sum)
                            .setPositiveButton(android.R.string.ok, null).show()
                    }
                    return@runOnDefaultDispatcher
                }
                val picked = profileId.takeIf { it > 0L }?.let {
                    try {
                        SagerDatabase.proxyDao.getById(it)
                    } catch (e: Exception) {
                        null
                    }
                }
                if (profileId > 0L && (picked == null ||
                            picked.type == ProxyEntity.TYPE_BALANCER ||
                            (picked.type == ProxyEntity.TYPE_CONFIG && picked.configBean?.type == "v2ray"))
                ) {
                    onMainDispatcher {
                        MaterialAlertDialogBuilder(this@ProfileSettingsActivity)
                            .setTitle(R.string.chain_via_not_allowed)
                            .setMessage(R.string.chain_via_not_allowed_sum)
                            .setPositiveButton(android.R.string.ok, null).show()
                    }
                    return@runOnDefaultDispatcher
                }
                DataStore.ncChainVia = profileId
                dirty = true
                onBackPressedCallback.isEnabled = true
                updateChainSummary()
            }
        }

    companion object {
        const val EXTRA_PROFILE_ID = "id"
        const val EXTRA_IS_SUBSCRIPTION = "sub"
        const val KEY_TEMP_BEAN_BYTES = "temp_bean_bytes"
        const val KEY_DIRTY = "dirty"
    }

    abstract fun createEntity(): T
    abstract fun T.init()
    abstract fun T.serialize()

    protected var dirty = false
    protected var bean: T? = null
    protected var isSubscription by Delegates.notNull<Boolean>()

    override fun onCreate(savedInstanceState: Bundle?) {
        savedInstanceState?.getByteArray(KEY_TEMP_BEAN_BYTES)?.let {
            bean = createEntity().apply {
                deserialize(ByteBufferInput(it))
            }
        }
        super.onCreate(savedInstanceState)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.profile_config)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        if (savedInstanceState == null) runOnDefaultDispatcher {
            val editingId = intent.getLongExtra(EXTRA_PROFILE_ID, 0L)
            isSubscription = intent.getBooleanExtra(EXTRA_IS_SUBSCRIPTION, false)
            DataStore.editingId = editingId
            bean = if (editingId == 0L) {
                DataStore.editingGroup = DataStore.selectedGroupForImport()
                DataStore.ncChainVia = 0L
                createEntity().applyDefaultValues()
            } else {
                val proxyEntity = SagerDatabase.proxyDao.getById(editingId)
                if (proxyEntity == null) {
                    onMainDispatcher {
                        finish()
                    }
                    return@runOnDefaultDispatcher
                }
                DataStore.editingGroup = proxyEntity.groupId
                DataStore.ncChainVia = proxyEntity.chainVia
                (proxyEntity.requireBean() as T)
            }
            bean!!.init()
            onMainDispatcher {
                supportFragmentManager
                    .beginTransaction()
                    .replace(R.id.settings, MyPreferenceFragmentCompat())
                    .commit()
            }

        }

        savedInstanceState?.getBoolean(KEY_DIRTY)?.let {
            dirty = it
            onBackPressedCallback.isEnabled = it
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        bean?.let {
            val output = ByteArrayOutputStream()
            val buffer = output.byteBuffer()
            it.serializeToBuffer(buffer)
            buffer.flush()
            buffer.close()
            outState.putByteArray(KEY_TEMP_BEAN_BYTES, output.toByteArray())
        }
        outState.putBoolean(KEY_DIRTY, dirty)
    }

    open suspend fun saveAndExit() {

        val editingId = DataStore.editingId
        // NextClave v32: persist the chain entry hop picked in the editor
        val chainVia = DataStore.ncChainVia
        if (editingId == 0L) {
            val editingGroup = DataStore.editingGroup
            val created = ProfileManager.createProfile(editingGroup, createEntity().apply { serialize() })
            if (chainVia > 0L) {
                created.chainVia = chainVia
                ProfileManager.updateProfile(created)
            }
        } else {
            val entity = SagerDatabase.proxyDao.getById(DataStore.editingId)
            if (entity == null) {
                finish()
                return
            }
            entity.chainVia = chainVia
            ProfileManager.updateProfile(entity.apply { (requireBean() as T).serialize() })
        }
        setResult(RESULT_OK)
        finish()

    }

    val child by lazy { supportFragmentManager.findFragmentById(R.id.settings) as MyPreferenceFragmentCompat }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.profile_config_menu, menu)
        menu.findItem(R.id.action_create_shortcut)?.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && DataStore.editingId > 0L) {
                isVisible = true
            }
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.action_delete -> {
            if (DataStore.editingId == 0L) {
                finish()
            } else {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.delete_confirm_prompt)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        runOnDefaultDispatcher {
                            ProfileManager.deleteProfile(DataStore.editingGroup, DataStore.editingId)
                        }
                        finish()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            true
        }
        R.id.action_apply -> {
            runOnDefaultDispatcher {
                saveAndExit()
            }
            true
        }
        R.id.action_create_shortcut -> {
            val entity = SagerDatabase.proxyDao.getById(DataStore.editingId)
            if (entity != null) {
                val shortcut = ShortcutInfoCompat.Builder(this, "shortcut-profile-${entity.id}")
                    .setShortLabel(entity.displayName())
                    .setLongLabel(entity.displayName())
                    .setIcon(IconCompat.createWithResource(this, R.drawable.ic_qu_shadowsocks_foreground))
                    .setIntent(Intent(this, QuickToggleShortcut::class.java).apply {
                            action = Intent.ACTION_MAIN
                            putExtra("profile", entity.id)
                        }
                    ).build()
                ShortcutManagerCompat.requestPinShortcut(this, shortcut, null)
            }
            true
        }
        else -> false
    }

    override fun onSupportNavigateUp(): Boolean {
        if (!super.onSupportNavigateUp()) finish()
        return true
    }

    override fun onDestroy() {
        DataStore.profileCacheStore.unregisterChangeListener(this)
        super.onDestroy()
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        if (key != Key.PROFILE_DIRTY) {
            dirty = true
            onBackPressedCallback.isEnabled = true
        }
    }

    abstract fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    )

    open fun PreferenceFragmentCompat.viewCreated(view: View, savedInstanceState: Bundle?) {
    }

    open fun PreferenceFragmentCompat.displayPreferenceDialog(preference: Preference): Boolean {
        return false
    }

    // NextClave v32: append the chain entry section to every protocol editor.
    // Skipped for the editors that ARE chain-related themselves (chain,
    // balancer, custom config) — their semantics already cover routing.
    fun addChainPreference(fragment: MyPreferenceFragmentCompat) {
        val currentBean = bean
        if (currentBean is ChainBean || currentBean is BalancerBean || currentBean is ConfigBean) {
            return
        }
        val screen = fragment.preferenceScreen ?: return
        val category = PreferenceCategory(fragment.requireContext()).apply {
            key = "nc_chain_category"
            title = getString(R.string.chain_via_category)
            isIconSpaceReserved = false
        }
        screen.addPreference(category)
        val pref = Preference(fragment.requireContext()).apply {
            key = Key.NC_CHAIN_VIA
            title = getString(R.string.chain_via_title)
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                MaterialAlertDialogBuilder(this@ProfileSettingsActivity)
                    .setTitle(R.string.chain_via_title)
                    .setItems(
                        arrayOf(
                            getString(R.string.chain_via_pick),
                            getString(R.string.chain_via_reset),
                        )
                    ) { _, which ->
                        when (which) {
                            0 -> selectViaProfile.launch(
                                Intent(this@ProfileSettingsActivity, ProfileSelectActivity::class.java)
                            )
                            else -> runOnDefaultDispatcher {
                                DataStore.ncChainVia = 0L
                                dirty = true
                                onBackPressedCallback.isEnabled = true
                                updateChainSummary()
                            }
                        }
                    }
                    .show()
                true
            }
        }
        category.addPreference(pref)
        chainPref = pref
        updateChainSummary()
    }

    // NextClave v32: resolve the entry hop chain into a human-readable path
    // ("you → A → B → internet"), following nested via links with a loop guard.
    fun updateChainSummary() {
        val pref = chainPref ?: return
        runOnDefaultDispatcher {
            val viaId = DataStore.ncChainVia
            val hops = ArrayList<String>()
            var broken = false
            var cur = viaId
            val visited = HashSet<Long>()
            while (cur > 0L) {
                if (!visited.add(cur) || hops.size >= 8) {
                    broken = true
                    break
                }
                val entity = try {
                    SagerDatabase.proxyDao.getById(cur)
                } catch (e: Exception) {
                    null
                }
                if (entity == null) {
                    broken = true
                    break
                }
                hops.add(entity.displayName())
                cur = entity.chainVia
            }
            val summary = if (viaId <= 0L && hops.isEmpty()) {
                getString(R.string.chain_via_none)
            } else {
                val parts = ArrayList<String>()
                parts.add(getString(R.string.chain_via_you))
                parts.addAll(hops)
                parts.add(getString(R.string.chain_via_internet))
                var text = parts.joinToString(" → ")
                if (broken) text += " " + getString(R.string.chain_via_broken)
                text
            }
            onMainDispatcher {
                pref.summary = summary
            }
        }
    }

    class MyPreferenceFragmentCompat : PreferenceFragmentCompat() {

        val activity: ProfileSettingsActivity<*>
            get() = requireActivity() as ProfileSettingsActivity<*>

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.preferenceDataStore = DataStore.profileCacheStore
            try {
                activity.apply {
                    createPreferences(savedInstanceState, rootKey)
                }
            } catch (e: Exception) {
                Logs.w(e)
            }
            // NextClave v32: shared chain entry section
            try {
                activity.addChainPreference(this)
            } catch (e: Exception) {
                Logs.w(e)
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            ViewCompat.setOnApplyWindowInsetsListener(listView) { v, insets ->
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            or WindowInsetsCompat.Type.displayCutout()
                )
                v.updatePadding(
                    left = bars.left,
                    right = bars.right,
                    bottom = bars.bottom,
                )
                insets
            }

            activity.apply {
                viewCreated(view, savedInstanceState)
                DataStore.profileCacheStore.registerChangeListener(this)
            }
        }

        override fun onDisplayPreferenceDialog(preference: Preference) {
            activity.apply {
                if (displayPreferenceDialog(preference)) return
            }
            super.onDisplayPreferenceDialog(preference)
        }

    }

    object PasswordSummaryProvider : Preference.SummaryProvider<EditTextPreference> {

        override fun provideSummary(preference: EditTextPreference): CharSequence {
            val text = preference.text
            return if (text.isNullOrEmpty()) {
                preference.context.getString(androidx.preference.R.string.not_set)
            } else {
                "\u2022".repeat(text.length)
            }
        }

    }

}
