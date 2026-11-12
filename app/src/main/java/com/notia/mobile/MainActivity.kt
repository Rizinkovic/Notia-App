package com.notia.mobile

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import com.notia.mobile.core.Store
import com.notia.mobile.sys.MainActivityLike
import com.notia.mobile.sys.Reminders
import com.notia.mobile.ui.MATCH
import com.notia.mobile.ui.Screen
import com.notia.mobile.ui.Tabs
import com.notia.mobile.sys.Library
import com.notia.mobile.ui.ReaderScreen
import com.notia.mobile.ui.Th
import com.notia.mobile.ui.importError
import com.notia.mobile.ui.toast
import com.notia.mobile.ui.dp
import com.notia.mobile.ui.put
import com.notia.mobile.ui.vbox

class NotiaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Reminders.channels(this)
    }
}

class MainActivity : Activity(), MainActivityLike {
    override val activity: Activity get() = this
    private val stack = ArrayList<Screen>()
    private lateinit var root: FrameLayout
    private val ui = Handler(Looper.getMainLooper())
    var resumed = false
        private set
    private var tabs: Tabs? = null
    private var pickCb: ((Uri?) -> Unit)? = null
    private var permCb: ((Boolean) -> Unit)? = null
    private var pendingBook: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        root = FrameLayout(this)
        setContentView(root)
        applyBars()
        pendingBook = bookUriOf(intent)
        showLoading()
        val t0 = System.currentTimeMillis()
        Thread {
            Store.ensureLoaded(applicationContext)
            val wait = Math.max(0L, 700 - (System.currentTimeMillis() - t0))
            ui.postDelayed({ if (!isFinishing) { applyBars(); start(0); openPending() } }, wait)
        }.start()
    }

    private fun showLoading() {
        root.setBackgroundColor(0xFF14121C.toInt())
        val box = FrameLayout(this)
        box.addView(ImageView(this).apply { setImageResource(R.drawable.logo) }, FrameLayout.LayoutParams(dp(96), dp(96), Gravity.CENTER))
        box.addView(ProgressBar(this).apply { isIndeterminate = true }, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER).also { it.topMargin = dp(120) })
        root.addView(box, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    /** Status and navigation bar colours follow the in-app theme. */
    fun applyBars() {
        val bg = Th.bg
        window.statusBarColor = bg; window.navigationBarColor = bg
        var f = window.decorView.systemUiVisibility
        f = if (!Store.dark) f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else f and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        if (Build.VERSION.SDK_INT >= 26) f = if (!Store.dark) f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else f and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        window.decorView.systemUiVisibility = f
        root.setBackgroundColor(bg)
    }

    /** A book shared to Notia or opened with "Open in Notia" from a file manager, mail or chat app. */
    private fun bookUriOf(i: Intent?): Uri? {
        if (i == null || (i.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return null
        @Suppress("DEPRECATION")
        return when (i.action) {
            Intent.ACTION_VIEW -> i.data
            Intent.ACTION_SEND -> i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            else -> null
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingBook = bookUriOf(intent)
        if (tabs != null) openPending()
    }

    /** Adds the file to the library (once) and opens it in the reader. */
    private fun openPending() {
        val uri = pendingBook ?: return
        pendingBook = null
        intent = Intent()
        toast(if (Store.lang == "fr") "Ouverture du livre…" else "Opening book…")
        Thread {
            var book: com.notia.mobile.core.Book? = null; var added = false; var err: String? = null
            try { book = Library.existing(applicationContext, uri) ?: Library.import(applicationContext, uri).also { added = true } }
            catch (e: Library.Fail) { err = e.code } catch (e: Throwable) { err = "invalid" }
            ui.post {
                if (isFinishing) return@post
                val b = book
                if (b == null) toast(importError(err ?: "invalid"))
                else { if (added) { Store.books.add(0, b); Store.saveLib(); Store.changed() }; push(ReaderScreen(this, b.id)) }
            }
        }.start()
    }

    /** (Re)builds the whole UI, e.g. after a theme or language change. */
    fun start(tab: Int) {
        stack.forEach { it.onHide(); it.onDestroy() }
        stack.clear()
        val t = Tabs(this, tab)
        tabs = t
        stack.add(t)
        show()
    }

    private fun show() {
        val s = stack.last()
        root.removeAllViews()
        (s.view().parent as? ViewGroup)?.removeView(s.view())
        root.addView(s.view(), FrameLayout.LayoutParams(MATCH, MATCH))
        s.onShow()
    }

    fun push(s: Screen) { stack.last().onHide(); stack.add(s); show() }

    fun pop() {
        if (stack.size <= 1) return
        val s = stack.removeAt(stack.size - 1)
        s.onHide(); s.onDestroy()
        show()
    }

    fun setSecure(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun post(r: Runnable) { ui.post(r) }

    // Opens the system file picker with the given MIME types.
    fun pick(mimes: Array<String>, cb: (Uri?) -> Unit) {
        pickCb = cb
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(if (mimes.size == 1) mimes[0] else "*/*")
        if (mimes.size > 1) i.putExtra(Intent.EXTRA_MIME_TYPES, mimes)
        try { startActivityForResult(i, 11) } catch (e: Exception) { pickCb = null; cb(null) }
    }

    @Deprecated("framework callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 11) { val cb = pickCb; pickCb = null; cb?.invoke(if (resultCode == RESULT_OK) data?.data else null) }
    }

    override fun requestNotifPermission(cb: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < 33) { cb(true); return }
        permCb = cb
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 21)
    }

    @Deprecated("framework callback")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 21) { val cb = permCb; permCb = null; cb?.invoke(grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) }
    }

    override fun onResume() { super.onResume(); resumed = true; if (Store.loaded) Reminders.scheduleReading(this) }
    override fun onPause() { resumed = false; if (Store.loaded) Store.flush(); super.onPause() }
    override fun onStop() { tabs?.lockVault(); super.onStop() }

    override fun onDestroy() { stack.forEach { it.onDestroy() }; super.onDestroy() }

    @Deprecated("framework callback")
    override fun onBackPressed() {
        if (stack.isEmpty()) { super.onBackPressed(); return }
        val s = stack.last()
        if (s.onBack()) return
        if (stack.size > 1) pop() else super.onBackPressed()
    }
}
