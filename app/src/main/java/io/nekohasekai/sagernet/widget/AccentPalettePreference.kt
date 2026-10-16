/******************************************************************************
 *                                                                            *
 * Copyright (C) 2021 Matsuri authors                                         *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
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
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.content.res.ResourcesCompat
import androidx.core.content.res.TypedArrayUtils
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.setPadding
import androidx.core.widget.NestedScrollView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout
import com.google.android.flexbox.JustifyContent
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.getColorAttr

import kotlin.math.roundToInt

/**
 * NextClave: palette preference for coal themes. Lets the user pick the
 * exact accent shade used for the card outline (coalBorderColor), selection
 * stripe, tab indicator, switches and other accent-colored details.
 * 0 = theme default, 1..N = palette entry (ThemeOverlay style applied by
 * Theme.applyCoalAccent on every themed activity/dialog).
 */
@SuppressLint("RestrictedApi")
class AccentPalettePreference
@JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = TypedArrayUtils.getAttr(
        context,
        androidx.preference.R.attr.editTextPreferenceStyle,
        android.R.attr.editTextPreferenceStyle
    )
) : Preference(
    context, attrs, defStyle
) {

    private fun paletteColors(): IntArray {
        val ta = context.resources.obtainTypedArray(R.array.coal_accent_colors)
        val colors = IntArray(ta.length())
        for (i in 0 until ta.length()) {
            colors[i] = ta.getColor(i, 0)
        }
        ta.recycle()
        return colors
    }

    private fun currentAccentColor(colors: IntArray): Int? {
        val index = DataStore.coalAccent
        if (index < 1 || index > colors.size) return null
        return colors[index - 1]
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val widgetFrame = holder.findViewById(android.R.id.widget_frame) as LinearLayout
        widgetFrame.removeAllViews()

        val color = currentAccentColor(paletteColors())
        val drawable = if (color != null) {
            ResourcesCompat.getDrawable(
                context.resources,
                R.drawable.ic_baseline_fiber_manual_record_24,
                null
            )!!.apply { DrawableCompat.setTint(this.mutate(), color) }
        } else {
            // theme default: black circle with the theme accent ring
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.BLACK)
                setStroke(dp2px(3), context.getColorAttr(android.R.attr.colorPrimary))
            }
        }

        widgetFrame.addView(
            ImageView(context).apply {
                layoutParams = ViewGroup.LayoutParams(dp2px(36), dp2px(36))
                setImageDrawable(drawable)
            }
        )
        widgetFrame.visibility = View.VISIBLE
    }

    fun getImageViewAtColor(color: Int, sizeDp: Int, paddingDp: Int): ImageView {
        val factor = context.resources.displayMetrics.density
        val size = (sizeDp * factor).roundToInt()
        val paddingSize = (paddingDp * factor).roundToInt()

        return ImageView(context).apply {
            layoutParams = ViewGroup.LayoutParams(size, size)
            setPadding(paddingSize)
            val drawable = ResourcesCompat.getDrawable(
                context.resources,
                R.drawable.ic_baseline_fiber_manual_record_24,
                null
            )!!
            DrawableCompat.setTint(drawable.mutate(), color)
            setImageDrawable(drawable)
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onClick() {
        super.onClick()

        lateinit var dialog: AlertDialog
        val colors = paletteColors()

        val flexbox = FlexboxLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            flexWrap = FlexWrap.WRAP
            justifyContent = JustifyContent.SPACE_BETWEEN

            // "theme default" swatch: black circle with the theme accent ring
            val defaultView = ImageView(context).apply {
                layoutParams = ViewGroup.LayoutParams(dp2px(56), dp2px(56))
                setImageDrawable(GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.BLACK)
                    setStroke(dp2px(3), context.getColorAttr(android.R.attr.colorPrimary))
                })
                setOnClickListener {
                    persistInt(0)
                    dialog.dismiss()
                    callChangeListener(0)
                }
            }
            addView(defaultView)

            for ((i, color) in colors.withIndex()) {
                val view = getImageViewAtColor(color, 56, 0).apply {
                    setOnClickListener {
                        persistInt(i + 1)
                        dialog.dismiss()
                        callChangeListener(i + 1)
                    }
                }
                addView(view)
            }
        }

        val scrollView = NestedScrollView(context).apply {
            setPadding(dp2px(16), dp2px(16), dp2px(16), 0)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(flexbox)
        }

        dialog = MaterialAlertDialogBuilder(context).setTitle(title)
            .setView(scrollView)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

}
