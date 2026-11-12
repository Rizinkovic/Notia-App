package com.notia.mobile.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.content.res.ColorStateList
import com.notia.mobile.MainActivity
import com.notia.mobile.R
import com.notia.mobile.core.Store
import com.notia.mobile.core.Task
import com.notia.mobile.core.fmtDate
import com.notia.mobile.core.newId
import com.notia.mobile.net.Net
import com.notia.mobile.sys.Qr
import com.notia.mobile.sys.Reminders
import com.notia.mobile.sys.Vault
import com.notia.mobile.sys.VaultEntry
import java.io.File
import java.util.Calendar
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private fun dialogTheme() = if (Store.dark) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert

class TasksScreen(private val a: MainActivity) : Screen() {
    private val root = a.vbox { setBackgroundColor(Th.bg) }
    private val listBox = a.vbox()
    private val warn = a.vbox()
    private val input = a.field(L("New task", "Nouvelle tâche"))
    private val dueChips = a.hbox()
    private var due: Long? = null
    private val onData = { render() }

    init {
        val sv = a.vscroll {
            setPadding(a.dp(20), a.dp(18), a.dp(20), a.dp(120))
            put(a.text(L("Tasks", "Tâches"), 30f, Th.ink, true), MATCH, WRAP, 0f, 0, 0, 0, 14)
            put(warn)
            put(a.hbox {
                put(input, 0, WRAP, 1f)
                put(a.pill(L("Add", "Ajouter")) { add() }, WRAP, a.dp(48), 0f, 8, 0, 0, 0)
            })
            val hs = a.hscroll {}
            hs.removeAllViews(); hs.addView(dueChips, FrameLayout.LayoutParams(WRAP, WRAP))
            put(hs, MATCH, WRAP, 0f, 0, 10, 0, 14)
            put(listBox)
        }
        input.setOnEditorActionListener { _, _, _ -> add(); true }
        root.put(sv, MATCH, 0, 1f)
    }

    private fun plusHours(h: Int) = System.currentTimeMillis() + h * 3_600_000L
    private fun at(hour: Int, addDays: Int): Long = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, addDays); set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    private fun drawDue() {
        dueChips.removeAllViews()
        fun c(label: String, v: Long?, sel: Boolean) = dueChips.put(a.chip(label, sel, 0, { due = v; drawDue() }), WRAP, WRAP, 0f, 0, 0, 8, 0)
        c(L("No reminder", "Sans rappel"), null, due == null)
        val tonight = at(20, 0).let { if (it <= System.currentTimeMillis() + 60_000) at(20, 1) else it }
        c(L("In 1 hour", "Dans 1 h"), plusHours(1), false)
        c(L("Tonight 20:00", "Ce soir 20:00"), tonight, false)
        c(L("Tomorrow 09:00", "Demain 09:00"), at(9, 1), false)
        dueChips.put(a.chip(if (due != null) "⏰ " + fmtDate(due!!, true) else L("Pick date & time", "Choisir date et heure"), due != null, 0, { pick() }), WRAP, WRAP)
    }

    private fun pick() {
        val c = Calendar.getInstance().apply { if (due != null) timeInMillis = due!! }
        DatePickerDialog(a, dialogTheme(), { _, y, m, d ->
            TimePickerDialog(a, dialogTheme(), { _, h, mi ->
                val t = Calendar.getInstance().apply { set(y, m, d, h, mi, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
                if (t <= System.currentTimeMillis()) a.toast(L("Choose a time in the future.", "Choisissez une heure dans le futur.")) else { due = t; drawDue() }
            }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun add() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        val k = Task(newId(), t.take(200), due, false)
        val d = due
        Store.tasks.add(k); input.setText(""); due = null; drawDue()
        if (d != null) Reminders.ask(a) { ok ->
            if (ok) { k.armed = true; Reminders.schedule(a, k.id, d) } else k.armed = false
            Store.saveTasks(); render()
        } else Store.saveTasks()
        render()
    }

    private fun toggle(k: Task) {
        k.done = !k.done
        if (k.done) { Reminders.cancel(a, k.id); k.armed = false }
        else if (k.due != null && k.due!! > System.currentTimeMillis()) Reminders.ask(a) { ok -> if (ok) { k.armed = true; Reminders.schedule(a, k.id, k.due!!) }; Store.saveTasks(); render() }
        Store.saveTasks(); render()
    }

    private fun render() {
        warn.removeAllViews()
        val hasDue = Store.tasks.any { !it.done && it.due != null && it.due!! > System.currentTimeMillis() }
        if (hasDue && !Reminders.enabled(a)) warn.put(note(L("Notifications are off for Notia, so reminders will not ring.", "Les notifications sont désactivées pour Notia : les rappels ne sonneront pas."), L("Open settings", "Ouvrir les réglages")) { Reminders.openNotificationSettings(a) }, MATCH, WRAP, 0f, 0, 0, 0, 12)
        else if (hasDue && !Reminders.canExact(a)) warn.put(note(L("For on-time reminders, allow “Alarms & reminders”. Without it they may be a few minutes late.", "Pour des rappels à l’heure, autorisez « Alarmes et rappels ». Sinon ils peuvent avoir quelques minutes de retard."), L("Allow", "Autoriser")) { Reminders.openExactSettings(a) }, MATCH, WRAP, 0f, 0, 0, 0, 12)
        listBox.removeAllViews()
        val sorted = Store.tasks.sortedWith(compareBy<Task>({ it.done }, { it.due ?: Long.MAX_VALUE }))
        if (sorted.isEmpty()) listBox.put(a.text(L("No tasks yet.", "Aucune tâche."), 15f, Th.sub).apply { gravity = Gravity.CENTER }, MATCH, WRAP, 0f, 0, 40, 0, 0)
        for (k in sorted) listBox.put(a.hbox {
            background = a.rounded(Th.card, 18f, Th.line); setPadding(a.dp(6), a.dp(4), a.dp(6), a.dp(4)); minimumHeight = a.dp(60)
            put(a.iconBtn(if (k.done) R.drawable.ic_check_circle else R.drawable.ic_circle, L("Done", "Terminé"), if (k.done) Th.accent else Th.sub, 24) { toggle(k) })
            put(a.vbox {
                put(a.text(k.title, 16f, if (k.done) Th.sub else Th.ink, true).apply { if (k.done) paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG })
                if (k.due != null) {
                    val late = !k.done && k.due!! < System.currentTimeMillis()
                    put(a.text((if (k.armed && !k.done) "⏰ " else "") + fmtDate(k.due!!, true) + (if (late) "  •  " + L("overdue", "en retard") else ""), 12f, if (late) Th.danger else Th.sub), MATCH, WRAP, 0f, 0, 3, 0, 0)
                }
            }, 0, WRAP, 1f, 4, 0, 4, 0)
            put(a.iconBtn(R.drawable.ic_delete, L("Delete", "Supprimer"), Th.sub, 20) { Reminders.cancel(a, k.id); Store.tasks.remove(k); Store.saveTasks(); render() })
        }, MATCH, WRAP, 0f, 0, 0, 0, 8)
    }

    private fun note(msg: String, action: String, onClick: () -> Unit): View = a.vbox {
        background = a.rounded(Th.field, 16f); setPadding(a.dp(14), a.dp(12), a.dp(14), a.dp(12))
        put(a.text(msg, 13f, Th.ink).apply { setLineSpacing(0f, 1.15f) })
        put(a.text(action, 14f, Th.accent, true).apply { isClickable = true; setOnClickListener { onClick() }; setPadding(0, a.dp(8), 0, a.dp(4)) }, WRAP, WRAP)
    }

    override fun view(): View = root
    override fun onShow() { Store.listen(onData); drawDue(); render() }
    override fun onHide() { Store.unlisten(onData); Store.saveTasks() }
}

class VaultScreen(private val a: MainActivity) : Screen() {
    private val root = FrameLayout(a).apply { setBackgroundColor(Th.bg) }
    private var entries: MutableList<VaultEntry>? = null
    private val revealed = HashSet<String>()
    private var visible = false

    private fun render() {
        root.removeAllViews()
        val v = if (!Vault.isSet(a)) setup() else if (entries == null) locked() else unlocked()
        root.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun centered(build: LinearLayout.() -> Unit): View = a.vscroll {
        setPadding(a.dp(28), a.dp(60), a.dp(28), a.dp(120)); gravity = Gravity.CENTER_HORIZONTAL
        put(a.icon(R.drawable.ic_lock, Th.accent, 48).also { (it.layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL }, a.dp(48), a.dp(48), 0f, 0, 0, 0, 16)
        build()
    }

    private fun pw(hint: String) = a.field(hint).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; typeface = android.graphics.Typeface.DEFAULT }

    private fun setup(): View = centered {
        put(a.text(L("Create your vault", "Créez votre coffre"), 24f, Th.ink, true).apply { gravity = Gravity.CENTER })
        put(a.text(L("Choose a 5-digit PIN, or a password of 6+ characters with letters and numbers. Your passwords are encrypted on this phone.", "Choisissez un code à 5 chiffres, ou un mot de passe de 6 caractères minimum avec lettres et chiffres. Vos mots de passe sont chiffrés sur ce téléphone."), 14f, Th.sub).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) }, MATCH, WRAP, 0f, 0, 10, 0, 18)
        val p1 = pw(L("PIN or password", "Code ou mot de passe")); val p2 = pw(L("Repeat", "Répéter"))
        val err = a.text("", 13f, Th.danger).apply { gravity = Gravity.CENTER }
        put(p1, MATCH, WRAP, 0f, 0, 0, 0, 10); put(p2, MATCH, WRAP, 0f, 0, 0, 0, 10); put(err, MATCH, WRAP, 0f, 0, 0, 0, 10)
        put(a.pill(L("Create vault", "Créer le coffre")) {
            val s = p1.text.toString()
            if (!Vault.valid(s)) err.text = L("Use 5 digits, or 6+ characters with a letter and a number.", "5 chiffres, ou 6 caractères minimum avec une lettre et un chiffre.")
            else if (s != p2.text.toString()) err.text = L("The two entries differ.", "Les deux saisies diffèrent.")
            else { Vault.setSecret(a, s); entries = ArrayList(); render() }
        })
    }

    private fun locked(): View = centered {
        put(a.text(L("Vault locked", "Coffre verrouillé"), 24f, Th.ink, true).apply { gravity = Gravity.CENTER }, MATCH, WRAP, 0f, 0, 0, 0, 18)
        val p = pw(L("PIN or password", "Code ou mot de passe"))
        val err = a.text("", 13f, Th.danger).apply { gravity = Gravity.CENTER }
        fun go() {
            val w = Vault.waitSeconds(a)
            if (w > 0) { err.text = L("Too many attempts. Try again in ${w}s.", "Trop d’essais. Réessayez dans ${w} s."); return }
            if (Vault.verify(a, p.text.toString())) { entries = Vault.load(a); render() }
            else { p.setText(""); val w2 = Vault.waitSeconds(a); err.text = if (w2 > 0) L("Too many attempts. Try again in ${w2}s.", "Trop d’essais. Réessayez dans ${w2} s.") else L("Wrong PIN or password.", "Code ou mot de passe incorrect.") }
        }
        p.setOnEditorActionListener { _, _, _ -> go(); true }
        put(p, MATCH, WRAP, 0f, 0, 0, 0, 10); put(err, MATCH, WRAP, 0f, 0, 0, 0, 10)
        put(a.pill(L("Unlock", "Déverrouiller")) { go() })
        put(a.text(L("Forgot it? Reset the vault", "Oublié ? Réinitialiser le coffre"), 13f, Th.sub).apply {
            gravity = Gravity.CENTER; setPadding(0, a.dp(14), 0, a.dp(14)); isClickable = true
            setOnClickListener { a.confirm(L("Reset the vault?", "Réinitialiser le coffre ?"), L("All saved passwords will be permanently deleted.", "Tous les mots de passe enregistrés seront définitivement supprimés."), L("Reset", "Réinitialiser")) {
                File(a.filesDir, "vault.json").delete(); File(a.filesDir, "vault.bin").delete(); entries = null; render() } }
        }, MATCH, WRAP, 0f, 0, 14, 0, 0)
    }

    private fun copy(text: String) {
        val cm = a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("password", text)
        if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        cm.setPrimaryClip(clip)
        a.toast(L("Copied. Cleared in 30 s.", "Copié. Effacé dans 30 s."))
        Handler(Looper.getMainLooper()).postDelayed({ try { if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() else cm.setPrimaryClip(ClipData.newPlainText("", "")) } catch (e: Exception) {} }, 30_000)
    }

    private fun unlocked(): View {
        val list = entries!!
        return a.vscroll {
            setPadding(a.dp(20), a.dp(18), a.dp(20), a.dp(120))
            put(a.hbox {
                put(a.text(L("Vault", "Coffre"), 30f, Th.ink, true), 0, WRAP, 1f)
                put(a.iconBtn(R.drawable.ic_lock, L("Lock", "Verrouiller"), Th.ink) { lock() })
            }, MATCH, WRAP, 0f, 0, 0, 0, 14)
            put(a.pill(L("Add password", "Ajouter un mot de passe")) { edit(null) }, MATCH, WRAP, 0f, 0, 0, 0, 14)
            if (list.isEmpty()) put(a.text(L("No passwords saved.", "Aucun mot de passe enregistré."), 15f, Th.sub).apply { gravity = Gravity.CENTER }, MATCH, WRAP, 0f, 0, 30, 0, 0)
            for (e in list) put(a.hbox {
                background = a.rounded(Th.card, 18f, Th.line); setPadding(a.dp(16), a.dp(12), a.dp(6), a.dp(12))
                put(a.vbox {
                    put(a.text(e.site.ifBlank { "—" }, 16f, Th.ink, true))
                    if (e.user.isNotBlank()) put(a.text(e.user, 13f, Th.sub), MATCH, WRAP, 0f, 0, 3, 0, 0)
                    put(a.text(if (e.id in revealed) e.pass else "••••••••", 14f, Th.accent).apply { typeface = android.graphics.Typeface.MONOSPACE }, MATCH, WRAP, 0f, 0, 6, 0, 0)
                }, 0, WRAP, 1f)
                put(a.iconBtn(if (e.id in revealed) R.drawable.ic_eye_off else R.drawable.ic_eye, L("Show", "Afficher"), Th.sub, 20) { if (!revealed.add(e.id)) revealed.remove(e.id); render() })
                put(a.iconBtn(R.drawable.ic_doc, L("Copy", "Copier"), Th.sub, 20) { copy(e.pass) })
                put(a.iconBtn(R.drawable.ic_pen, L("Edit", "Modifier"), Th.sub, 20) { edit(e) })
                put(a.iconBtn(R.drawable.ic_delete, L("Delete", "Supprimer"), Th.danger, 20) {
                    a.confirm(L("Delete this password?", "Supprimer ce mot de passe ?"), e.site, L("Delete", "Supprimer")) { list.remove(e); Vault.save(a, list); render() }
                })
            }, MATCH, WRAP, 0f, 0, 0, 0, 8)
        }
    }

    private fun edit(e: VaultEntry?) {
        val s = Sheet(a, if (e == null) L("New password", "Nouveau mot de passe") else L("Edit password", "Modifier"))
        val site = a.field(L("Site or app", "Site ou application")).apply { setText(e?.site ?: "") }
        val user = a.field(L("Username or email", "Identifiant ou e-mail")).apply { setText(e?.user ?: "") }
        val pass = a.field(L("Password", "Mot de passe")).apply { setText(e?.pass ?: "") }
        s.body.put(site, MATCH, WRAP, 0f, 0, 0, 0, 10); s.body.put(user, MATCH, WRAP, 0f, 0, 0, 0, 10); s.body.put(pass, MATCH, WRAP, 0f, 0, 0, 0, 14)
        s.body.put(a.pill(L("Save", "Enregistrer")) {
            if (pass.text.isNullOrEmpty()) return@pill
            val list = entries ?: return@pill
            if (e == null) list.add(0, VaultEntry(newId(), site.text.toString().trim(), user.text.toString().trim(), pass.text.toString()))
            else { e.site = site.text.toString().trim(); e.user = user.text.toString().trim(); e.pass = pass.text.toString() }
            Vault.save(a, list); s.dismiss(); render()
        }); s.show()
    }

    fun lock() { if (entries != null) { entries = null; revealed.clear(); if (visible) render() } }

    override fun view(): View = root
    override fun onShow() { visible = true; render() }
    override fun onHide() { visible = false; lock() }
}

class TransferScreen(private val a: MainActivity, pre: String? = null) : Screen() {
    private val root = a.vbox { setBackgroundColor(Th.bg) }
    private val selected = HashSet<String>().apply { if (pre != null) add(pre) }
    private val notesBox = a.vbox()
    private val report = a.text("", 13f, Th.sub)
    private val rxInfo = a.text("", 20f, Th.accent, true)
    private val rxCount = a.text("", 13f, Th.sub)
    private var server: Net.Server? = null
    private var received = 0
    private var busy = false

    init {
        val top = a.hbox { setPadding(a.dp(8), a.dp(8), a.dp(16), 0) }
        top.put(a.iconBtn(R.drawable.ic_back, L("Back", "Retour"), Th.ink, 24) { a.pop() })
        top.put(a.text(L("Wi-Fi transfer", "Transfert Wi-Fi"), 22f, Th.ink, true), 0, WRAP, 1f)
        root.put(top)
        val sv = a.vscroll {
            setPadding(a.dp(20), a.dp(14), a.dp(20), a.dp(40))
            // desktop sync
            put(section(L("Sync from desktop", "Synchroniser depuis le bureau")) {
                put(a.text(L("Scan the QR code shown by Notia Desktop. One scan is enough. Both devices must be on the same Wi-Fi.", "Scannez le QR code affiché par Notia Desktop. Un seul scan suffit. Les deux appareils doivent être sur le même Wi-Fi."), 13f, Th.sub).apply { setLineSpacing(0f, 1.15f) }, MATCH, WRAP, 0f, 0, 0, 0, 12)
                put(a.pill(L("Scan desktop QR code", "Scanner le QR code du bureau")) { scan() })
                val link = a.field(L("…or paste the sync link", "…ou collez le lien de synchronisation"))
                put(link, MATCH, WRAP, 0f, 0, 10, 0, 0)
                put(a.pill(L("Sync from link", "Synchroniser via le lien"), false) { sync(link.text.toString()) }, MATCH, WRAP, 0f, 0, 8, 0, 0)
            }, MATCH, WRAP, 0f, 0, 0, 0, 14)
            // send
            put(section(L("Send notes", "Envoyer des notes")) {
                put(notesBox)
                val targets = a.field(L("192.168.1.20 4821\n(address, then code; one device per line)", "192.168.1.20 4821\n(adresse puis code ; un appareil par ligne)"), false, 2)
                put(targets, MATCH, WRAP, 0f, 0, 10, 0, 10)
                put(a.pill(L("Send", "Envoyer")) { send(targets.text.toString()) })
                put(report, MATCH, WRAP, 0f, 0, 10, 0, 0)
            }, MATCH, WRAP, 0f, 0, 0, 0, 14)
            // receive
            put(section(L("Receive notes", "Recevoir des notes")) {
                put(a.hbox {
                    put(a.text(L("Receive from another device", "Recevoir d’un autre appareil"), 15f, Th.ink, true), 0, WRAP, 1f)
                    put(Switch(a).apply {
                        thumbTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt()); trackTintList = ColorStateList.valueOf(Th.accent)
                        setOnCheckedChangeListener { _, on -> if (on) startRx() else stopRx() }
                    })
                })
                put(rxInfo, MATCH, WRAP, 0f, 0, 10, 0, 0); put(rxCount, MATCH, WRAP, 0f, 0, 4, 0, 0)
            })
        }
        root.put(sv, MATCH, 0, 1f)
        drawNotes()
    }

    private fun section(title: String, build: LinearLayout.() -> Unit): View = a.vbox {
        background = a.rounded(Th.card, 20f, Th.line); setPadding(a.dp(16), a.dp(16), a.dp(16), a.dp(16))
        put(a.text(title, 17f, Th.ink, true), MATCH, WRAP, 0f, 0, 0, 0, 10)
        build()
    }

    private fun drawNotes() {
        notesBox.removeAllViews()
        if (Store.notes.isEmpty()) notesBox.put(a.text(L("No notes to send.", "Aucune note à envoyer."), 13f, Th.sub))
        for (n in Store.notes.take(100)) notesBox.put(a.hbox {
            minimumHeight = a.dp(44)
            put(a.icon(if (n.id in selected) R.drawable.ic_box_on else R.drawable.ic_box_off, if (n.id in selected) Th.accent else Th.sub, 22), a.dp(22), a.dp(22), 0f, 0, 0, 12, 0)
            put(a.text(n.title.ifBlank { L("Untitled", "Sans titre") }, 15f, Th.ink).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, 0, WRAP, 1f)
            isClickable = true
            setOnClickListener { if (!selected.add(n.id)) selected.remove(n.id); drawNotes() }
        })
    }

    private fun send(targetsText: String) {
        if (busy) return
        val notes = Store.notes.filter { it.id in selected }
        val tg = Net.parseTargets(targetsText)
        if (notes.isEmpty()) { report.text = L("Select at least one note.", "Sélectionnez au moins une note."); return }
        if (tg.isEmpty()) { report.text = L("Enter the receiver address, e.g. 192.168.1.20 4821", "Entrez l’adresse du destinataire, ex. 192.168.1.20 4821"); return }
        busy = true; report.text = L("Sending…", "Envoi…")
        val body = Net.payload(notes)
        Thread {
            val lines = tg.map { t ->
                val c = Net.send(t, body)
                "${t.host}:${t.port} — " + when (c) {
                    200 -> L("sent ✓", "envoyé ✓"); 401 -> L("wrong code", "code incorrect")
                    0 -> L("unreachable (same Wi-Fi? app open and receiving?)", "injoignable (même Wi-Fi ? application ouverte en réception ?)"); else -> "error $c"
                }
            }
            a.post(Runnable { report.text = lines.joinToString("\n"); busy = false })
        }.start()
    }

    private fun scan() { Qr.scan(a, { v -> a.post(Runnable { sync(v) }) }, { m -> a.post(Runnable { a.toast(L("Scanner unavailable: ", "Scanner indisponible : ") + m + "\n" + L("Paste the sync link instead.", "Collez plutôt le lien de synchronisation.")) }) }) }

    private fun sync(v: String) {
        val u = Net.validSyncUrl(v)
        if (u == null) { a.info(L("Invalid QR code", "QR code invalide"), L("This is not a valid Notia sync code for your local network.", "Ce n’est pas un code de synchronisation Notia valide pour votre réseau local.")); return }
        a.toast(L("Connecting and importing notes…", "Connexion et importation des notes…"))
        Thread {
            try {
                val list = Net.fetchSync(u)
                a.post(Runnable {
                    val n = Store.importNotes(list); Store.changed()
                    a.info(L("Sync complete", "Synchronisation terminée"), L("$n note(s) imported or updated.", "$n note(s) importée(s) ou mise(s) à jour."))
                })
            } catch (e: Exception) { a.post(Runnable { a.info(L("Sync failed", "Échec de la synchronisation"), L("Could not reach the desktop. Same Wi-Fi? Is Notia Desktop still showing the QR code?", "Impossible de joindre le bureau. Même Wi-Fi ? Notia Desktop affiche-t-il toujours le QR code ?")) }) }
        }.start()
    }

    private fun startRx() {
        val ip = Net.myIp()
        if (ip == null) { a.toast(L("Connect to Wi-Fi first.", "Connectez-vous d’abord au Wi-Fi.")); return }
        val code = (1000 + java.security.SecureRandom().nextInt(9000)).toString()
        val s = Net.Server(code, { list ->
            val latch = CountDownLatch(1); var n = 0
            a.post(Runnable { n = Store.importNotes(list); Store.changed(); received += n; rxCount.text = L("Received: $received note(s)", "Reçu : $received note(s)"); latch.countDown() })
            latch.await(5, TimeUnit.SECONDS); n
        }, { m -> a.post(Runnable { rxInfo.text = ""; a.toast(L("Cannot receive: ", "Réception impossible : ") + m) }) })
        if (s.start()) { server = s; rxInfo.text = "$ip:${Net.PORT}  $code"; rxCount.text = L("Give this address and code to the sender. Keep this screen open.", "Donnez cette adresse et ce code à l’expéditeur. Gardez cet écran ouvert.") }
    }

    private fun stopRx() { server?.stop(); server = null; rxInfo.text = ""; rxCount.text = "" }

    override fun view(): View = root
    override fun onDestroy() { stopRx() }
    override fun onBack(): Boolean { return false }
}
