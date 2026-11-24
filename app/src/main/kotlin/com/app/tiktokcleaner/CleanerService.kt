package com.app.tiktokcleaner

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.math.abs
import kotlin.random.Random

class CleanerService : AccessibilityService() {

    companion object {
        var instance: CleanerService? = null
        var listener: (() -> Unit)? = null
        var lastStatus = ""
        var isRunning = false
        var doneCount = 0
        var skipCount = 0
        var cycleCount = 0
        var cycleSum = 0L
        var startedAt = 0L

        val TIKTOK_PACKAGES = setOf(
            "com.zhiliaoapp.musically",      // TikTok
            "com.ss.android.ugc.trill",      // TikTok (varian Asia)
            "com.zhiliaoapp.musically.go"    // TikTok Lite
        )
        // Kata pendek (<= 3 huruf) dicocokkan persis, sisanya "mengandung"
        val PROFILE_KW = listOf("me", "profile", "profil", "saya")
        val UNDO_KW = listOf(
            "undo repost", "batalkan repost", "batal repost",
            "urungkan repost", "hapus repost", "undo"
        )
        val SHARE_KW = listOf("share", "bagikan")
        val SHEET_KW = listOf("send to", "kirim ke", "copy link", "salin tautan", "salin link")
        // Penanda iklan
        val AD_KW = listOf(
            "sponsored", "bersponsor", "disponsori", "promoted",
            "learn more", "shop now", "install now", "pelajari lebih", "beli sekarang"
        )
        val AD_EXACT = setOf("ad", "ads", "iklan", "promosi")
        const val MAX_MISS = 4
        const val FIRST_VIDEO_OFFSET = 0.22f

        // Posisi cadangan tombol Share (pecahan layar), dari screenshot 720x1600
        const val SHARE_X = 0.929f
        const val SHARE_Y = 0.814f
    }

    private enum class Phase { HOME, PROFILE, TAB, VIDEO, CLEAN }

    private class Cand(val node: AccessibilityNodeInfo, val r: Rect) {
        val area get() = r.width().toLong() * r.height()
    }

    private val h = Handler(Looper.getMainLooper())
    private var running: Boolean
        get() = isRunning
        set(v) { isRunning = v }

    private var phase = Phase.HOME
    private var mode = 0          // 0=Unlike, 1=Unfavorite, 2=Unrepost
    private var isId = true
    private var tabKw: List<String> = emptyList()
    private var undoKw: List<String> = UNDO_KW
    private var launchPkgs: List<String> = emptyList()
    private var tabIndex = 1      // urutan ikon tab: 1=Favorit, 2=Repost, 3=Suka
    private var limit = 0         // 0 = tanpa batas
    private var dMin = 400
    private var dMax = 1000
    private var verifyWait = 450L
    private var missCount = 0
    private var waitCount = 0
    private var homeTries = 0
    private var tabBottom = 0
    private var cycleStart = 0L
    private var emaMs = 700f      // rata-rata waktu muat yang teramati (ms)

    private var overlay: View? = null
    private var overlayLabel: TextView? = null

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() { stopCleaning() }
    override fun onUnbind(intent: Intent?): Boolean {
        stopCleaning(); instance = null
        return super.onUnbind(intent)
    }

    // ---------- Bahasa & waktu ----------

    private fun tr(id: String, en: String) = if (isId) id else en
    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    private fun delay(): Long = (dMin + Random.nextInt(dMax - dMin + 1)).toLong()
    private fun lagF(): Float = (emaMs / 700f).coerceIn(1f, 3.5f)
    private fun loadWait(ms: Long): Long = (ms * lagF()).toLong()
    private fun next(extra: Long = 0) { h.postDelayed({ tick() }, delay() + extra) }
    private fun swipeSoon() { h.postDelayed({ swipe() }, delay()) }

    // Belajar dari kecepatan muat yang sebenarnya
    private fun observe(ms: Long) {
        emaMs = 0.7f * emaMs + 0.3f * ms.coerceIn(200L, 15000L).toFloat()
    }

    private fun hasNetwork(): Boolean = try {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val nc = cm.getNetworkCapabilities(cm.activeNetwork)
        nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (e: Exception) { true }

    private fun initLagFromNetwork() {
        emaMs = try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val nc = cm.getNetworkCapabilities(cm.activeNetwork)
            val down = nc?.linkDownstreamBandwidthKbps ?: 0
            when {
                nc == null -> 1800f
                down in 1..2000 -> 1800f
                down in 2001..6000 -> 1100f
                else -> 700f
            }
        } catch (e: Exception) { 900f }
    }

    @Suppress("DEPRECATION")
    private fun screen(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    // ---------- Kontrol ----------

    fun startCleaning(mode: Int, tab: List<String>, extraUndo: List<String>, tabIdx: Int,
                      speed: Int, indo: Boolean, limit: Int, pkgs: List<String>): Boolean {
        isId = indo
        if (mode != 2 && Build.VERSION.SDK_INT < 30) {
            toast(tr("Mode ini butuh Android 11 ke atas", "This mode requires Android 11 or newer"))
            return false
        }
        this.mode = mode
        tabKw = tab
        undoKw = UNDO_KW + extraUndo
        tabIndex = tabIdx
        this.limit = limit
        launchPkgs = pkgs
        when (speed) {
            0 -> { dMin = 1000; dMax = 3000; verifyWait = 700 }   // Aman
            1 -> { dMin = 400; dMax = 1000; verifyWait = 450 }    // Cepat
            else -> { dMin = 150; dMax = 450; verifyWait = 350 }  // Turbo
        }
        initLagFromNetwork()
        missCount = 0; waitCount = 0; homeTries = 0
        doneCount = 0; skipCount = 0; cycleCount = 0; cycleSum = 0L; cycleStart = 0L
        startedAt = SystemClock.uptimeMillis()
        phase = Phase.HOME
        running = true
        showOverlay()
        status(tr("Menyiapkan...", "Getting ready..."))
        h.postDelayed({ tick() }, loadWait(1500))
        return true
    }

    fun stopCleaning() = endSession("stop", tr("Dihentikan", "Stopped"))

    // code: done / limit / stop / err
    private fun endSession(code: String, msg: String) {
        val was = running
        running = false
        h.removeCallbacksAndMessages(null)
        hideOverlay()
        lastStatus = msg
        if (was) {
            saveHistory(code)
            if (code != "stop") toast(msg)
        }
        listener?.invoke()
    }

    private fun saveHistory(code: String) {
        try {
            val sp = getSharedPreferences("aeryth", MODE_PRIVATE)
            val dur = (SystemClock.uptimeMillis() - startedAt) / 1000
            val line = "${System.currentTimeMillis()};$mode;$doneCount;$skipCount;$dur;$code"
            val old = (sp.getString("history", "") ?: "").split("\n").filter { it.isNotBlank() }
            sp.edit().putString("history", (listOf(line) + old).take(50).joinToString("\n")).apply()
        } catch (_: Exception) {}
    }

    private fun status(msg: String) {
        lastStatus = msg
        Log.d("TC", msg)
        overlayLabel?.text = tr("Berhenti", "Stop") + "  ·  $doneCount"
        listener?.invoke()
    }

    // done=true: berhenti karena memang sudah selesai / daftar habis
    private fun failed(msg: String, done: Boolean = false): Boolean {
        if (++missCount >= MAX_MISS) {
            if (done) endSession("done", tr("Selesai · $doneCount video dibersihkan",
                "Finished · $doneCount videos cleaned"))
            else endSession("err", msg)
            return true
        }
        return false
    }

    private fun onCleaned() {
        doneCount++
        missCount = 0
        status(tr("Dibersihkan: $doneCount video", "Cleaned: $doneCount videos"))
        if (limit in 1..doneCount) {
            endSession("limit", tr("Batas tercapai · $doneCount video dibersihkan",
                "Limit reached · $doneCount videos cleaned"))
            return
        }
        swipeSoon()
    }

    private fun skipAd() {
        skipCount++
        status(tr("Iklan terdeteksi, dilewati", "Ad detected, skipped"))
        swipeSoon()
    }

    private fun relaunch() {
        for (p in launchPkgs) {
            val i = packageManager.getLaunchIntentForPackage(p) ?: continue
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { startActivity(i) } catch (_: Exception) {}
            return
        }
    }

    // ---------- Alur utama ----------

    private fun tick() {
        if (!running) return
        if (!hasNetwork()) {
            status(tr("Menunggu koneksi internet...", "Waiting for connection..."))
            h.postDelayed({ tick() }, 2000)
            return
        }
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString()
        if (root == null || pkg == null || pkg !in TIKTOK_PACKAGES) {
            if (phase == Phase.HOME) {
                if (++homeTries > 12) {
                    endSession("err", tr("Aplikasi tidak terbuka. Berhenti.", "The app did not open. Stopped."))
                    return
                }
                status(tr("Membuka aplikasi...", "Opening the app..."))
                relaunch()
                next(loadWait(1500)); return
            }
            if (failed(tr("Aplikasi tidak di layar. Berhenti.", "App is not on screen. Stopped."))) return
            next(loadWait(500)); return
        }
        when (phase) {
            Phase.HOME -> stepHome(root)
            Phase.PROFILE -> stepProfile(root)
            Phase.TAB -> stepTab(root)
            Phase.VIDEO -> stepVideo(root)
            Phase.CLEAN -> if (mode == 2) cleanRepost() else cleanIcon()
        }
    }

    // 0) Dari mana pun: tekan Back sampai menu bawah (Home / Me) terlihat
    private fun stepHome(root: AccessibilityNodeInfo) {
        val (_, sh) = screen()
        val nav = findAll(root, PROFILE_KW).any { it.r.top > sh * 0.8 }
        if (nav) {
            homeTries = 0
            phase = Phase.PROFILE
            next(loadWait(200))
            return
        }
        if (++homeTries > 14) {
            endSession("err", tr("Tidak bisa kembali ke beranda. Berhenti.", "Could not return to the home screen. Stopped."))
            return
        }
        status(tr("Kembali ke beranda...", "Returning to home..."))
        performGlobalAction(GLOBAL_ACTION_BACK)
        next(loadWait(500))
    }

    // 1) Profil ("Me")
    private fun stepProfile(root: AccessibilityNodeInfo) {
        val (sw, sh) = screen()
        val c = findAll(root, PROFILE_KW)
            .filter { it.r.top > sh * 0.8 }
            .minByOrNull { it.area }
        if (c == null && waitCount < 3) {
            waitCount++; next(loadWait(800)); return
        }
        waitCount = 0
        status(tr("Membuka profil...", "Opening profile..."))
        if (c != null) tapRect(c.r) else tapAt(sw * 0.9f, sh * 0.955f)
        missCount = 0; phase = Phase.TAB
        next(loadWait(1200))
    }

    // 2) Tab Favorit / Repost / Suka
    private fun stepTab(root: AccessibilityNodeInfo) {
        val (sw, sh) = screen()
        var rect: Rect? = findAll(root, tabKw)
            .filter { it.r.top < sh * 0.7 && it.r.top > sh * 0.15 }
            .maxByOrNull { it.r.top }?.r
        if (rect == null) {
            val row = findTabRow(root, sw, sh)
            if (row != null && tabIndex < row.size) rect = row[tabIndex]
        }
        if (rect == null && waitCount < 3) {
            waitCount++; next(loadWait(800)); return
        }
        waitCount = 0
        status(tr("Memilih tab...", "Selecting tab..."))
        if (rect == null) {
            tabBottom = (sh * 0.395f).toInt()
            tapAt(sw * (0.1f + 0.2f * tabIndex), sh * 0.375f)
        } else {
            tabBottom = rect.bottom
            tapRect(rect)
        }
        missCount = 0; phase = Phase.VIDEO
        next(loadWait(1200))
    }

    // 3) Buka video paling atas
    private fun stepVideo(root: AccessibilityNodeInfo) {
        val (sw, _) = screen()
        val cells = ArrayList<Rect>()
        collectCells(root, sw, cells)
        val cell = cells.minWithOrNull(compareBy<Rect>({ it.top }, { it.left }))
        status(tr("Membuka video pertama...", "Opening first video..."))
        if (cell != null) tapAt(cell.centerX().toFloat(), cell.centerY().toFloat())
        else tapAt(sw / 6f, tabBottom + sw * FIRST_VIDEO_OFFSET)
        missCount = 0; phase = Phase.CLEAN
        cycleStart = SystemClock.uptimeMillis()
        next(loadWait(1500))
    }

    // ---------- Deteksi iklan ----------

    private fun isAd(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        var found = false
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || found) return
            for (cs in listOf(n.text, n.contentDescription)) {
                val t = cs?.toString()?.lowercase()?.trim() ?: continue
                if (t.length in 2..40 && (t in AD_EXACT || AD_KW.any { t.contains(it) })) {
                    found = true
                    return
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return found
    }

    // ---------- Unlike / Unfavorite ----------

    private fun cleanIcon() {
        if (isAd(rootInActiveWindow)) { skipAd(); return }
        val t0 = SystemClock.uptimeMillis()
        status(tr("Memindai video... ($doneCount selesai)", "Scanning video... ($doneCount done)"))
        locateIcon(loadWait(4000)) { pt ->
            if (!running) return@locateIcon
            if (pt == null) {
                if (failed("", true)) return@locateIcon
                skipCount++
                swipeSoon()
            } else if (isAd(rootInActiveWindow)) {
                skipAd()
            } else {
                observe(SystemClock.uptimeMillis() - t0)
                missCount = 0
                tapAndVerify(pt, 0)
            }
        }
    }

    // Tap ikon, lalu cek ulang: kalau ikon masih aktif, tap lagi (maks 3x)
    private fun tapAndVerify(pt: PointF, attempt: Int) {
        tapAt(pt.x + Random.nextInt(9) - 4, pt.y + Random.nextInt(9) - 4)
        h.postDelayed({ verify(attempt, 2) }, verifyWait + ((lagF() - 1f) * 250f).toLong())
    }

    private fun verify(attempt: Int, tries: Int) {
        if (!running) return
        shoot { bmp ->
            if (!running) { bmp?.recycle(); return@shoot }
            if (bmp == null) {
                if (tries > 0) h.postDelayed({ verify(attempt, tries - 1) }, 400) else onCleaned()
                return@shoot
            }
            val again = findIcon(bmp)
            bmp.recycle()
            when {
                again == null -> onCleaned()
                attempt < 2 -> tapAndVerify(again, attempt + 1)
                else -> {
                    if (failed(tr("Tap tidak mengubah ikon.", "Tap did not change the icon."))) return@shoot
                    skipCount++
                    swipeSoon()
                }
            }
        }
    }

    private fun locateIcon(timeoutMs: Long, cb: (PointF?) -> Unit) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        fun poll() {
            if (!running) return
            shoot { bmp ->
                val pt = if (bmp != null) findIcon(bmp) else null
                bmp?.recycle()
                when {
                    !running -> {}
                    pt != null -> cb(pt)
                    SystemClock.uptimeMillis() >= end -> cb(null)
                    else -> h.postDelayed({ poll() }, 350)
                }
            }
        }
        poll()
    }

    // Cari ikon AKTIF (hati merah / bookmark kuning) di kolom kanan layar.
    private fun findIcon(b: Bitmap): PointF? {
        val w = b.width
        val hgt = b.height
        val s = w / 720f
        val x0 = (w * 0.85f).toInt()
        val x1 = (w * 0.995f).toInt()
        val y0 = (hgt * 0.38f).toInt()
        val y1 = (hgt * 0.90f).toInt()
        val sw = x1 - x0
        val sh = y1 - y0
        if (sw <= 0 || sh <= 0) return null
        val px = IntArray(sw * sh)
        b.getPixels(px, 0, sw, x0, y0, sw, sh)

        fun match(c: Int): Boolean {
            val r = Color.red(c); val g = Color.green(c); val bl = Color.blue(c)
            return if (mode == 0) r > 200 && g < 110 && bl < 150      // merah
                   else r > 215 && g in 140..230 && bl < 100          // kuning
        }

        val rowCnt = IntArray(sh)
        val rowMin = IntArray(sh) { Int.MAX_VALUE }
        val rowMax = IntArray(sh) { -1 }
        for (y in 0 until sh) {
            for (x in 0 until sw) {
                if (match(px[y * sw + x])) {
                    rowCnt[y]++
                    if (x < rowMin[y]) rowMin[y] = x
                    if (x > rowMax[y]) rowMax[y] = x
                }
            }
        }

        val lo = (16 * s).toInt()
        val hi = (90 * s).toInt()
        val gapMax = (6 * s).toInt().coerceAtLeast(3)
        var best: PointF? = null
        var y = 0
        while (y < sh) {
            if (rowCnt[y] < 2) { y++; continue }
            val top = y
            var bottom = y
            var cnt = 0
            var minX = Int.MAX_VALUE
            var maxX = -1
            var gap = 0
            var yy = y
            while (yy < sh && gap <= gapMax) {
                if (rowCnt[yy] >= 2) {
                    bottom = yy; cnt += rowCnt[yy]; gap = 0
                    minX = minOf(minX, rowMin[yy]); maxX = maxOf(maxX, rowMax[yy])
                } else gap++
                yy++
            }
            y = yy
            val cw = maxX - minX + 1
            val ch = bottom - top + 1
            if (cw in lo..hi && ch in lo..hi && cnt.toFloat() / (cw * ch) >= 0.3f) {
                val cx = (minX + maxX) / 2
                val cy = (top + bottom) / 2
                // Pusat harus terisi warna aktif; tombol "+" (follow) punya tanda plus
                // putih di tengah sehingga otomatis ditolak.
                var ok = 0
                var tot = 0
                for (dy in -2..2) for (dx in -2..2) {
                    val xx = cx + dx
                    val y2 = cy + dy
                    if (xx in 0 until sw && y2 in 0 until sh) {
                        tot++
                        if (match(px[y2 * sw + xx])) ok++
                    }
                }
                if (tot > 0 && ok * 10 >= tot * 6) {
                    best = PointF((x0 + cx).toFloat(), (y0 + cy).toFloat())
                }
            }
        }
        Log.d("TC", "findIcon mode=$mode -> $best (layar ${w}x$hgt)")
        return best
    }

    // ---------- Unrepost (lewat menu Share) ----------

    private fun cleanRepost() {
        if (isAd(rootInActiveWindow)) { skipAd(); return }
        val t0 = SystemClock.uptimeMillis()
        status(tr("Membuka menu bagikan...", "Opening share menu..."))
        val root = rootInActiveWindow
        val (sw, sh) = screen()
        val share = if (root != null) findShare(root, sw, sh) else null
        if (share != null) tapRect(share)
        else tapAt(sw * SHARE_X + Random.nextInt(9) - 4, sh * SHARE_Y + Random.nextInt(9) - 4)

        h.postDelayed({
            pollUndo(SystemClock.uptimeMillis() + loadWait(7000), t0)
        }, 600L + ((lagF() - 1f) * 300f).toLong())
    }

    // Tombol Share = ikon terbawah di kolom kanan (di bawah Favorit)
    private fun findShare(root: AccessibilityNodeInfo, sw: Int, sh: Int): Rect? {
        val byLabel = findAll(root, SHARE_KW)
            .filter { it.r.centerX() > sw * 0.8 && it.r.top > sh * 0.45 }
            .minByOrNull { it.area }
        if (byLabel != null) return byLabel.r
        var best: Rect? = null
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            if (n.isClickable) {
                val r = Rect()
                n.getBoundsInScreen(r)
                if (r.centerX() > sw * 0.85 && r.centerY() > sh * 0.45 && r.centerY() < sh * 0.87 &&
                    r.width() > sw * 0.06 && r.width() < sw * 0.25 && r.height() < sh * 0.12) {
                    val cur = best
                    if (cur == null || r.centerY() > cur.centerY()) best = r
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return best
    }

    // Tunggu tombol "Undo repost" muncul di menu Share, lalu tap
    private fun pollUndo(end: Long, t0: Long) {
        if (!running) return
        val root = rootInActiveWindow
        val u = if (root != null) findAll(root, undoKw).minByOrNull { it.area } else null
        if (u != null) {
            observe(SystemClock.uptimeMillis() - t0)
            h.postDelayed({
                if (!running) return@postDelayed
                val r2 = rootInActiveWindow
                val fresh = if (r2 != null) findAll(r2, undoKw).minByOrNull { it.area } else null
                status(tr("Membatalkan repost...", "Undoing repost..."))
                tapRect((fresh ?: u).r)
                waitProcessed(SystemClock.uptimeMillis())
            }, 300L + Random.nextInt(250))
            return
        }
        if (SystemClock.uptimeMillis() >= end) {
            closeSheetIfOpen()
            if (failed("", true)) return
            skipCount++
            swipeSoon()
            return
        }
        h.postDelayed({ pollUndo(end, t0) }, 400)
    }

    // Setelah Undo di-tap: tunggu TikTok selesai memproses (menu tertutup)
    private fun waitProcessed(t0: Long) {
        val end = t0 + loadWait(8000)
        var gone = 0
        fun poll() {
            if (!running) return
            val root = rootInActiveWindow
            val open = root != null && findAll(root, SHEET_KW + undoKw).isNotEmpty()
            gone = if (open) 0 else gone + 1
            when {
                gone >= 2 -> {
                    observe(SystemClock.uptimeMillis() - t0)
                    waitLabelGone()
                }
                SystemClock.uptimeMillis() >= end -> {
                    closeSheetIfOpen()
                    if (failed(tr("Undo repost belum selesai.", "Undo repost did not complete."))) return
                    skipCount++
                    swipeSoon()
                }
                else -> h.postDelayed({ poll() }, 400)
            }
        }
        poll()
    }

    // Tunggu label "Reposted" hilang (tanda undo benar-benar diproses), baru scroll
    private fun waitLabelGone() {
        status(tr("Menunggu TikTok memproses...", "Waiting for the app to process..."))
        val end = SystemClock.uptimeMillis() + loadWait(3500)
        fun poll() {
            if (!running) return
            val root = rootInActiveWindow
            val still = root != null && findAll(root, listOf("reposted")).isNotEmpty()
            if (!still || SystemClock.uptimeMillis() >= end) {
                h.postDelayed({ if (running) onCleaned() }, loadWait(300))
                return
            }
            h.postDelayed({ poll() }, 400)
        }
        poll()
    }

    // BACK hanya kalau menu Share benar-benar terbuka (agar tidak keluar dari video)
    private fun closeSheetIfOpen() {
        val root = rootInActiveWindow
        if (root != null && findAll(root, SHEET_KW).isNotEmpty()) performGlobalAction(GLOBAL_ACTION_BACK)
    }

    // ---------- Swipe + tunggu benar-benar berpindah ----------

    private fun swipe() {
        if (!running) return
        val now = SystemClock.uptimeMillis()
        if (cycleStart > 0) { cycleSum += now - cycleStart; cycleCount++ }
        cycleStart = now
        listener?.invoke()

        val (hash0, _) = nodeSig(rootInActiveWindow)
        shoot { b0 ->
            val sig0 = if (b0 != null) sigOf(b0) else null
            b0?.recycle()
            if (!running) return@shoot
            val (sw, sh) = screen()
            val x = sw / 2f + Random.nextInt(41) - 20
            val p = Path().apply {
                moveTo(x, sh * 0.75f + Random.nextInt(41) - 20)
                lineTo(x, sh * 0.25f)
            }
            gesture(p, (200 + Random.nextInt(140)).toLong())
            h.postDelayed({ waitScrolled(sig0, hash0) { tick() } }, 300)
        }
    }

    // Tunggu sampai video benar-benar berganti dan tidak sedang loading
    private fun waitScrolled(prevSig: IntArray?, prevHash: Int, cb: () -> Unit) {
        val t0 = SystemClock.uptimeMillis()
        val end = t0 + loadWait(5000)
        var changed = false
        var lastHash = prevHash
        var stable = 0
        fun poll() {
            if (!running) return
            val (hash, loading) = nodeSig(rootInActiveWindow)
            shoot { bmp ->
                var pixelChanged = false
                val noPix = prevSig == null || bmp == null
                if (!noPix && bmp != null && prevSig != null) {
                    pixelChanged = sigDiff(prevSig, sigOf(bmp)) > 28f
                }
                bmp?.recycle()
                if (!running) return@shoot
                if (hash != prevHash || pixelChanged) changed = true
                stable = if (hash == lastHash) stable + 1 else 0
                lastHash = hash
                val now = SystemClock.uptimeMillis()
                val ready = changed && !loading && stable >= 1
                val fallback = noPix && !loading && now - t0 >= loadWait(1500)
                if (ready || fallback || now >= end) {
                    if (ready) observe(now - t0)
                    cb()
                } else {
                    h.postDelayed({ poll() }, 350)
                }
            }
        }
        poll()
    }

    // Tanda tangan isi layar: hash teks elemen + deteksi spinner loading
    private fun nodeSig(root: AccessibilityNodeInfo?): Pair<Int, Boolean> {
        var hash = 17
        var loading = false
        val (sw, sh) = screen()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            val cn = n.className?.toString() ?: ""
            if (cn.contains("ProgressBar")) {
                val r = Rect()
                n.getBoundsInScreen(r)
                // spinner = persegi sedang; garis progres video tipis diabaikan
                if (r.height() > sh * 0.02 && r.width() > sw * 0.02 && r.height() > r.width() * 0.5) {
                    loading = true
                }
            } else if (!cn.contains("SeekBar")) {
                val t = n.text?.toString()
                val d = n.contentDescription?.toString()
                if (!t.isNullOrEmpty() && t.length < 200) hash = hash * 31 + t.hashCode()
                if (!d.isNullOrEmpty() && d.length < 200) hash = hash * 31 + d.hashCode()
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return hash to loading
    }

    private fun sigOf(b: Bitmap): IntArray {
        val w = b.width
        val hh = b.height
        val cols = 10
        val rows = 16
        val out = IntArray(cols * rows)
        var k = 0
        for (r in 0 until rows) for (c in 0 until cols) {
            val x = (w * (0.06f + 0.70f * (c + 0.5f) / cols)).toInt().coerceIn(0, w - 1)
            val y = (hh * (0.20f + 0.62f * (r + 0.5f) / rows)).toInt().coerceIn(0, hh - 1)
            val p = b.getPixel(x, y)
            out[k++] = (Color.red(p) * 3 + Color.green(p) * 6 + Color.blue(p)) / 10
        }
        return out
    }

    private fun sigDiff(a: IntArray, b: IntArray): Float {
        var s = 0L
        for (i in a.indices) s += abs(a[i] - b[i])
        return s / a.size.toFloat()
    }

    // ---------- Helper ----------

    private fun tapRect(r: Rect) =
        tapAt((r.centerX() + Random.nextInt(11) - 5).toFloat(),
              (r.centerY() + Random.nextInt(11) - 5).toFloat())

    private fun tapAt(x: Float, y: Float) {
        val p = Path().apply { moveTo(x, y) }
        gesture(p, 60)
    }

    private fun gesture(p: Path, duration: Long) {
        val b = GestureDescription.Builder()
        b.addStroke(GestureDescription.StrokeDescription(p, 0, duration))
        dispatchGesture(b.build(), null, null)
    }

    // Screenshot -> Bitmap software (null kalau gagal / Android < 11)
    private fun shoot(cb: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) { cb(null); return }
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY, mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(res: AccessibilityService.ScreenshotResult) {
                        var bmp: Bitmap? = null
                        try {
                            val hb = res.hardwareBuffer
                            val hw = Bitmap.wrapHardwareBuffer(hb, res.colorSpace)
                            bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                            hw?.recycle()
                            hb.close()
                        } catch (e: Exception) {
                            Log.d("TC", "screenshot error: $e")
                        }
                        cb(bmp)
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.d("TC", "screenshot gagal, kode=$errorCode")
                        cb(null)
                    }
                })
        } catch (e: Exception) {
            Log.d("TC", "takeScreenshot error: $e")
            cb(null)
        }
    }

    // Kata <= 3 huruf harus sama persis, sisanya cukup "mengandung"
    private fun matchKw(n: AccessibilityNodeInfo, kws: List<String>): Boolean {
        val parts = listOfNotNull(n.contentDescription, n.text)
            .map { it.toString().lowercase().trim() }
        return parts.any { p ->
            p.isNotEmpty() && kws.any { k -> if (k.length <= 3) p == k else p.contains(k) }
        }
    }

    private fun findAll(root: AccessibilityNodeInfo, kws: List<String>): List<Cand> {
        val out = ArrayList<Cand>()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            if (matchKw(n, kws)) {
                val r = Rect()
                n.getBoundsInScreen(r)
                if (r.width() > 0 && r.height() > 0) out.add(Cand(n, r))
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return out
    }

    // Baris tab profil: >= 4 node clickable selebar ~1/5 layar di satu baris
    private fun findTabRow(root: AccessibilityNodeInfo, sw: Int, sh: Int): List<Rect>? {
        val all = ArrayList<Rect>()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            if (n.isClickable) {
                val r = Rect()
                n.getBoundsInScreen(r)
                if (r.width() > sw * 0.13 && r.width() < sw * 0.27 &&
                    r.height() < sh * 0.1 && r.top > sh * 0.15 && r.top < sh * 0.7) all.add(r)
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        val sorted = all.sortedBy { it.top }
        var best: List<Rect> = emptyList()
        var cur = ArrayList<Rect>()
        for (r in sorted) {
            if (cur.isNotEmpty() && r.top - cur[0].top > 20) {
                if (cur.size > best.size) best = cur
                cur = ArrayList()
            }
            cur.add(r)
        }
        if (cur.size > best.size) best = cur
        return if (best.size >= 4) best.sortedBy { it.left } else null
    }

    // Kotak video di grid: node clickable berukuran sekitar 1/3 lebar layar
    private fun collectCells(n: AccessibilityNodeInfo?, sw: Int, out: MutableList<Rect>) {
        if (n == null) return
        if (n.isClickable) {
            val r = Rect()
            n.getBoundsInScreen(r)
            if (r.width() > sw * 0.25 && r.width() < sw * 0.5 &&
                r.height() >= r.width() * 0.9 && r.top >= tabBottom - 5) out.add(r)
        }
        for (i in 0 until n.childCount) collectCells(n.getChild(i), sw, out)
    }

    // ---------- Diagnostik (tersembunyi di Pengaturan) ----------

    fun dumpScreen() {
        val root = rootInActiveWindow
        if (root == null) { toast("Screen unreadable"); return }
        val sb = StringBuilder()
        sb.append("pkg=${root.packageName} screen=${screen()}\n")
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null) return
            val r = Rect()
            n.getBoundsInScreen(r)
            sb.append("  ".repeat(d)).append(n.className)
                .append(" id=").append(n.viewIdResourceName)
                .append(" txt=").append(n.text)
                .append(" desc=").append(n.contentDescription)
                .append(" [${r.left},${r.top},${r.right},${r.bottom}]")
            if (n.isClickable) sb.append(" CLICK")
            if (n.isSelected) sb.append(" SEL")
            sb.append("\n")
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
        }
        walk(root, 0)
        val text = sb.toString().take(200_000)
        try {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("diag", text))
        } catch (_: Exception) {}
        try { File(getExternalFilesDir(null), "diag.txt").writeText(text) } catch (_: Exception) {}
        toast("Copied (${text.length})")
    }

    // ---------- Tombol STOP melayang (bisa digeser) ----------

    private fun showOverlay() {
        if (overlay != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(18), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F2141418"))
                cornerRadius = dp(26).toFloat()
                setStroke(dp(1), Color.parseColor("#33333C"))
            }
        }
        val iconView = ImageView(this).apply {
            setImageDrawable(IconDrawable(IconDrawable.Kind.STOP, Color.WHITE))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FE2C55"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
        }
        val label = TextView(this).apply {
            text = tr("Berhenti", "Stop") + "  ·  $doneCount"
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), 0, 0, 0)
        }
        pill.addView(iconView)
        pill.addView(label)
        overlayLabel = label

        val (sw, _) = screen()
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,   // layar tetap menyala
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = sw - dp(170)
            y = dp(90)
        }

        var sx = 0
        var sy = 0
        var tx = 0f
        var ty = 0f
        var moved = false
        pill.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY; moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - tx).toInt()
                    val dy = (e.rawY - ty).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    if (moved) {
                        lp.x = sx + dx; lp.y = sy + dy
                        try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) stopCleaning()
                    true
                }
                else -> false
            }
        }
        overlay = pill
        wm.addView(pill, lp)
    }

    private fun hideOverlay() {
        val o = overlay ?: return
        try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(o) }
        catch (_: Exception) {}
        overlay = null
        overlayLabel = null
    }
}