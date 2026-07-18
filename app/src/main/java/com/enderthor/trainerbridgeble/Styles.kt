package com.enderthor.trainerbridgeble

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Shared card-based UI palette + view helpers (same look as the ANT app), used by both activities. */
object Palette {
    const val PAGE_BG = 0xFFEDEFF2.toInt()
    const val CARD_BG = 0xFFFFFFFF.toInt()
    val ACCENT = 0xFF1565C0.toInt()
    const val TEXT = 0xFF1A1A1A.toInt()
    const val MUTED = 0xFF616161.toInt()
    const val OK = 0xFF2E7D32.toInt()
    const val DANGER = 0xFFC62828.toInt()
}

fun Activity.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
fun Activity.rounded(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(12).toFloat() }

fun Activity.card(title: String): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL; background = rounded(Palette.CARD_BG); setPadding(dp(18), dp(14), dp(18), dp(16))
    val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT); lp.setMargins(0, 0, 0, dp(14)); layoutParams = lp
    if (title.isNotEmpty()) addView(TextView(this@card).apply {
        text = title; textSize = 16f; setTextColor(Palette.ACCENT); typeface = Typeface.DEFAULT_BOLD; setPadding(0, 0, 0, dp(8))
    })
}

fun Activity.bodyText(text: String, size: Float = 14f, color: Int = Palette.MUTED) = TextView(this).apply {
    this.text = text; textSize = size; setTextColor(color); setPadding(0, dp(3), 0, dp(3))
}

fun Activity.title(text: String) = TextView(this).apply {
    this.text = text; textSize = 22f; setTextColor(Palette.TEXT); typeface = Typeface.DEFAULT_BOLD; setPadding(dp(4), 0, 0, dp(12))
}

fun Activity.intField(value: String) = EditText(this).apply {
    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED; setText(value); setTextColor(Palette.TEXT)
}

fun Activity.textField(value: String) = EditText(this).apply {
    inputType = InputType.TYPE_CLASS_TEXT; setText(value); setTextColor(Palette.TEXT)
}

fun Activity.check(text: String, on: Boolean) = CheckBox(this).apply {
    this.text = text; isChecked = on; setTextColor(Palette.TEXT); setPadding(0, dp(6), 0, 0)
}

fun Activity.accentButton(text: String, onClick: () -> Unit) = Button(this).apply {
    this.text = text; setTextColor(0xFFFFFFFF.toInt()); background = rounded(Palette.ACCENT); textSize = 16f; setPadding(dp(16), dp(12), dp(16), dp(12))
    val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT); lp.setMargins(0, dp(10), 0, 0); layoutParams = lp
    setOnClickListener { onClick() }
}

fun Activity.plainButton(text: String, onClick: () -> Unit) = Button(this).apply {
    this.text = text; setTextColor(Palette.ACCENT); background = rounded(Palette.PAGE_BG); setPadding(dp(14), dp(10), dp(14), dp(10))
    val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT); lp.setMargins(0, dp(8), 0, 0); layoutParams = lp
    setOnClickListener { onClick() }
}

fun Activity.tileRow() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(8)) }

/** A value/label tile inside a [tileRow]; returns the value TextView to update. */
fun Activity.tile(row: LinearLayout, label: String, valueColor: Int): TextView {
    val col = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = rounded(Palette.PAGE_BG); setPadding(dp(6), dp(8), dp(6), dp(8)); gravity = Gravity.CENTER
        val lp = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f); lp.setMargins(dp(3), 0, dp(3), 0); layoutParams = lp
    }
    val value = TextView(this).apply { text = "—"; textSize = 22f; setTextColor(valueColor); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER }
    col.addView(value)
    col.addView(TextView(this).apply { text = label; textSize = 12f; setTextColor(Palette.MUTED); gravity = Gravity.CENTER })
    row.addView(col); return value
}
