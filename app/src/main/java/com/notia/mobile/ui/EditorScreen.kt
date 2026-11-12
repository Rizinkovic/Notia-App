package com.notia.mobile.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.notia.mobile.MainActivity
import com.notia.mobile.R
import com.notia.mobile.core.MathError
import com.notia.mobile.core.Note
import com.notia.mobile.core.Store
import com.notia.mobile.core.chartImg
import com.notia.mobile.core.esc
import com.notia.mobile.core.evalMath
import com.notia.mobile.core.fmtDate
import com.notia.mobile.core.fmtNum
import com.notia.mobile.core.parsePts
import com.notia.mobile.core.tableHtml
import com.notia.mobile.sys.Exporter
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream

fun cssColor(c: Int) = String.format("#%06X", c and 0xFFFFFF)

object Pages {
    fun load(a: MainActivity, name: String, vars: Map<String, String>): String {
        var s = a.assets.open(name).bufferedReader().use { it.readText() }
        for ((k, v) in vars) s = s.replace(k, v)
        return s
    }
    fun nonce(): String { val b = ByteArray(12); java.security.SecureRandom().nextBytes(b); return b.joinToString("") { "%02x".format(it) } }
}

class EditorScreen(private val a: MainActivity, private val id: String) : Screen() {
    private val note: Note? = Store.note(id)
    private val root = a.vbox { setBackgroundColor(Th.bg) }
    private val web = WebView(a)
    private val meta = a.text("", 11f, Th.sub)
    private val chips = a.hbox()
    private var chars = note?.text?.length ?: 0
    private var done = false
    private val title = a.field(L("Title", "Titre")).apply {
        background = null; textSize = 26f; typeface = Typeface.DEFAULT_BOLD; setPadding(a.dp(20), a.dp(4), a.dp(20), a.dp(4))
        setText(note?.title ?: "")
    }

    private inner class Bridge {
        @JavascriptInterface fun ready() { a.post(Runnable {
            val n = note ?: return@Runnable
            web.evaluateJavascript("setContent(" + JSONObject.quote(n.html) + ")", null)
            if (n.title.isBlank() && n.html.isBlank()) { title.requestFocus(); (a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(title, 0) }
        }) }
        @JavascriptInterface fun change(html: String, count: Int) { a.post(Runnable { onText(html, count) }) }
    }

    private fun onText(html: String, count: Int) {
        val n = note ?: return
        if (n.html != html) { n.html = html; Store.touch(n) }
        chars = count; updateMeta()
    }

    private fun updateMeta() {
        val n = note ?: return
        meta.text = "$chars " + L("characters", "caractères") + "  •  " + L("Created", "Créée") + " " + fmtDate(n.created, true) + "  •  " + L("Modified", "Modifiée") + " " + fmtDate(n.modified, true)
    }

    private fun js(s: String) { web.evaluateJavascript(s, null) }

    /** Pulls the freshest HTML out of the page, then continues. */
    private fun sync(next: () -> Unit) {
        web.evaluateJavascript("(function(){return ed.innerHTML})()") { v ->
            try { (JSONTokener(v).nextValue() as? String)?.let { h -> note?.let { n -> if (n.html != h) { n.html = h; Store.touch(n) } } } } catch (e: Exception) {}
            next()
        }
    }

    init {
        val n = note
        // top bar
        val top = a.hbox { setPadding(a.dp(8), a.dp(8), a.dp(8), 0) }
        top.put(a.iconBtn(R.drawable.ic_back, L("Back", "Retour"), Th.ink, 24) { onBack() })
        top.put(View(a), 0, 1, 1f)
        top.put(a.iconBtn(R.drawable.ic_share, L("Share", "Partager"), Th.ink) { share() })
        top.put(a.iconBtn(R.drawable.ic_delete, L("Delete", "Supprimer"), Th.danger) {
            a.confirm(L("Delete this note?", "Supprimer cette note ?"), null, L("Delete", "Supprimer")) { done = true; Store.removeNote(id); a.pop() }
        })
        root.put(top)
        root.put(title, MATCH, WRAP, 0f, 0, 4, 0, 0)
        title.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { val nn = note ?: return; val t = s?.toString() ?: ""; if (nn.title != t) { nn.title = t; Store.touch(nn); updateMeta() } }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
        val hs = a.hscroll { setPadding(a.dp(20), 0, a.dp(20), 0) }
        hs.removeAllViews(); hs.addView(chips, FrameLayout.LayoutParams(WRAP, WRAP))
        root.put(hs, MATCH, WRAP, 0f, 0, 6, 0, 6)
        drawChips()

        // web editor
        web.setBackgroundColor(Th.bg)
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.settings.apply {
            javaScriptEnabled = true; allowFileAccess = false; allowContentAccess = false
            setSupportZoom(false); builtInZoomControls = false; mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        web.addJavascriptInterface(Bridge(), "Notia")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = true
        }
        root.put(web, MATCH, 0, 1f)
        root.put(meta, MATCH, WRAP, 0f, 20, 4, 20, 6)
        updateMeta()

        // toolbars
        val bar = a.vbox { background = a.rounded(Th.card, 0f); setPadding(0, a.dp(4), 0, a.dp(4)) }
        bar.put(a.hscroll {
            setPadding(a.dp(8), 0, a.dp(8), 0)
            put(a.iconBtn(R.drawable.ic_image, L("Image", "Image"), Th.accent) { pickImage() })
            put(a.iconBtn(R.drawable.ic_link, L("Link", "Lien"), Th.accent) { linkSheet() })
            put(a.iconBtn(R.drawable.ic_table, L("Table", "Tableau"), Th.accent) { tableSheet() })
            put(a.iconBtn(R.drawable.ic_calc, L("Formula", "Formule"), Th.accent) { formulaSheet() })
            put(a.iconBtn(R.drawable.ic_chart, L("Chart", "Graphique"), Th.accent) { chartSheet() })
            put(View(a), a.dp(10), 1)
            for (c in listOf(Th.ink, 0xFFE5484D.toInt(), 0xFFF2B33D.toInt(), 0xFF3FBF7F.toInt(), 0xFF3DA5E0.toInt(), 0xFF8B6CF6.toInt())) put(swatch(c, true), a.dp(30), a.dp(30), 0f, 3, 0, 3, 0)
            put(View(a), a.dp(10), 1)
            for (c in listOf(0xFFFFF3A3.toInt(), 0xFFC9F3D5.toInt(), 0xFFCDE6FF.toInt(), 0xFFFFD3E2.toInt(), 0)) put(swatch(c, false), a.dp(30), a.dp(30), 0f, 3, 0, 3, 0)
        })
        bar.put(a.hscroll {
            setPadding(a.dp(8), 0, a.dp(8), 0)
            put(a.iconBtn(R.drawable.ic_undo, L("Undo", "Annuler"), Th.ink) { js("cmd('undo')") })
            put(a.iconBtn(R.drawable.ic_redo, L("Redo", "Rétablir"), Th.ink) { js("cmd('redo')") })
            put(tbtn("H1", Typeface.BOLD) { js("block('h1')") }); put(tbtn("H2", Typeface.BOLD) { js("block('h2')") })
            put(tbtn("B", Typeface.BOLD) { js("cmd('bold')") }); put(tbtn("I", Typeface.ITALIC) { js("cmd('italic')") })
            put(tbtn("U", Typeface.NORMAL, true) { js("cmd('underline')") })
            put(a.iconBtn(R.drawable.ic_bullets, L("Bullets", "Puces"), Th.ink) { js("cmd('insertUnorderedList')") })
            put(a.iconBtn(R.drawable.ic_numbered, L("Numbered list", "Liste numérotée"), Th.ink) { js("cmd('insertOrderedList')") })
            put(a.iconBtn(R.drawable.ic_code, L("Code", "Code"), Th.ink) { js("block('pre')") })
            put(a.iconBtn(R.drawable.ic_hr, L("Line", "Ligne"), Th.ink) { js("cmd('insertHorizontalRule')") })
        })
        root.put(bar)

        val html = Pages.load(a, "editor.html", mapOf("%NONCE%" to Pages.nonce(), "%BG%" to cssColor(Th.bg), "%INK%" to cssColor(Th.ink), "%SUB%" to cssColor(Th.sub),
            "%ACCENT%" to cssColor(Th.accent), "%PH%" to esc(L("Start writing…", "Commencez à écrire…"))))
        web.loadDataWithBaseURL("https://notia.local/", html, "text/html", "utf-8", null)
        if (n == null) a.post(Runnable { a.pop() })
    }

    private fun tbtn(label: String, style: Int, underline: Boolean = false, onClick: () -> Unit): TextView = a.text(label, 16f, Th.ink, true).apply {
        setTypeface(Typeface.DEFAULT, if (style == Typeface.NORMAL) Typeface.BOLD else style)
        if (underline) paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
        gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(a.dp(44), a.dp(44)); isClickable = true; contentDescription = label
        setOnClickListener { onClick() }
    }

    private fun swatch(color: Int, ink: Boolean): View = View(a).apply {
        background = GradientDrawable().apply {
            shape = if (ink) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
            if (!ink) cornerRadius = a.dpf(8f)
            if (color == 0) { setColor(Color.TRANSPARENT); setStroke(a.dp(2), Th.sub) } else { setColor(color); setStroke(a.dp(1), Th.line) }
        }
        isClickable = true
        contentDescription = if (ink) L("Text colour", "Couleur du texte") else L("Highlight", "Surlignage")
        setOnClickListener {
            if (ink) js("cmd('foreColor','${cssColor(color)}')") else js("cmd('hiliteColor','${if (color == 0) "transparent" else cssColor(color)}')")
        }
    }

    private fun drawChips() {
        chips.removeAllViews()
        val n = note ?: return
        for (c in Store.cats) chips.put(a.chip(c.name, n.cat == c.id, Color.parseColor(c.color), {
            n.cat = if (n.cat == c.id) null else c.id; Store.touch(n); drawChips()
        }), WRAP, WRAP, 0f, 0, 0, 8, 0)
    }

    // ---- inserts ----
    private fun ins(html: String) { js("ins(" + JSONObject.quote(html) + ")") }

    private fun pickImage() {
        a.pick(arrayOf("image/*")) { uri ->
            if (uri == null) return@pick
            a.toast(L("Adding image…", "Ajout de l’image…"))
            Thread {
                val data = try { imageDataUri(a, uri) } catch (e: Throwable) { null }
                a.post(Runnable { if (data == null) a.toast(L("Could not add this image.", "Impossible d’ajouter cette image.")) else ins("<img src=\"$data\" style=\"max-width:100%;height:auto;border-radius:8px\"/><div><br></div>") })
            }.start()
        }
    }

    private fun linkSheet() {
        val s = Sheet(a, L("Insert link", "Insérer un lien"))
        val t = a.field(L("Text (optional)", "Texte (facultatif)")); val u = a.field("https://…").apply { inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI }
        s.body.put(t, MATCH, WRAP, 0f, 0, 0, 0, 10); s.body.put(u, MATCH, WRAP, 0f, 0, 0, 0, 14)
        s.body.put(a.pill(L("Insert", "Insérer")) {
            var url = u.text.toString().trim()
            if (url.isEmpty()) return@pill
            if (!Regex("^(https?|mailto|tel):", RegexOption.IGNORE_CASE).containsMatchIn(url)) url = if (url.contains("@") && !url.contains("/")) "mailto:$url" else "https://$url"
            ins("<a href=\"${esc(url)}\">${esc(t.text.toString().trim().ifEmpty { url })}</a>&nbsp;"); s.dismiss()
        })
        s.show(); u.requestFocus()
    }

    private fun stepper(label: String, init: Int, min: Int, max: Int, out: IntArray, idx: Int): View {
        val v = a.text(init.toString(), 18f, Th.ink, true).apply { gravity = Gravity.CENTER }
        out[idx] = init
        fun step(d: Int) { out[idx] = Math.max(min, Math.min(max, out[idx] + d)); v.text = out[idx].toString() }
        return a.hbox {
            put(a.text(label, 16f, Th.ink, true), 0, WRAP, 1f)
            put(a.pill("−", false) { step(-1) }.apply { minimumWidth = a.dp(52); setPadding(0, 0, 0, 0) }, a.dp(52), a.dp(44))
            put(v, a.dp(48), WRAP)
            put(a.pill("+", false) { step(1) }.apply { minimumWidth = a.dp(52); setPadding(0, 0, 0, 0) }, a.dp(52), a.dp(44))
        }
    }

    private fun tableSheet() {
        val s = Sheet(a, L("Insert table", "Insérer un tableau")); val v = IntArray(2)
        s.body.put(stepper(L("Rows", "Lignes"), 3, 1, 12, v, 0), MATCH, WRAP, 0f, 0, 0, 0, 10)
        s.body.put(stepper(L("Columns", "Colonnes"), 3, 1, 6, v, 1), MATCH, WRAP, 0f, 0, 0, 0, 16)
        s.body.put(a.pill(L("Insert", "Insérer")) { ins(tableHtml(v[0], v[1])); s.dismiss() }); s.show()
    }

    private fun formulaSheet() {
        val s = Sheet(a, L("Formula", "Formule"))
        val f = a.field("(12 + 8) × 3 / sqrt(16)").apply { inputType = android.text.InputType.TYPE_CLASS_TEXT }
        val res = a.text("", 20f, Th.accent, true)
        s.body.put(f, MATCH, WRAP, 0f, 0, 0, 0, 10)
        s.body.put(a.text(L("Supports + − × ÷ % ^ ( ) sqrt sin cos tan log ln abs round pi e", "Accepte + − × ÷ % ^ ( ) sqrt sin cos tan log ln abs round pi e"), 12f, Th.sub), MATCH, WRAP, 0f, 0, 0, 0, 10)
        s.body.put(res, MATCH, WRAP, 0f, 0, 0, 0, 14)
        var value: String? = null
        fun calc() {
            val e = f.text.toString()
            if (e.isBlank()) { res.text = ""; value = null; return }
            try { value = fmtNum(evalMath(e)); res.text = "= $value"; res.setTextColor(Th.accent) }
            catch (x: MathError) { value = null; res.text = x.message ?: "?"; res.setTextColor(Th.danger) }
            catch (x: Exception) { value = null; res.text = "?"; res.setTextColor(Th.danger) }
        }
        f.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(x: Editable?) { calc() }
            override fun beforeTextChanged(x: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(x: CharSequence?, st: Int, b: Int, c: Int) {}
        })
        s.body.put(a.pill(L("Insert", "Insérer")) { val v = value ?: return@pill; ins(esc(f.text.toString().trim()) + " = <b>" + esc(v) + "</b>&nbsp;"); s.dismiss() })
        s.show(); f.requestFocus()
    }

    private fun chartSheet() {
        val s = Sheet(a, L("Chart", "Graphique")); var type = "bar"
        val types = a.hbox()
        fun drawTypes() {
            types.removeAllViews()
            for ((k, lbl) in listOf("bar" to L("Bars", "Barres"), "line" to L("Line", "Courbe"), "pie" to L("Pie", "Camembert")))
                types.put(a.chip(lbl, type == k, 0, { type = k; drawTypes() }), WRAP, WRAP, 0f, 0, 0, 8, 0)
        }
        drawTypes()
        val t = a.field(L("Title", "Titre")).apply { filters = arrayOf(android.text.InputFilter.LengthFilter(40)) }
        val d = a.field(L("January: 12\nFebruary: 19\nMarch: 8", "Janvier : 12\nFévrier : 19\nMars : 8"), false, 5)
        val err = a.text("", 13f, Th.danger)
        s.body.put(types, MATCH, WRAP, 0f, 0, 0, 0, 12); s.body.put(t, MATCH, WRAP, 0f, 0, 0, 0, 10)
        s.body.put(d, MATCH, WRAP, 0f, 0, 0, 0, 6)
        s.body.put(a.text(L("One per line: Label: value (max 12)", "Une par ligne : Libellé : valeur (12 max)"), 12f, Th.sub), MATCH, WRAP, 0f, 0, 0, 0, 10)
        s.body.put(err, MATCH, WRAP, 0f, 0, 0, 0, 6)
        s.body.put(a.pill(L("Insert", "Insérer")) {
            val pts = parsePts(d.text.toString()).take(12)
            if (pts.isEmpty()) { err.text = L("Add at least one line like “Label: 10”.", "Ajoutez au moins une ligne comme « Libellé : 10 »."); return@pill }
            ins(chartImg(type, t.text.toString().trim().ifEmpty { L("Chart", "Graphique") }, pts)); s.dismiss()
        }); s.show()
    }

    // ---- share ----
    private fun share() {
        val n = note ?: return
        sync {
            val s = Sheet(a, L("Share note", "Partager la note"))
            s.row(R.drawable.ic_doc, L("PDF", "PDF")) { export(true) }
            s.row(R.drawable.ic_doc, L("Word (DOCX)", "Word (DOCX)")) { export(false) }
            s.row(R.drawable.ic_chat, L("Text (email, WhatsApp, Telegram…)", "Texte (e-mail, WhatsApp, Telegram…)")) { Exporter.shareText(a, n) }
            s.row(R.drawable.ic_wifi, L("Send over Wi-Fi", "Envoyer par Wi-Fi")) { a.push(TransferScreen(a, n.id)) }
            s.show()
        }
    }

    private fun export(pdf: Boolean) {
        val n = note ?: return
        a.toast(L("Preparing file…", "Préparation du fichier…"))
        Thread {
            try {
                val f = Exporter.fileFor(a, n, pdf)
                a.post(Runnable { Exporter.shareFile(a, f, if (pdf) Exporter.MIME_PDF else Exporter.MIME_DOCX) })
            } catch (e: Throwable) {
                a.post(Runnable { if (pdf) Exporter.printNote(a, n) else a.toast(L("Export failed.", "L’export a échoué.")) })
            }
        }.start()
    }

    override fun view(): View = root
    override fun onHide() { js("flush()") }

    override fun onBack(): Boolean {
        if (done) return true
        done = true
        sync { a.pop() }
        return true
    }

    override fun onDestroy() {
        val n = note
        if (n != null && n.title.isBlank() && n.text.isBlank() && !n.media && Store.note(n.id) != null) { Store.removeNote(n.id) }
        (web.parent as? android.view.ViewGroup)?.removeView(web)
        web.removeJavascriptInterface("Notia")
        web.destroy()
        Store.flush()
        Store.changed()
    }
}

/** Reads an image, fixes its rotation, shrinks it (max 1280px) and returns a data: URI. */
fun imageDataUri(a: MainActivity, uri: Uri): String {
    val cr = a.contentResolver
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    cr.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, o) }
    var s = 1; while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= 1280) s *= 2
    var bmp = cr.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) } ?: throw java.io.IOException("decode")
    val rot = try { cr.openInputStream(uri)!!.use { android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1) } } catch (e: Exception) { 1 }
    val deg = when (rot) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
    if (deg != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
    val alpha = bmp.hasAlpha()
    val bos = ByteArrayOutputStream()
    bmp.compress(if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 78, bos)
    return "data:image/" + (if (alpha) "png" else "jpeg") + ";base64," + android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
}
