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

package io.nekohasekai.sagernet.utils

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.app

object Theme {

    const val RED = 1
    const val PINK = 2
    const val PURPLE = 3
    const val DEEP_PURPLE = 4
    const val INDIGO = 5
    const val BLUE = 6
    const val LIGHT_BLUE = 7
    const val CYAN = 8
    const val TEAL = 9
    const val GREEN = 10
    const val LIGHT_GREEN = 11
    const val LIME = 12
    const val YELLOW = 13
    const val AMBER = 14
    const val ORANGE = 15
    const val DEEP_ORANGE = 16

    const val BROWN = 17
    const val GREY = 18
    const val BLUE_GREY = 19
    const val BLACK = 20

    const val DYNAMIC = 21

    const val COAL_TURQUOISE = 22
    const val COAL_PINK = 23
    const val COAL_BLACK = 24

    private fun defaultTheme() = COAL_TURQUOISE

    fun apply(context: Context) {
        context.setTheme(getTheme())
    }

    fun applyDialog(context: Context) {
        context.setTheme(getDialogTheme())
    }

    fun getTheme(): Int {
        return getTheme(DataStore.appTheme)
    }

    fun getDialogTheme(): Int {
        return getDialogTheme(DataStore.appTheme)
    }

    fun getTheme(theme: Int): Int {
        return when (theme) {
            RED -> R.style.Theme_SagerNet_Red
            PINK -> R.style.Theme_SagerNet
            PURPLE -> R.style.Theme_SagerNet_Purple
            DEEP_PURPLE -> R.style.Theme_SagerNet_DeepPurple
            INDIGO -> R.style.Theme_SagerNet_Indigo
            BLUE -> R.style.Theme_SagerNet_Blue
            LIGHT_BLUE -> R.style.Theme_SagerNet_LightBlue
            CYAN -> R.style.Theme_SagerNet_Cyan
            TEAL -> R.style.Theme_SagerNet_Teal
            GREEN -> R.style.Theme_SagerNet_Green
            LIGHT_GREEN -> R.style.Theme_SagerNet_LightGreen
            LIME -> R.style.Theme_SagerNet_Lime
            YELLOW -> R.style.Theme_SagerNet_Yellow
            AMBER -> R.style.Theme_SagerNet_Amber
            ORANGE -> R.style.Theme_SagerNet_Orange
            DEEP_ORANGE -> R.style.Theme_SagerNet_DeepOrange
            BROWN -> R.style.Theme_SagerNet_Brown
            GREY -> R.style.Theme_SagerNet_Grey
            BLUE_GREY -> R.style.Theme_SagerNet_BlueGrey
            BLACK -> if (usingNightMode()) R.style.Theme_SagerNet_Black else R.style.Theme_SagerNet_LightBlack
            COAL_TURQUOISE -> R.style.Theme_SagerNet_CoalTurquoise
            COAL_PINK -> R.style.Theme_SagerNet_CoalPink
            COAL_BLACK -> R.style.Theme_SagerNet_CoalBlack
            DYNAMIC -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) R.style.Theme_SagerNet_Dynamic else getTheme(defaultTheme())
            else -> getTheme(defaultTheme())
        }
    }

    fun getDialogTheme(theme: Int): Int {
        return when (theme) {
            RED -> R.style.Theme_SagerNet_Dialog_Red
            PINK -> R.style.Theme_SagerNet_Dialog
            PURPLE -> R.style.Theme_SagerNet_Dialog_Purple
            DEEP_PURPLE -> R.style.Theme_SagerNet_Dialog_DeepPurple
            INDIGO -> R.style.Theme_SagerNet_Dialog_Indigo
            BLUE -> R.style.Theme_SagerNet_Dialog_Blue
            LIGHT_BLUE -> R.style.Theme_SagerNet_Dialog_LightBlue
            CYAN -> R.style.Theme_SagerNet_Dialog_Cyan
            TEAL -> R.style.Theme_SagerNet_Dialog_Teal
            GREEN -> R.style.Theme_SagerNet_Dialog_Green
            LIGHT_GREEN -> R.style.Theme_SagerNet_Dialog_LightGreen
            LIME -> R.style.Theme_SagerNet_Dialog_Lime
            YELLOW -> R.style.Theme_SagerNet_Dialog_Yellow
            AMBER -> R.style.Theme_SagerNet_Dialog_Amber
            ORANGE -> R.style.Theme_SagerNet_Dialog_Orange
            DEEP_ORANGE -> R.style.Theme_SagerNet_Dialog_DeepOrange
            BROWN -> R.style.Theme_SagerNet_Dialog_Brown
            GREY -> R.style.Theme_SagerNet_Dialog_Grey
            BLUE_GREY -> R.style.Theme_SagerNet_Dialog_BlueGrey
            BLACK -> if (usingNightMode()) R.style.Theme_SagerNet_Dialog_Black else R.style.Theme_SagerNet_Dialog_LightBlack
            COAL_TURQUOISE -> R.style.Theme_SagerNet_Dialog_CoalTurquoise
            COAL_PINK -> R.style.Theme_SagerNet_Dialog_CoalPink
            COAL_BLACK -> R.style.Theme_SagerNet_Dialog_CoalBlack
            DYNAMIC -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) R.style.Theme_SagerNet_Dialog_Dynamic else getDialogTheme(defaultTheme())
            else -> getDialogTheme(defaultTheme())
        }
    }

    var currentNightMode = -1

    // NextClave: coal themes paint hardcoded black surfaces (windowBackground
    // / colorBackground / colorSurface) while text colors are resolved from
    // the DayNight resource set. In system light mode the light palette
    // (dark text) lands on those black surfaces and the whole UI becomes
    // unreadable. Coal themes are dark-only — they must always resolve the
    // night resource set, regardless of the system setting.
    // Mirrors the fallbacks of getTheme(): unset (0) and pre-12 dynamic
    // fall back to defaultTheme() which is a coal theme.
    fun isDarkOnlyTheme(): Boolean {
        val theme = DataStore.appTheme
        val resolved = when {
            theme == 0 -> defaultTheme()
            theme == DYNAMIC && Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> defaultTheme()
            else -> theme
        }
        return resolved == COAL_TURQUOISE || resolved == COAL_PINK || resolved == COAL_BLACK
    }

    fun getNightMode(): Int {
        if (isDarkOnlyTheme()) {
            return AppCompatDelegate.MODE_NIGHT_YES
        }
        if (currentNightMode == -1) {
            currentNightMode = DataStore.nightTheme
        }
        return getNightMode(currentNightMode)
    }

    fun getNightMode(mode: Int): Int {
        return when (mode) {
            0 -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            1 -> AppCompatDelegate.MODE_NIGHT_YES
            2 -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY
        }
    }

    fun usingNightMode(): Boolean {
        if (isDarkOnlyTheme()) return true
        return when (DataStore.nightTheme) {
            1 -> true
            2 -> false
            else -> (app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
    }

    fun applyNightTheme() {
        AppCompatDelegate.setDefaultNightMode(getNightMode())
    }

}