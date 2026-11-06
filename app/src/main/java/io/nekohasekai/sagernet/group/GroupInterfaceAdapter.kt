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

package io.nekohasekai.sagernet.group

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ui.ThemedActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class GroupInterfaceAdapter(val context: ThemedActivity) : GroupManager.Interface {

    override suspend fun confirm(message: String): Boolean {
        return suspendCancellableCoroutine {
            var resumed = false
            fun tryResume(value: Boolean) {
                if (!resumed) {
                    resumed = true
                    it.resume(value)
                }
            }
            runOnMainDispatcher {
                MaterialAlertDialogBuilder(context).setTitle(R.string.confirm)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> tryResume(true) }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> tryResume(false) }
                    .setOnCancelListener { _ -> tryResume(false) }
                    .show()
            }
        }
    }

    override suspend fun onUpdateSuccess(
        group: ProxyGroup,
        changed: Int,
        added: List<String>,
        updated: Map<String, String>,
        deleted: List<String>,
        duplicate: List<String>,
    ) = onUpdateSuccess(group, changed, added, updated, deleted, duplicate, 0)

    override suspend fun onUpdateSuccess(
        group: ProxyGroup,
        changed: Int,
        added: List<String>,
        updated: Map<String, String>,
        deleted: List<String>,
        duplicate: List<String>,
        filtered: Int,
    ) {
        if (changed == 0 && duplicate.isEmpty() && filtered <= 0) {
            onMainDispatcher {
                context.snackbar(context.getString(R.string.group_no_difference, group.displayName())).show()
            }
        } else {
            var status = context.resources.getQuantityString(R.plurals.group_updated, changed, group.name, changed) + "\n\n"
            if (added.isNotEmpty()) {
                status += context.getString(
                        R.string.group_added, names(added)
                ) + "\n\n"
            }
            if (updated.isNotEmpty()) {
                status += context.getString(R.string.group_changed,
                        names(updated.map {
                            if (it.key == it.value) it.key else "${it.key} => ${it.value}"
                        })
                ) + "\n\n"
            }
            if (deleted.isNotEmpty()) {
                status += context.getString(
                        R.string.group_deleted, names(deleted)
                ) + "\n\n"
            }
            if (duplicate.isNotEmpty()) {
                status += context.getString(
                        R.string.group_duplicate, names(duplicate)
                ) + "\n\n"
            }
            if (filtered > 0) {
                // NextClave: Throne's Surface.Diff — nodes dropped by the
                // include/exclude name regexes are reported, not only logged
                status += context.getString(R.string.group_filtered, filtered) + "\n\n"
            }

            onMainDispatcher {
                MaterialAlertDialogBuilder(context).setTitle(
                        context.getString(
                                R.string.group_diff, group.displayName()
                        )
                ).setMessage(status.trim()).setPositiveButton(android.R.string.ok, null).show()
            }
        }
    }

    // NextClave: a subscription can carry hundreds of nodes — collapse huge
    // sections the way Throne does (count line instead of endless names).
    private fun names(names: List<String>, limit: Int = 30): String {
        val shown = names.take(limit).joinToString("\n")
        return if (names.size > limit) {
            shown + "\n" + context.getString(R.string.group_more, names.size - limit)
        } else shown
    }

    override suspend fun onUpdateFailure(group: ProxyGroup, message: String) {
        onMainDispatcher {
            context.snackbar(message).show()
        }
    }

}