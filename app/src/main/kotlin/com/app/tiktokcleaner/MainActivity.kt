package com.app.tiktokcleaner

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        const val APP_NAME = "Tikcleaner"
        const val VERSION = "1.0.0"
        const val COPYRIGHT = "© 2026 Aeryth Systems × Adanndev. All rights reserved."
        val LIMITS = listOf(0, 50, 100, 250, 500)
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var body: LinearLayout

    private var dark = true
    private var isId = true
    private var cBg = 0
    private var cSurface = 0
    private var cSurface2 = 0
    private var cStroke = 0
    private var cText = 0
    private var cSub = 0
    private var cAccent = 0
    private var cOk = 0

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private var appSel = 0
    private var modeSel = 0
    private var speedSel = 1
    private var limitSel = 0
    private var extraTab: EditText? = null
    private var extraUndo: EditText? = null

    private var renderedRunning: Boolean? = null
    private var renderedActive: Boolean? = null
    private var tvCount: TextView? = null
    private var tvStatus: TextView? = null
    private var tvElapsed: TextView? = null
    private var tvAvg: TextView? = null
    private var tvSkip: TextView? = null
    private val ui = Handler(Looper.getMainLooper())
    private val clock = object : Runnable {
        override fun run() {
            if (CleanerService.isRunning) {
                updateLive()
                ui.postDelayed(this, 1000)
            }
        }
    }

    // ---------- Util ----------

    private fun s(id: String, en: String) = if (isId) id else en
    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun shape(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun ripple(d: Drawable, radius: Int): Drawable = RippleDrawable(
        ColorStateList.valueOf(if (dark) 0x22FFFFFF else 0x22000000), d, shape(Color.WHITE, radius))

    private fun tv(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun save(key: String, v: Int) = prefs.edit().putInt(key, v).apply()

    private fun setupPalette() {
        isId = when (prefs.getInt("lang", -1)) {
            0 -> true
            1 -> false
            else -> Locale.getDefault().language.let { it == "in" || it == "id" }
        }
        dark = when (prefs.getInt("theme", 0)) {
            1 -> false
            2 -> true
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }
        cAccent = Color.parseColor("#FE2C55")   // merah TikTok
        if (dark) {
            cBg = Color.parseColor("#0B0B0E"); cSurface = Color.parseColor("#141418")
            cSurface2 = Color.parseColor("#1C1C22"); cStroke = Color.parseColor("#26262E")
            cText = Color.parseColor("#F4F4F6"); cSub = Color.parseColor("#8E8E99")
            cOk = Color.parseColor("#25F4EE")   // cyan TikTok
        } else {
            cBg = Color.parseColor("#F5F5F7"); cSurface = Color.parseColor("#FFFFFF")
            cSurface2 = Color.parseColor("#F0F0F3"); cStroke = Color.parseColor("#E3E3E8")
            cText = Color.parseColor("#121214"); cSub = Color.parseColor("#6B6B76")
            cOk = Color.parseColor("#0FB5AE")
        }
    }

    private fun card(title: String?, vararg content: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(16))
        background = shape(cSurface, 16, cStroke)
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) }
        if (title != null) addView(tv(title, 12f, cSub, true).apply { setPadding(0, 0, 0, dp(10)) })
        content.forEach { addView(it) }
    }

    private fun segmented(
        labels: List<String>, icons: List<IconDrawable.Kind?>, initial: Int, onSelect: (Int) -> Unit
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = shape(cSurface2, 12)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val segs = ArrayList<LinearLayout>()
        val texts = ArrayList<TextView>()
        val icos = ArrayList<IconDrawable?>()
        fun paint(sel: Int) {
            segs.forEachIndexed { i, seg ->
                val on = i == sel
                seg.background = if (on) shape(cAccent, 9) else null
                texts[i].setTextColor(if (on) Color.WHITE else cSub)
                texts[i].setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
                icos[i]?.setColor(if (on) Color.WHITE else cSub)
            }
        }
        labels.forEachIndexed { i, label ->
            val seg = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(11), dp(4), dp(11))
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            }
            val kind = icons.getOrNull(i)
            var d: IconDrawable? = null
            if (kind != null) {
                d = IconDrawable(kind, cSub)
                seg.addView(ImageView(this).apply {
                    setImageDrawable(d)
                    layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { rightMargin = dp(6) }
                })
            }
            val t = tv(label, 12.5f, cSub)
            seg.addView(t)
            seg.setOnClickListener { paint(i); onSelect(i) }
            segs.add(seg); texts.add(t); icos.add(d)
            row.addView(seg)
        }
        paint(initial)
        return row
    }

    private fun bigButton(
        label: String, kind: IconDrawable.Kind, bg: Int, fg: Int, onClick: () -> Unit
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        isClickable = true
        background = ripple(shape(bg, 14), 14)
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(54)).apply { topMargin = dp(6) }
        addView(ImageView(this@MainActivity).apply {
            setImageDrawable(IconDrawable(kind, fg))
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(10) }
        })
        addView(tv(label, 16f, fg, true))
        setOnClickListener { onClick() }
    }

    private fun softButton(label: String, kind: IconDrawable.Kind, onClick: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = true
            background = ripple(shape(cSurface2, 12, cStroke), 12)
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(46)).apply { topMargin = dp(12) }
            addView(ImageView(this@MainActivity).apply {
                setImageDrawable(IconDrawable(kind, cText))
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(8) }
            })
            addView(tv(label, 14f, cText, true))
            setOnClickListener { onClick() }
        }

    private fun field(hintText: String) = EditText(this).apply {
        hint = hintText
        textSize = 13f
        setTextColor(cText)
        setHintTextColor(cSub)
        background = shape(cSurface2, 10, cStroke)
        setPadding(dp(12), dp(11), dp(12), dp(11))
        setSingleLine()
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }
    }

    // ---------- Lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("aeryth", MODE_PRIVATE)
        appSel = prefs.getInt("app", 0)
        modeSel = prefs.getInt("mode", 0)
        speedSel = prefs.getInt("speed", 1)
        limitSel = prefs.getInt("limit", 0)
        setupPalette()

        window.statusBarColor = cBg
        window.navigationBarColor = cBg
        @Suppress("DEPRECATION")
        run {
            var flags = window.decorView.systemUiVisibility
            flags = if (!dark)
                flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            else
                flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            window.decorView.systemUiVisibility = flags
        }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(cBg)
            isVerticalScrollBarEnabled = false
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
        }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(buildHeader())
        root.addView(body)
        scroll.addView(root)
        setContentView(scroll)

        renderBody()
        if (!prefs.getBoolean("consent", false)) showConsent()
    }

    override fun onResume() {
        super.onResume()
        CleanerService.listener = { refresh() }
        refresh()
        ui.removeCallbacks(clock)
        ui.post(clock)
    }

    override fun onPause() {
        super.onPause()
        CleanerService.listener = null
        ui.removeCallbacks(clock)
    }

    private fun refresh() {
        val running = CleanerService.isRunning
        val active = CleanerService.instance != null
        if (renderedRunning != running || renderedActive != active) {
            renderBody()
            ui.removeCallbacks(clock)
            ui.post(clock)
        } else if (running) updateLive()
    }

    // ---------- Header ----------

    private fun headerButton(kind: IconDrawable.Kind, onClick: () -> Unit) = ImageView(this).apply {
        setImageDrawable(IconDrawable(kind, cText))
        setPadding(dp(10), dp(10), dp(10), dp(10))
        background = ripple(shape(cSurface, 12, cStroke), 12)
        layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { leftMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun buildHeader() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, dp(18))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            addView(tv(APP_NAME, 26f, cText, true))
            addView(tv(s("Pembersih aktivitas TikTok", "TikTok activity cleaner"), 12.5f, cSub))
        })
        addView(headerButton(IconDrawable.Kind.CLOCK) { showHistory() })
        addView(headerButton(IconDrawable.Kind.SLIDERS) { showSettings() })
    }

    // ---------- Body ----------

    private fun renderBody() {
        body.removeAllViews()
        tvCount = null; tvStatus = null; tvElapsed = null; tvAvg = null; tvSkip = null
        renderedRunning = CleanerService.isRunning
        renderedActive = CleanerService.instance != null
        if (renderedRunning == true) buildRunning() else buildIdle()
    }

    private fun modeHint(i: Int) = listOf(
        s("Membatalkan like pada video di tab Suka.", "Removes your like from videos in the Liked tab."),
        s("Menghapus video dari tab Favorit.", "Removes videos from the Favorites tab."),
        s("Membatalkan repost pada video di tab Repost.", "Undoes reposts on videos in the Reposts tab.")
    )[i]

    private fun serviceCard(active: Boolean): View {
        if (active) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(this@MainActivity).apply {
                    setImageDrawable(IconDrawable(IconDrawable.Kind.CHECK, Color.BLACK))
                    setPadding(dp(7), dp(7), dp(7), dp(7))
                    background = shape(cOk, 20)
                    layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { rightMargin = dp(12) }
                })
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(tv(s("Layanan aktif", "Service active"), 14f, cText, true))
                    addView(tv(s("Siap digunakan", "Ready to use"), 12f, cSub))
                })
            }
            return card(null, row)
        }
        val head = tv(s("Aktifkan layanan aksesibilitas", "Enable accessibility service"), 15f, cText, true)
        val desc = tv(
            s("Buka Pengaturan Aksesibilitas, pilih $APP_NAME, lalu aktifkan. " +
                "Di Android 13+, jika tombol abu-abu: Info Aplikasi, menu titik tiga, Izinkan setelan terbatas.",
              "Open Accessibility settings, choose $APP_NAME and turn it on. " +
                "On Android 13+, if the switch is greyed out: App info, three-dot menu, Allow restricted settings."),
            12.5f, cSub).apply { setPadding(0, dp(8), 0, 0) }
        val btn = softButton(s("Buka Pengaturan Aksesibilitas", "Open Accessibility Settings"),
            IconDrawable.Kind.ACCESS) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        return card(null, head, desc, btn)
    }

    private fun buildIdle() {
        // Ringkasan sesi terakhir
        if (CleanerService.doneCount > 0 && CleanerService.lastStatus.isNotEmpty()) {
            body.addView(card(s("Sesi terakhir", "Last session"),
                tv(CleanerService.lastStatus, 14f, cText, true),
                tv(s("Dilewati: ", "Skipped: ") + CleanerService.skipCount, 12f, cSub)
                    .apply { setPadding(0, dp(4), 0, 0) }))
        }

        body.addView(serviceCard(renderedActive == true))

        body.addView(card(s("Aplikasi", "App"),
            segmented(listOf("TikTok", "TikTok Lite"),
                listOf<IconDrawable.Kind?>(null, null), appSel) { appSel = it; save("app", it) }))

        val hint = tv(modeHint(modeSel), 12.5f, cSub).apply { setPadding(0, dp(10), 0, 0) }
        body.addView(card(s("Mode", "Mode"),
            segmented(listOf("Unlike", "Unfavorite", "Unrepost"),
                listOf(IconDrawable.Kind.HEART, IconDrawable.Kind.BOOKMARK, IconDrawable.Kind.REPOST),
                modeSel) { modeSel = it; save("mode", it); hint.text = modeHint(it) },
            hint))

        body.addView(card(s("Kecepatan", "Speed"),
            segmented(listOf(s("Aman", "Safe"), s("Cepat", "Fast"), "Turbo"),
                listOf<IconDrawable.Kind?>(null, null, IconDrawable.Kind.BOLT),
                speedSel) { speedSel = it; save("speed", it) },
            tv(s("Waktu tunggu menyesuaikan kondisi jaringan secara otomatis. " +
                    "Kecepatan lebih tinggi meningkatkan risiko pembatasan dari platform.",
                 "Wait times adapt to your network automatically. " +
                    "Higher speed increases the risk of platform restrictions."),
                12f, cSub).apply { setPadding(0, dp(10), 0, 0) }))

        body.addView(card(s("Batas sesi (video)", "Session limit (videos)"),
            segmented(listOf(s("Semua", "All"), "50", "100", "250", "500"),
                listOf<IconDrawable.Kind?>(null, null, null, null, null),
                limitSel) { limitSel = it; save("limit", it) }))

        val et = field(s("Kata kunci tab tambahan (pisah koma)", "Extra tab keywords (comma separated)"))
        val eu = field(s("Label 'Undo repost' tambahan (pisah koma)", "Extra 'Undo repost' labels (comma separated)"))
        extraTab = et; extraUndo = eu
        val advBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(et); addView(eu)
        }
        val advHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(tv(s("Lanjutan", "Advanced"), 14f, cText, true).apply {
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            })
            addView(ImageView(this@MainActivity).apply {
                setImageDrawable(IconDrawable(IconDrawable.Kind.CHEVRON_DOWN, cSub))
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
            })
            setOnClickListener {
                advBody.visibility = if (advBody.visibility == View.GONE) View.VISIBLE else View.GONE
            }
        }
        body.addView(card(null, advHeader, advBody))

        body.addView(bigButton(s("Mulai", "Start"), IconDrawable.Kind.PLAY, cAccent, Color.WHITE) { startSession() })

        body.addView(tv(COPYRIGHT, 11f, cSub).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(22), dp(8), 0)
        })
    }

    private fun statTile(kind: IconDrawable.Kind, label: String): Pair<View, TextView> {
        val value = tv("-", 16f, cText, true)
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = shape(cSurface2, 12)
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) }
            addView(ImageView(this@MainActivity).apply {
                setImageDrawable(IconDrawable(kind, cAccent))
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
            })
            addView(value.apply { setPadding(0, dp(8), 0, 0) })
            addView(tv(label, 11.5f, cSub))
        }
        return tile to value
    }
    private fun buildRunning() {
        val count = tv("0", 48f, cText, true)
        val state = tv("", 13f, cSub).apply { setPadding(0, dp(6), 0, 0) }
        tvCount = count; tvStatus = state

        val (t1, v1) = statTile(IconDrawable.Kind.CLOCK, s("Waktu berjalan", "Elapsed"))
        val (t2, v2) = statTile(IconDrawable.Kind.BOLT, s("Rata-rata / video", "Avg / video"))
        val (t3, v3) = statTile(IconDrawable.Kind.FORWARD, s("Dilewati", "Skipped"))
        tvElapsed = v1; tvAvg = v2; tvSkip = v3
        (t3.layoutParams as LinearLayout.LayoutParams).rightMargin = 0

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(View(this@MainActivity).apply {
                background = shape(cOk, 5)
                layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(8) }
            })
            addView(tv(s("Sedang berjalan", "Running"), 13f, cOk, true))
        }
        body.addView(card(null, head, count,
            tv(s("video dibersihkan", "videos cleaned"), 13f, cSub), state))

        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) }
            addView(t1); addView(t2); addView(t3)
        })

        body.addView(bigButton(s("Berhenti", "Stop"), IconDrawable.Kind.STOP, cSurface2, cAccent) {
            CleanerService.instance?.stopCleaning()
            refresh()
        })
        body.addView(tv(
            s("Tombol berhenti melayang juga tersedia di layar dan bisa digeser.",
              "A floating stop button is also on screen and can be dragged."),
            12f, cSub).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(16), dp(8), 0)
        })
        updateLive()
    }

    private fun updateLive() {
        val c = CleanerService
        tvCount?.text = c.doneCount.toString()
        tvStatus?.text = c.lastStatus
        val el = (SystemClock.uptimeMillis() - c.startedAt) / 1000
        tvElapsed?.text = String.format(Locale.US, "%02d:%02d", el / 60, el % 60)
        tvAvg?.text = if (c.cycleCount > 0)
            String.format(Locale.US, "%.1f s", c.cycleSum / c.cycleCount / 1000.0) else "-"
        tvSkip?.text = c.skipCount.toString()
    }

    // ---------- Aksi ----------

    private fun startSession() {
        val svc = CleanerService.instance
        if (svc == null) {
            Toast.makeText(this,
                s("Aktifkan layanan aksesibilitas dulu", "Enable the accessibility service first"),
                Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        val pkgs = if (appSel == 0)
            listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")
        else
            listOf("com.zhiliaoapp.musically.go")
        val launch = pkgs.firstNotNullOfOrNull { packageManager.getLaunchIntentForPackage(it) }
        if (launch == null) {
            Toast.makeText(this,
                s("Aplikasi yang dipilih tidak terpasang", "The selected app is not installed"),
                Toast.LENGTH_LONG).show()
            return
        }

        val tabBase = listOf(
            listOf("liked", "disukai"),
            listOf("favorites", "favorite", "favorit", "bookmark", "saved", "disimpan"),
            listOf("reposts", "repost", "posting ulang")
        )[modeSel]
        val tabIdx = listOf(3, 1, 2)[modeSel]

        fun parse(t: String?) = (t ?: "").split(",")
            .map { it.trim().lowercase() }.filter { it.isNotEmpty() }

        val ok = svc.startCleaning(
            modeSel,
            tabBase + parse(extraTab?.text?.toString()),
            parse(extraUndo?.text?.toString()),
            tabIdx,
            speedSel,
            isId,
            LIMITS[limitSel],
            pkgs
        )
        if (ok) startActivity(launch)
    }

    // ---------- Dialog ----------

    private fun makeDialog(content: View): Dialog {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setContentView(content)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout((resources.displayMetrics.widthPixels * 0.9f).toInt(), WRAP)
        return d
    }

    private fun dialogBox() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(20), dp(20), dp(18))
        background = shape(cSurface, 20)
    }

    private fun termsText() = s(
        "SYARAT PENGGUNAAN\n" +
            "1. $APP_NAME adalah alat bantu pribadi untuk membersihkan aktivitas akunmu sendiri.\n" +
            "2. Aplikasi ini tidak berafiliasi dengan, didukung, atau disponsori oleh TikTok atau ByteDance.\n" +
            "3. Otomatisasi dapat melanggar ketentuan layanan platform dan berisiko menyebabkan pembatasan atau penangguhan akun. Kamu menggunakannya sepenuhnya atas risiko sendiri.\n" +
            "4. Aplikasi diberikan apa adanya tanpa jaminan. Pengembang tidak bertanggung jawab atas kehilangan data, pembatasan akun, atau kerugian lain.\n" +
            "5. Dilarang digunakan pada akun milik orang lain tanpa izin.\n\n" +
            "KEBIJAKAN PRIVASI\n" +
            "1. Aplikasi tidak mengumpulkan, menyimpan di server, atau mengirim data pribadi apa pun.\n" +
            "2. Layanan Aksesibilitas hanya dipakai saat sesi berjalan, untuk membaca elemen layar aplikasi target dan menyimulasikan ketukan serta geseran.\n" +
            "3. Tangkapan layar diproses di perangkat secara sementara untuk mendeteksi status ikon dan langsung dibuang.\n" +
            "4. Pengaturan dan riwayat sesi hanya tersimpan lokal di perangkatmu dan terhapus saat aplikasi di-uninstall.\n\n" +
            COPYRIGHT,
        "TERMS OF USE\n" +
            "1. $APP_NAME is a personal tool for cleaning up the activity of your own account.\n" +
            "2. This app is not affiliated with, endorsed by, or sponsored by TikTok or ByteDance.\n" +
            "3. Automation may violate the platform's terms of service and may lead to account restrictions or suspension. You use it entirely at your own risk.\n" +
            "4. The app is provided as is, without warranty. The developers are not liable for data loss, account restrictions, or any other damages.\n" +
            "5. Do not use it on accounts you do not own without permission.\n\n" +
            "PRIVACY POLICY\n" +
            "1. The app does not collect, store on servers, or transmit any personal data.\n" +
            "2. The Accessibility service is only used while a session is running, to read on-screen elements of the target app and to simulate taps and swipes.\n" +
            "3. Screenshots are processed temporarily on the device to detect icon states and are discarded immediately.\n" +
            "4. Settings and session history are stored locally on your device only and are removed when the app is uninstalled.\n\n" +
            COPYRIGHT)

    private fun showTerms() {
        val c = dialogBox()
        c.addView(tv(s("Syarat & Privasi", "Terms & Privacy"), 18f, cText, true))
        val sv = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(360)).apply { topMargin = dp(12) }
            addView(tv(termsText(), 12.5f, cSub))
        }
        c.addView(sv)
        val d = makeDialog(c)
        c.addView(softButton(s("Tutup", "Close"), IconDrawable.Kind.CHECK) { d.dismiss() })
        d.show()
    }

    private fun showConsent() {
        val c = dialogBox()
        c.addView(tv(s("Izin Aksesibilitas", "Accessibility permission"), 18f, cText, true))
        c.addView(tv(
            s("$APP_NAME memakai layanan Aksesibilitas Android untuk membaca elemen layar aplikasi target, " +
                "mensimulasikan ketukan dan geseran atas perintahmu, serta mengambil tangkapan layar sementara " +
                "untuk mendeteksi status ikon. Tidak ada data yang dikumpulkan atau dikirim ke mana pun. " +
                "Layanan hanya bekerja saat kamu menekan Mulai.",
              "$APP_NAME uses the Android Accessibility service to read the target app's screen elements, " +
                "simulate taps and swipes on your command, and take temporary screenshots to detect icon states. " +
                "No data is collected or sent anywhere. The service only works while you press Start."),
            13.5f, cSub).apply { setPadding(0, dp(12), 0, dp(14)) })
        val d = makeDialog(c)
        d.setCancelable(false)
        c.addView(bigButton(s("Setuju", "Agree"), IconDrawable.Kind.CHECK, cAccent, Color.WHITE) {
            prefs.edit().putBoolean("consent", true).apply()
            d.dismiss()
        })
        c.addView(softButton(s("Baca Syarat & Privasi", "Read Terms & Privacy"), IconDrawable.Kind.SHIELD) { showTerms() })
        c.addView(softButton(s("Tolak", "Decline"), IconDrawable.Kind.STOP) { d.dismiss(); finish() })
        d.show()
    }

    private fun showHistory() {
        val c = dialogBox()
        c.addView(tv(s("Riwayat sesi", "Session history"), 18f, cText, true))
        val lines = (prefs.getString("history", "") ?: "").split("\n").filter { it.isNotBlank() }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (lines.isEmpty()) {
            list.addView(tv(s("Belum ada riwayat.", "No history yet."), 13f, cSub)
                .apply { setPadding(0, dp(14), 0, dp(6)) })
        }
        val fmt = SimpleDateFormat("dd MMM · HH:mm", Locale.getDefault())
        val names = listOf("Unlike", "Unfavorite", "Unrepost")
        lines.forEach { ln ->
            val p = ln.split(";")
            if (p.size >= 6) {
                val ts = p[0].toLongOrNull() ?: 0L
                val dur = p[4].toLongOrNull() ?: 0L
                val reason = when (p[5]) {
                    "done" -> s("Selesai", "Finished")
                    "limit" -> s("Batas tercapai", "Limit reached")
                    "stop" -> s("Dihentikan", "Stopped")
                    else -> s("Terhenti", "Interrupted")
                }
                list.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = shape(cSurface2, 12)
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }
                    addView(tv("${names.getOrElse(p[1].toIntOrNull() ?: 0) { "-" }}  ·  ${p[2]} video",
                        14f, cText, true))
                    addView(tv("${fmt.format(Date(ts))}  ·  " +
                        String.format(Locale.US, "%02d:%02d", dur / 60, dur % 60) +
                        "  ·  ${s("lewati", "skipped")} ${p[3]}  ·  $reason", 11.5f, cSub))
                })
            }
        }
        val sv = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, if (lines.size > 4) dp(320) else WRAP)
            addView(list)
        }
        c.addView(sv)
        val d = makeDialog(c)
        c.addView(softButton(s("Tutup", "Close"), IconDrawable.Kind.CHECK) { d.dismiss() })
        d.show()
    }

    private fun showSettings() {
        val c = dialogBox()
        c.addView(tv(s("Pengaturan", "Settings"), 18f, cText, true))
        val d = makeDialog(c)

        c.addView(tv(s("Tema", "Theme"), 12f, cSub, true).apply { setPadding(0, dp(16), 0, dp(8)) })
        c.addView(segmented(
            listOf(s("Sistem", "System"), s("Terang", "Light"), s("Gelap", "Dark")),
            listOf<IconDrawable.Kind?>(null, IconDrawable.Kind.SUN, IconDrawable.Kind.MOON),
            prefs.getInt("theme", 0)) {
            save("theme", it); d.dismiss(); recreate()
        })

        c.addView(tv(s("Bahasa", "Language"), 12f, cSub, true).apply { setPadding(0, dp(16), 0, dp(8)) })
        c.addView(segmented(
            listOf("Indonesia", "English"),
            listOf<IconDrawable.Kind?>(IconDrawable.Kind.GLOBE, IconDrawable.Kind.GLOBE),
            if (isId) 0 else 1) {
            save("lang", it); d.dismiss(); recreate()
        })

        c.addView(softButton(s("Syarat & Privasi", "Terms & Privacy"), IconDrawable.Kind.SHIELD) { showTerms() })

        var taps = 0
        c.addView(tv("$APP_NAME v$VERSION\n$COPYRIGHT", 11.5f, cSub).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, dp(6))
            setOnClickListener {
                taps++
                if (taps >= 5) {
                    taps = 0
                    Toast.makeText(this@MainActivity,
                        "Diagnostics in 5 s. Switch to the target screen.", Toast.LENGTH_LONG).show()
                    Handler(Looper.getMainLooper()).postDelayed({
                        CleanerService.instance?.dumpScreen()
                    }, 5000)
                }
            }
        })
        c.addView(softButton(s("Tutup", "Close"), IconDrawable.Kind.CHECK) { d.dismiss() })
        d.show()
    }
}