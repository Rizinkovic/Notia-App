package com.notia.mobile.ui

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.notia.mobile.core.Store

class Palette(val bg: Int, val card: Int, val ink: Int, val sub: Int, val line: Int, val accent: Int, val accentInk: Int, val nav: Int, val field: Int, val danger: Int)

object Th {
    private val dark = Palette(0xFF14121C.toInt(), 0xFF1E1B2B.toInt(), 0xFFF4F2FA.toInt(), 0xFF9A96B0.toInt(), 0xFF2E2A42.toInt(), 0xFF8B6CF6.toInt(), 0xFFFFFFFF.toInt(), 0xFF0B0A10.toInt(), 0xFF252238.toInt(), 0xFFFF6B7A.toInt())
    private val light = Palette(0xFFF6F4FC.toInt(), 0xFFFFFFFF.toInt(), 0xFF1B1830.toInt(), 0xFF6E6A86.toInt(), 0xFFE4E0F2.toInt(), 0xFF6D4AE8.toInt(), 0xFFFFFFFF.toInt(), 0xFF1B1830.toInt(), 0xFFEEEAF9.toInt(), 0xFFD93A4D.toInt())
    val p: Palette get() = if (Store.dark) dark else light
    val bg get() = p.bg; val card get() = p.card; val ink get() = p.ink; val sub get() = p.sub; val line get() = p.line
    val accent get() = p.accent; val accentInk get() = p.accentInk; val field get() = p.field; val danger get() = p.danger
}

fun L(en: String, fr: String) = if (Store.lang == "fr") fr else en

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density
fun View.dp(v: Int) = context.dp(v)

fun Context.vbox(f: LinearLayout.() -> Unit = {}) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; f() }
fun Context.hbox(f: LinearLayout.() -> Unit = {}) = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; f() }

fun LinearLayout.put(v: View, w: Int = MATCH, h: Int = WRAP, weight: Float = 0f, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0): View {
    addView(v, LinearLayout.LayoutParams(w, h, weight).also { it.setMargins(dp(l), dp(t), dp(r), dp(b)) }); return v
}
fun FrameLayout.put(v: View, w: Int = MATCH, h: Int = MATCH, gravity: Int = Gravity.TOP or Gravity.START, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0): View {
    addView(v, FrameLayout.LayoutParams(w, h, gravity).also { it.setMargins(dp(l), dp(t), dp(r), dp(b)) }); return v
}

fun Context.rounded(color: Int, r: Float, stroke: Int = 0, strokeDp: Float = 1f): GradientDrawable = GradientDrawable().apply {
    setColor(color); cornerRadius = dpf(r); if (stroke != 0) setStroke(Math.max(1, dpf(strokeDp).toInt()), stroke)
}

/** Rounded background with a ripple that respects the corners. */
fun Context.pressable(color: Int, r: Float, stroke: Int = 0): RippleDrawable {
    val mask = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dpf(r) }
    return RippleDrawable(ColorStateList.valueOf(0x33808080), rounded(color, r, stroke), mask)
}

fun View.clipRound(r: Float) {
    val px = context.dpf(r)
    outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, px) } }
    clipToOutline = true
}

fun Context.text(s: CharSequence, size: Float = 15f, color: Int = Th.ink, bold: Boolean = false) = TextView(this).apply {
    text = s; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD; includeFontPadding = false
}

fun Context.icon(res: Int, color: Int = Th.ink, size: Int = 22): ImageView = ImageView(this).apply {
    setImageResource(res); setColorFilter(color); scaleType = ImageView.ScaleType.FIT_CENTER
    layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
}

/** Square tappable icon (44dp target). */
fun Context.iconBtn(res: Int, desc: String, color: Int = Th.ink, size: Int = 22, onClick: () -> Unit): FrameLayout = FrameLayout(this).apply {
    contentDescription = desc
    background = RippleDrawable(ColorStateList.valueOf(0x33808080), null, GradientDrawable().apply { setColor(Color.WHITE); shape = GradientDrawable.OVAL })
    addView(ImageView(context).apply { setImageResource(res); setColorFilter(color); scaleType = ImageView.ScaleType.FIT_CENTER }, FrameLayout.LayoutParams(dp(size), dp(size), Gravity.CENTER))
    layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
    isClickable = true; setOnClickListener { onClick() }
}

fun Context.pill(label: String, filled: Boolean = true, danger: Boolean = false, onClick: () -> Unit): TextView = text(label, 15f, if (filled) Th.accentInk else Th.ink, true).apply {
    gravity = Gravity.CENTER
    val c = if (danger) Th.danger else Th.accent
    background = if (filled) pressable(c, 23f) else pressable(Th.field, 23f)
    setPadding(dp(20), 0, dp(20), 0); minimumHeight = dp(46)
    isClickable = true; setOnClickListener { onClick() }
}

fun Context.chip(label: String, selected: Boolean, dot: Int = 0, onClick: () -> Unit, onLong: (() -> Unit)? = null): LinearLayout = hbox {
    background = pressable(if (selected) Th.accent else Th.card, 17f, if (selected) 0 else Th.line)
    setPadding(dp(14), 0, dp(14), 0); minimumHeight = dp(34)
    if (dot != 0) put(View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(dot) } }, dp(8), dp(8), 0f, 0, 0, 6, 0)
    put(text(label, 13f, if (selected) Th.accentInk else Th.ink, true), WRAP, WRAP)
    isClickable = true; setOnClickListener { onClick() }
    if (onLong != null) setOnLongClickListener { onLong(); true }
}

fun Context.field(hint: String, single: Boolean = true, minLines: Int = 1): EditText = EditText(this).apply {
    setHint(hint); setHintTextColor(Th.sub); setTextColor(Th.ink); textSize = 15f
    background = rounded(Th.field, 14f); setPadding(dp(14), dp(12), dp(14), dp(12))
    if (single) { setSingleLine(true); imeOptions = EditorInfo.IME_ACTION_DONE } else { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; this.minLines = minLines; gravity = Gravity.TOP or Gravity.START }
}

fun Context.hscroll(f: LinearLayout.() -> Unit): HorizontalScrollView = HorizontalScrollView(this).apply {
    isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
    addView(hbox { f() }, FrameLayout.LayoutParams(WRAP, WRAP))
}

fun Context.vscroll(f: LinearLayout.() -> Unit): ScrollView = ScrollView(this).apply {
    isVerticalScrollBarEnabled = false
    addView(vbox { f() }, FrameLayout.LayoutParams(MATCH, WRAP))
}

fun Context.toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

fun Context.confirm(title: String, msg: String?, ok: String, danger: Boolean = true, onOk: () -> Unit) {
    val d = AlertDialog.Builder(this, if (Store.dark) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
        .setTitle(title).also { if (msg != null) it.setMessage(msg) }
        .setNegativeButton(L("Cancel", "Annuler"), null).setPositiveButton(ok) { _, _ -> onOk() }.create()
    d.show()
    if (danger) d.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Th.danger)
}

fun Context.info(title: String, msg: String) {
    AlertDialog.Builder(this, if (Store.dark) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
        .setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()
}

/** Bottom sheet with rounded top corners. Content goes into [body]. */
class Sheet(ctx: Context, title: String? = null) : Dialog(ctx) {
    val body: LinearLayout = ctx.vbox { setPadding(ctx.dp(20), ctx.dp(18), ctx.dp(20), ctx.dp(22)) }

    init {
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val outer = ctx.vbox {
            val r = ctx.dpf(24f)
            background = GradientDrawable().apply { setColor(Th.bg); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
        }
        outer.put(View(ctx).apply { background = ctx.rounded(Th.line, 3f) }, ctx.dp(40), ctx.dp(4), 0f, 0, 10, 0, 0).also { (it.layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL }
        if (title != null) body.put(ctx.text(title, 20f, Th.ink, true), MATCH, WRAP, 0f, 0, 0, 0, 12)
        val sv = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; addView(body, FrameLayout.LayoutParams(MATCH, WRAP)) }
        outer.put(sv, MATCH, WRAP)
        setContentView(outer)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(MATCH, WRAP); setGravity(Gravity.BOTTOM)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    fun row(res: Int, label: String, onClick: () -> Unit) {
        val c = context
        body.put(c.hbox {
            background = c.pressable(Th.card, 16f, Th.line)
            setPadding(c.dp(16), 0, c.dp(16), 0); minimumHeight = c.dp(54)
            put(c.icon(res, Th.accent, 22), c.dp(22), c.dp(22), 0f, 0, 0, 14, 0)
            put(c.text(label, 16f, Th.ink, true), 0, WRAP, 1f)
            isClickable = true; setOnClickListener { dismiss(); onClick() }
        }, MATCH, WRAP, 0f, 0, 0, 0, 8)
    }
}

abstract class Screen {
    abstract fun view(): View
    open fun onShow() {}
    open fun onHide() {}
    open fun onDestroy() {}
    /** return true when the back press was consumed */
    open fun onBack(): Boolean = false
}
