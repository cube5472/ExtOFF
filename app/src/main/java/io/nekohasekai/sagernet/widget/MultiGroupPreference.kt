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

package io.nekohasekai.sagernet.widget

import android.content.Context
import android.util.AttributeSet
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase

// NextClave: multi-group selector for the balancer (TYPE_GROUP). Replaces the
// single-choice GroupPreference: the user ticks any number of groups
// (subscriptions), the selection is stored as a comma-separated id list under
// Key.BALANCER_GROUPS and read back by BalancerSettingsActivity.serialize().
// The value lives in the profile cache store like every other balancer
// preference, so it survives rotation via KEY_TEMP_BEAN_BYTES round-trips the
// same way (the activity writes it in init() before the fragment binds).
class MultiGroupPreference @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : Preference(context, attrs) {

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context, attrs, defStyleAttr
    )

    var checkedIds: Set<Long> = readChecked()
        private set

    private fun readChecked(): Set<Long> {
        return DataStore.balancerGroups.split(",")
            .mapNotNull { it.trim().takeIf { t -> t.isNotBlank() }?.toLongOrNull() }
            .filter { it > 0L }
            .toSet()
    }

    public override fun notifyChanged() {
        super.notifyChanged()
    }

    fun reloadFromStore() {
        checkedIds = readChecked()
        notifyChanged()
    }

    override fun onClick() {
        val groups = SagerDatabase.groupDao.allGroups()
        if (groups.isEmpty()) return

        val names = groups.map { it.displayName() }.toTypedArray()
        val dialogChecked = LinkedHashSet(checkedIds)
        val checked = groups.map { it.id in dialogChecked }.toBooleanArray()

        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                val id = groups[which].id
                if (isChecked) dialogChecked.add(id) else dialogChecked.remove(id)
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                checkedIds = dialogChecked
                DataStore.balancerGroups = dialogChecked.joinToString(",")
                notifyChanged()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun getSummary(): CharSequence? {
        val names = checkedIds.mapNotNull { id ->
            SagerDatabase.groupDao.getById(id)?.displayName()
        }
        if (names.isEmpty()) {
            return context.getString(R.string.balancer_groups_none)
        }
        return names.joinToString(" + ")
    }

}
