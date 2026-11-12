package com.notia.mobile.ui

import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import com.notia.mobile.MainActivity
import com.notia.mobile.R
import com.notia.mobile.core.Note
import com.notia.mobile.core.Store
import com.notia.mobile.core.fmtDate

/** Hosts the five main screens and the floating pill navigation. */
class Tabs(private val a: MainActivity, start: Int) : Screen() {
    private val vault = VaultScreen(a)
    private val screens: List<Screen> = listOf(NotesScreen(a), LibraryScreen(a), TasksScreen(a), vault, SettingsScreen(a))
    private val icons = intArrayOf(R.drawable.ic_doc, R.drawable.ic_book, R.drawable.ic_box_on, R.drawable.ic_lock, R.drawable.ic_settings)
    private val names get() = listOf(L("Notes", "Notes"), L("Library", "Bibliothèque"), L("Tasks", "Tâches"), L("Vault", "Coffre"), L("Settings", "Réglages"))
    private val content = FrameLayout(a)
    private val bar = a.hbox()
    private val frame = FrameLayout(a)
    private var cur = -1
    private var shown = false
    private var initial = start
    private val kb = ViewTreeObserver.OnGlobalLayoutListener {
        val r = Rect(); frame.getWindowVisibleDisplayFrame(r)
        val full = frame.rootView.height
        bar.visibility = if (full - r.bottom > full * 0.15) View.GONE else View.VISIBLE
    }

    init {
        frame.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))
        bar.background = a.rounded(Th.p.nav, 30f)
        bar.elevation = a.dpf(10f)
        bar.setPadding(a.dp(8), 0, a.dp(8), 0)
        for (i in screens.indices) {
            val b = FrameLayout(a)
            b.contentDescription = names[i]
            b.isClickable = true
            b.setOnClickListener { select(i) }
            b.addView(android.widget.ImageView(a).apply { setImageResource(icons[i]) }, FrameLayout.LayoutParams(a.dp(24), a.dp(24), Gravity.CENTER))
            bar.put(b, 0, a.dp(56), 1f)
        }
        frame.addView(bar, FrameLayout.LayoutParams(MATCH, a.dp(58), Gravity.BOTTOM).also { it.setMargins(a.dp(16), 0, a.dp(16), a.dp(12)) })
        select(initial)
    }

    fun select(i: Int) {
        if (i == cur) return
        if (cur >= 0 && shown) screens[cur].onHide()
        if (cur == 3) a.setSecure(false)
        cur = i
        content.removeAllViews()
        val v = screens[i].view()
        (v.parent as? ViewGroup)?.removeView(v)
        content.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        for (k in 0 until bar.childCount) ((bar.getChildAt(k) as FrameLayout).getChildAt(0) as android.widget.ImageView).setColorFilter(if (k == i) Th.p.accent else 0xFF8F8AA8.toInt())
        if (i == 3) a.setSecure(true)
        if (shown) screens[i].onShow()
    }

    fun lockVault() = vault.lock()

    override fun view(): View = frame
    override fun onShow() { shown = true; frame.viewTreeObserver.addOnGlobalLayoutListener(kb); screens[cur].onShow() }
    override fun onHide() { shown = false; if (frame.viewTreeObserver.isAlive) frame.viewTreeObserver.removeOnGlobalLayoutListener(kb); screens[cur].onHide() }
    override fun onDestroy() { screens.forEach { it.onDestroy() } }
    override fun onBack(): Boolean { if (cur != 0) { select(0); return true }; return false }
}

class NotesScreen(private val a: MainActivity) : Screen() {
    private val root = a.vbox()
    private val list = ListView(a)
    private val chips = a.hbox()
    private val empty = a.text("", 15f, Th.sub)
    private var query = ""
    private var cat: String? = null
    private var shown: List<Note> = emptyList()
    private val onData = { refresh() }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(p: Int) = shown[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
            val n = shown[p]
            val color = Store.cats.firstOrNull { it.id == n.cat }?.color?.let { android.graphics.Color.parseColor(it) } ?: Th.line
            val card = a.hbox {
                background = a.pressable(Th.card, 20f, Th.line); clipRound(20f)
                put(View(a).apply { setBackgroundColor(color) }, a.dp(5), MATCH)
                put(a.vbox {
                    setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(14))
                    put(a.text(n.title.ifBlank { L("Untitled", "Sans titre") }, 17f, Th.ink, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                    if (n.text.isNotEmpty()) put(a.text(n.text.take(200), 14f, Th.sub).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setLineSpacing(0f, 1.15f) }, MATCH, WRAP, 0f, 0, 6, 0, 0)
                    put(a.text(fmtDate(n.modified, true), 11f, Th.sub), MATCH, WRAP, 0f, 0, 8, 0, 0)
                }, 0, WRAP, 1f)
                isClickable = true
                setOnClickListener { a.push(EditorScreen(a, n.id)) }
                setOnLongClickListener { a.confirm(L("Delete this note?", "Supprimer cette note ?"), null, L("Delete", "Supprimer")) { Store.removeNote(n.id); Store.changed() }; true }
            }
            return FrameLayout(a).apply { setPadding(0, a.dp(5), 0, a.dp(5)); addView(card, FrameLayout.LayoutParams(MATCH, WRAP)) }
        }
    }

    init {
        root.setBackgroundColor(Th.bg)
        val head = a.hbox { setPadding(a.dp(20), a.dp(18), a.dp(8), 0) }
        head.put(a.text(L("Notes", "Notes"), 30f, Th.ink, true), 0, WRAP, 1f)
        head.put(a.iconBtn(R.drawable.ic_swap, L("Transfer", "Transfert"), Th.ink) { a.push(TransferScreen(a)) })
        root.put(head)
        val search = a.field(L("Search", "Rechercher"))
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { query = s?.toString() ?: ""; refresh() }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
        root.put(search, MATCH, WRAP, 0f, 20, 12, 20, 0)
        val hs = a.hscroll { setPadding(a.dp(20), 0, a.dp(20), 0) }
        (hs.getChildAt(0) as LinearLayout).let { row -> chips.let { c -> hs.removeAllViews(); hs.addView(c, FrameLayout.LayoutParams(WRAP, WRAP)); } }
        root.put(hs, MATCH, WRAP, 0f, 0, 12, 0, 6)
        list.divider = null; list.dividerHeight = 0; list.clipToPadding = false
        list.setPadding(a.dp(20), a.dp(4), a.dp(20), a.dp(170)); list.selector = android.graphics.drawable.ColorDrawable(0)
        list.adapter = adapter
        val body = FrameLayout(a)
        body.addView(list, FrameLayout.LayoutParams(MATCH, MATCH))
        body.addView(empty, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        val fab = FrameLayout(a).apply {
            background = a.rounded(Th.accent, 28f); elevation = a.dpf(8f); isClickable = true; contentDescription = L("New note", "Nouvelle note")
            addView(a.icon(R.drawable.ic_add, Th.accentInk, 26), FrameLayout.LayoutParams(a.dp(26), a.dp(26), Gravity.CENTER))
            setOnClickListener { a.push(EditorScreen(a, Store.addNote(cat).id)) }
        }
        body.addView(fab, FrameLayout.LayoutParams(a.dp(56), a.dp(56), Gravity.BOTTOM or Gravity.END).also { it.setMargins(0, 0, a.dp(24), a.dp(92)) })
        root.put(body, MATCH, 0, 1f)
    }

    private fun refresh() {
        val q = query.trim().lowercase()
        shown = Store.notes.filter { (cat == null || it.cat == cat) && (q.isEmpty() || it.title.lowercase().contains(q) || it.text.lowercase().contains(q)) }
        adapter.notifyDataSetChanged()
        empty.text = if (Store.notes.isEmpty()) L("No notes yet.\nTap + to write your first one.", "Aucune note.\nAppuyez sur + pour écrire la première.") else if (shown.isEmpty()) L("Nothing found.", "Aucun résultat.") else ""
        empty.gravity = Gravity.CENTER
        chips.removeAllViews()
        chips.put(a.chip(L("All", "Toutes"), cat == null, 0, { cat = null; refresh() }), WRAP, WRAP, 0f, 0, 0, 8, 0)
        for (c in Store.cats) chips.put(a.chip(c.name, cat == c.id, android.graphics.Color.parseColor(c.color), { cat = c.id; refresh() }, {
            a.confirm(L("Delete category “${c.name}”?", "Supprimer la catégorie « ${c.name} » ?"), L("Notes stay, they just lose this category.", "Les notes sont conservées, sans catégorie."), L("Delete", "Supprimer")) { if (cat == c.id) cat = null; Store.removeCat(c.id); refresh() }
        }), WRAP, WRAP, 0f, 0, 0, 8, 0)
        chips.put(a.chip("+", false, 0, { addCat() }), WRAP, WRAP)
    }

    private fun addCat() {
        val s = Sheet(a, L("New category", "Nouvelle catégorie"))
        val f = a.field(L("Name", "Nom")); f.filters = arrayOf(android.text.InputFilter.LengthFilter(24))
        s.body.put(f, MATCH, WRAP, 0f, 0, 0, 0, 14)
        s.body.put(a.pill(L("Create", "Créer")) { val n = f.text.toString().trim(); if (n.isNotEmpty() && Store.cats.none { it.name.equals(n, true) }) { Store.addCat(n); refresh() }; s.dismiss() })
        s.show(); f.requestFocus()
    }

    override fun view(): View = root
    override fun onShow() { Store.listen(onData); refresh() }
    override fun onHide() { Store.unlisten(onData) }
}

class SettingsScreen(private val a: MainActivity) : Screen() {
    private val root = a.vbox { setBackgroundColor(Th.bg) }

    init {
        val sv = a.vscroll {
            setPadding(a.dp(20), a.dp(18), a.dp(20), a.dp(120))
            put(a.text(L("Settings", "Réglages"), 30f, Th.ink, true), MATCH, WRAP, 0f, 0, 0, 0, 18)
            put(card(L("Dark mode", "Mode sombre"), null, Switch(a).apply {
                isChecked = Store.dark
                thumbTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt()); trackTintList = ColorStateList.valueOf(Th.accent)
                setOnCheckedChangeListener { _, c -> Store.dark = c; Store.saveState(); Store.flush(); a.start(4) }
            }), MATCH, WRAP, 0f, 0, 0, 0, 10)
            put(a.vbox {
                background = a.rounded(Th.card, 20f, Th.line); setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(14))
                put(a.text(L("Language", "Langue"), 16f, Th.ink, true), MATCH, WRAP, 0f, 0, 0, 0, 10)
                put(a.hbox {
                    put(a.chip("English", Store.lang == "en", 0, { setLang("en") }), WRAP, WRAP, 0f, 0, 0, 8, 0)
                    put(a.chip("Français", Store.lang == "fr", 0, { setLang("fr") }))
                })
            }, MATCH, WRAP, 0f, 0, 0, 0, 10)
            put(a.vbox {
                background = a.rounded(Th.card, 20f, Th.line); setPadding(a.dp(16), a.dp(16), a.dp(16), a.dp(16))
                put(a.text("Notia", 20f, Th.ink, true))
                put(a.text(L("Version ", "Version ") + com.notia.mobile.BuildConfig.VERSION_NAME, 13f, Th.sub), MATCH, WRAP, 0f, 0, 4, 0, 10)
                put(a.text(L("Your notes, tasks and books stay on this phone. No account, no tracking, no ads. Works fully offline.", "Vos notes, tâches et livres restent sur ce téléphone. Aucun compte, aucun suivi, aucune publicité. Fonctionne entièrement hors ligne."), 14f, Th.sub).apply { setLineSpacing(0f, 1.2f) })
                put(a.text(L("Made by Song Richard", "Créé par Song Richard"), 14f, Th.accent, true).apply {
                    isClickable = true
                    setOnClickListener { try { a.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://srichard.site"))) } catch (e: Exception) {} }
                }, WRAP, WRAP, 0f, 0, 12, 0, 0)
            }, MATCH, WRAP)
        }
        root.put(sv, MATCH, 0, 1f)
    }

    private fun setLang(l: String) { if (Store.lang == l) return; Store.lang = l; Store.saveState(); Store.flush(); com.notia.mobile.sys.Reminders.channels(a); a.start(4) }

    private fun card(title: String, sub: String?, trailing: View): View = a.hbox {
        background = a.rounded(Th.card, 20f, Th.line); setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(14))
        put(a.text(title, 16f, Th.ink, true), 0, WRAP, 1f)
        put(trailing, WRAP, WRAP)
    }

    override fun view(): View = root
}
