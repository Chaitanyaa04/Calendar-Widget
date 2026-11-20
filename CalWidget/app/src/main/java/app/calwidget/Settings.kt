package app.calwidget

import android.app.*
import android.appwidget.AppWidgetManager
import android.content.*
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

private val BG = 0xFF000000.toInt()
private val CARD = 0xFF111113.toInt()
private val CARD2 = 0xFF1D1D21.toInt()
private val TXT = 0xFFF2F2F5.toInt()
private val SUB = 0xFF9A9AA5.toInt()
private val ACC = 0xFFFF4081.toInt()
private val PALETTE = listOf(0xFF000000, 0xFF1C1C1E, 0xFF3A3A3C, 0xFF636366, 0xFF8E8E93, 0xFFC7C7CC, 0xFFFFFFFF, 0xFFFF4081, 0xFFF44336, 0xFFFF6E40, 0xFFFF9800, 0xFFFFC107, 0xFFFFEB3B, 0xFFCDDC39, 0xFF8BC34A, 0xFF66BB6A, 0xFF1DE9B6, 0xFF26C6DA, 0xFF29B6F6, 0xFF2979FF, 0xFF3F51B5, 0xFF7C4DFF, 0xFFAB47BC, 0xFFE040FB, 0xFF8D6E63).map { it.toInt() }
private val RAINBOW = intArrayOf(0xFFFF0000.toInt(), 0xFFFFFF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FFFF.toInt(), 0xFF0000FF.toInt(), 0xFFFF00FF.toInt(), 0xFFFF0000.toInt())
private val PRESETS = listOf(0xFF000000, 0xFFFFFFFF, 0xFF212121, 0xFFFF4081, 0xFF7C4DFF, 0xFF2979FF, 0xFF1DE9B6, 0xFF66BB6A, 0xFFFFC107, 0xFFFF6E40, 0xFFF44336, 0xFFBDBDBD).map { it.toInt() }

open class SettingsActivity : Activity() {
    private lateinit var p: Prefs
    private var wid = 0
    private lateinit var holder: FrameLayout
    private var dateBtn: TextView? = null
    private var banner: TextView? = null
    private var updStatus: TextView? = null
    private var updBtn: TextView? = null
    private var dlId = -1L
    private val doneRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { if (i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) == dlId) installNow() }
    }

    // ---------- tiny UI toolkit ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun rr(c: Int, r: Int) = GradientDrawable().apply { setColor(c); cornerRadius = dp(r).toFloat() }
    private fun dot(c: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c); setStroke(dp(1), 0x44FFFFFF) }
    private fun csl(on: Int, off: Int) = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(on, off))
    private fun lp(w: Int = -1, h: Int = -2, wt: Float = 0f) = LinearLayout.LayoutParams(w, h, wt)
    private fun tx(t: String, size: Float, col: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(col); if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private fun save(k: String, v: Int) { p.put(k, v); CalWidget.refresh(this); preview() }

    private fun header(c: LinearLayout, title: String, sub: String) {
        c.addView(tx(title, 32f, TXT, true).apply { setPadding(dp(4), dp(10), 0, 0) })
        c.addView(tx(sub, 13f, SUB).apply { setPadding(dp(4), dp(2), 0, dp(16)) })
    }

    private fun card(c: LinearLayout, title: String): LinearLayout {
        c.addView(tx(title.uppercase(), 11f, SUB, true).apply { letterSpacing = 0.12f; setPadding(dp(6), dp(8), 0, dp(6)) })
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = rr(CARD, 20); setPadding(dp(18), dp(10), dp(18), dp(12)) }
        c.addView(box, lp().apply { setMargins(0, 0, 0, dp(10)) })
        return box
    }

    private fun more(c: LinearLayout, title: String = "More options"): LinearLayout {
        val arrow = tx("▾", 18f, SUB)
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; background = rr(CARD, 20); setPadding(dp(18), dp(14), dp(18), dp(14)) }
        head.addView(tx(title, 15f, TXT, true), lp(0, -2, 1f)); head.addView(arrow)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        head.setOnClickListener { val open = body.visibility == View.GONE; body.visibility = if (open) View.VISIBLE else View.GONE; arrow.text = if (open) "▴" else "▾" }
        c.addView(head, lp().apply { setMargins(0, dp(6), 0, dp(10)) }); c.addView(body)
        return body
    }

    private fun swRow(c: LinearLayout, title: String, on: Boolean, f: (Boolean) -> Unit) {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
        row.addView(tx(title, 15f, TXT), lp(0, -2, 1f))
        row.addView(Switch(this).apply { isChecked = on; thumbTintList = csl(ACC, SUB); trackTintList = csl((ACC and 0xFFFFFF) or 0x66000000, CARD2); setOnCheckedChangeListener { _, v -> f(v) } })
        c.addView(row)
    }

    private fun texts(title: String, sub: String?) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(tx(title, 15f, TXT))
        if (sub != null) addView(tx(sub, 12f, SUB))
    }

    private fun toggle(c: LinearLayout, title: String, sub: String?, k: String, after: ((Boolean) -> Unit)? = null) {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
        row.addView(texts(title, sub), lp(0, -2, 1f))
        row.addView(Switch(this).apply {
            isChecked = p.i(k) == 1; thumbTintList = csl(ACC, SUB); trackTintList = csl((ACC and 0xFFFFFF) or 0x66000000, CARD2)
            setOnCheckedChangeListener { _, on -> save(k, if (on) 1 else 0); after?.invoke(on) }
        })
        c.addView(row)
    }

    private fun seg(c: LinearLayout, title: String, k: String, opts: List<String>) {
        c.addView(tx(title, 14f, TXT).apply { setPadding(0, dp(12), 0, dp(6)) })
        val vertical = opts.sumOf { it.length } > 30
        val box = LinearLayout(this).apply { orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL; background = rr(CARD2, 14); setPadding(dp(3), dp(3), dp(3), dp(3)) }
        val cells = ArrayList<TextView>()
        fun paint() = cells.forEachIndexed { i, t -> t.background = if (p.i(k) == i) rr(ACC, 11) else null; t.setTextColor(if (p.i(k) == i) Color.WHITE else SUB) }
        opts.forEachIndexed { i, o ->
            val t = tx(o, 13f, SUB, true).apply { gravity = Gravity.CENTER; setPadding(dp(4), dp(10), dp(4), dp(10)); setOnClickListener { save(k, i); paint() } }
            cells.add(t); box.addView(t, if (vertical) lp() else lp(0, -2, 1f))
        }
        paint(); c.addView(box)
    }

    private fun slider(c: LinearLayout, title: String, k: String, min: Int, max: Int, unit: String) {
        val v = tx("${p.i(k)}$unit", 13f, SUB)
        val head = LinearLayout(this).apply { setPadding(0, dp(12), 0, 0) }
        head.addView(tx(title, 14f, TXT), lp(0, -2, 1f)); head.addView(v)
        c.addView(head)
        c.addView(SeekBar(this).apply {
            this.max = max - min; progress = p.i(k) - min; progressTintList = ColorStateList.valueOf(ACC); thumbTintList = ColorStateList.valueOf(ACC)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, x: Int, user: Boolean) { v.text = "${x + min}$unit" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { save(k, progress + min) }
            })
        })
    }

    private fun button(t: String, primary: Boolean = true, click: () -> Unit) = tx(t, 14f, if (primary) Color.WHITE else TXT, true).apply {
        gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(14)); background = rr(if (primary) ACC else CARD2, 14)
        layoutParams = lp().apply { setMargins(0, dp(6), 0, dp(6)) }; setOnClickListener { click() }
    }

    private fun field(c: LinearLayout, hint: String, k: String) {
        c.addView(EditText(this).apply {
            this.hint = hint; setHintTextColor(SUB); setTextColor(TXT); textSize = 14f; setSingleLine(); setText(p.s(k))
            background = rr(CARD2, 12); setPadding(dp(14), dp(12), dp(14), dp(12))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { p.putS(k, s.toString()); CalWidget.refresh(this@SettingsActivity); preview() }
            })
        }, lp().apply { setMargins(0, dp(4), 0, dp(8)) })
    }

    private fun colorRow(c: LinearLayout, title: String, k: String) {
        val sw = View(this).apply { background = dot(p.i(k)) }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, dp(10))
            setOnClickListener { pickColor(title, p.i(k)) { col -> save(k, col); sw.background = dot(col) } } }
        row.addView(tx(title, 15f, TXT), lp(0, -2, 1f)); row.addView(sw, lp(dp(30), dp(30)))
        c.addView(row)
    }

    private fun pickColor(title: String, start: Int, onPick: (Int) -> Unit) {
        var cur = start
        var dlg: AlertDialog? = null
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val row = LinearLayout(this).apply { setPadding(0, dp(6), 0, dp(6)) }
        val sws = ArrayList<Pair<View, Int>>()
        fun mark() = sws.forEach { (v, col) ->
            val on = (col and 0xFFFFFF) == (cur and 0xFFFFFF)
            v.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(col or 0xFF000000.toInt()); setStroke(dp(if (on) 3 else 1), if (on) Color.WHITE else 0x44FFFFFF) }
        }
        PALETTE.forEach { col ->
            val v = View(this).apply { setOnClickListener { cur = (cur and 0xFF000000.toInt()) or (col and 0xFFFFFF); onPick(cur); mark() } }
            sws.add(v to col); row.addView(v, lp(dp(42), dp(42)).apply { setMargins(dp(5), 0, dp(5), 0) })
        }
        val custom = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; gradientType = GradientDrawable.SWEEP_GRADIENT; colors = RAINBOW }
            addView(tx("+", 20f, Color.WHITE, true).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
            setOnClickListener { dlg?.dismiss(); customColor(title, cur, onPick) }
        }
        row.addView(custom, lp(dp(42), dp(42)).apply { setMargins(dp(5), 0, dp(5), 0) })
        mark()
        box.addView(tx("Swipe for more, or tap + for a custom colour", 12f, SUB))
        box.addView(HorizontalScrollView(this).apply { addView(row); isHorizontalScrollBarEnabled = false })
        val op = tx("Opacity ${Color.alpha(cur) * 100 / 255}%", 13f, SUB).apply { setPadding(0, dp(14), 0, 0) }
        box.addView(op)
        box.addView(SeekBar(this).apply {
            max = 100; progress = Color.alpha(cur) * 100 / 255; progressTintList = ColorStateList.valueOf(ACC); thumbTintList = ColorStateList.valueOf(ACC)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, user: Boolean) { op.text = "Opacity $v%" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { cur = (cur and 0xFFFFFF) or ((progress * 255 / 100) shl 24); onPick(cur) }
            })
        })
        dlg = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(title).setView(box).setPositiveButton("Done", null).show()
    }

    private fun customColor(title: String, start: Int, onPick: (Int) -> Unit) {
        val hsv = FloatArray(3); Color.colorToHSV(start, hsv); var alpha = Color.alpha(start); var busy = false
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val prev = View(this)
        box.addView(prev, lp(-1, dp(56)))
        val hex = EditText(this).apply { setSingleLine(); textSize = 14f; setTextColor(TXT); background = rr(CARD2, 12); setPadding(dp(14), dp(10), dp(14), dp(10)) }
        fun track(cols: IntArray) = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, cols).apply { cornerRadius = dp(7).toFloat(); setSize(-1, dp(14)) }
        val hueD = track(RAINBOW); val satD = track(intArrayOf(0, 0)); val valD = track(intArrayOf(0, 0)); val alD = track(intArrayOf(0, 0))
        val bars = ArrayList<SeekBar>()
        lateinit var sync: (Boolean) -> Unit
        fun slider(label: String, max: Int, init: Int, d: GradientDrawable, set: (Int) -> Unit) {
            box.addView(tx(label, 13f, SUB).apply { setPadding(0, dp(12), 0, dp(2)) })
            val sb = SeekBar(this).apply {
                this.max = max; progress = init; progressDrawable = d; thumbTintList = ColorStateList.valueOf(Color.WHITE); splitTrack = false
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, v: Int, user: Boolean) { if (user) { set(v); sync(false) } }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            bars.add(sb); box.addView(sb)
        }
        slider("Colour", 360, hsv[0].toInt(), hueD) { hsv[0] = it.toFloat() }
        slider("Intensity", 100, (hsv[1] * 100).toInt(), satD) { hsv[1] = it / 100f }
        slider("Dark  ↔  Light", 100, (hsv[2] * 100).toInt(), valD) { hsv[2] = it / 100f }
        slider("Opacity", 100, alpha * 100 / 255, alD) { alpha = it * 255 / 100 }
        box.addView(hex, lp().apply { setMargins(0, dp(14), 0, 0) })
        sync = { fromHex ->
            val c = Color.HSVToColor(alpha, hsv)
            prev.background = rr(c, 14)
            satD.setColors(intArrayOf(Color.HSVToColor(floatArrayOf(hsv[0], 0f, hsv[2])), Color.HSVToColor(floatArrayOf(hsv[0], 1f, hsv[2]))))
            valD.setColors(intArrayOf(Color.BLACK, Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 1f))))
            alD.setColors(intArrayOf(Color.HSVToColor(0, hsv), Color.HSVToColor(255, hsv)))
            if (!fromHex) { busy = true; hex.setText(String.format("#%08X", c)); busy = false }
            onPick(c)
        }
        hex.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (busy || (s?.length != 7 && s?.length != 9)) return
                try {
                    val c = Color.parseColor(s.toString()); Color.colorToHSV(c, hsv); alpha = Color.alpha(c)
                    bars[0].progress = hsv[0].toInt(); bars[1].progress = (hsv[1] * 100).toInt(); bars[2].progress = (hsv[2] * 100).toInt(); bars[3].progress = alpha * 100 / 255
                    sync(true)
                } catch (_: Exception) {}
            }
        })
        sync(false)
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(title).setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Done", null).setNegativeButton("Cancel") { _, _ -> onPick(start) }.show()
    }

    // ---------- screen ----------
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        actionBar?.hide()
        wid = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0); p = Prefs(this, wid)
        window.statusBarColor = BG; window.navigationBarColor = BG
        if (checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.READ_CALENDAR), 1)
        val g = wid == 0
        val tabs = if (g) listOf("Look" to R.drawable.ic_t_style, "Agenda" to R.drawable.ic_t_events, "Rules" to R.drawable.ic_t_rules, "Alerts" to R.drawable.ic_t_alerts, "Study" to R.drawable.ic_t_study, "More" to R.drawable.ic_t_more)
        else listOf("Look" to R.drawable.ic_t_style, "Agenda" to R.drawable.ic_t_events, "Rules" to R.drawable.ic_t_rules, "Study" to R.drawable.ic_t_study)
        val cols = tabs.map { LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(24)) } }
        val scrolls = cols.map { c -> ScrollView(this).apply { addView(c); isVerticalScrollBarEnabled = false; visibility = View.GONE } }
        val byName = tabs.map { it.first }.zip(cols).toMap()
        stylePage(byName["Look"]!!); eventsPage(byName["Agenda"]!!); rulesPage(byName["Rules"]!!); studyPage(byName["Study"]!!)
        if (g) { alertsPage(byName["Alerts"]!!); morePage(byName["More"]!!) }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(22), dp(18), dp(22), 0) }
        top.addView(tx(if (g) "CALENDAR WIDGET  ·  DEFAULTS" else "THIS WIDGET ONLY", 11f, ACC, true).apply { letterSpacing = 0.14f }, lp(0, -2, 1f))
        if (!g) top.addView(tx("All settings  ›", 12f, SUB, true).apply {
            setPadding(dp(8), dp(4), 0, dp(4))
            setOnClickListener { startActivity(Intent(this@SettingsActivity, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)); finish() }
        })
        root.addView(top)
        banner = tx("", 13f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; background = rr(ACC, 14); setPadding(dp(14), dp(10), dp(14), dp(10)); visibility = View.GONE
            setOnClickListener { startUpdate() }
        }
        root.addView(banner, lp().apply { setMargins(dp(16), dp(8), dp(16), 0) })
        holder = FrameLayout(this).apply { background = rr(CARD, 24); setPadding(dp(6), dp(6), dp(6), dp(6)) }
        root.addView(holder, lp(-1, dp(200)).apply { setMargins(dp(16), dp(10), dp(16), dp(4)) })
        val body = FrameLayout(this); scrolls.forEach { body.addView(it) }
        root.addView(body, lp(-1, 0, 1f))
        val nav = LinearLayout(this).apply { setBackgroundColor(BG); setPadding(dp(4), dp(8), dp(4), dp(10)) }
        val pills = ArrayList<FrameLayout>(); val ics = ArrayList<ImageView>(); val lbs = ArrayList<TextView>()
        fun show(i: Int) {
            scrolls.forEachIndexed { j, s -> s.visibility = if (i == j) View.VISIBLE else View.GONE }
            pills.forEachIndexed { j, v -> v.background = if (i == j) rr((ACC and 0xFFFFFF) or 0x33000000, 16) else null }
            ics.forEachIndexed { j, v -> v.setColorFilter(if (i == j) ACC else SUB) }
            lbs.forEachIndexed { j, v -> v.setTextColor(if (i == j) ACC else SUB) }
            holder.visibility = if (tabs[i].first in listOf("Look", "Agenda", "Rules")) View.VISIBLE else View.GONE
        }
        tabs.forEachIndexed { i, t ->
            val item = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setOnClickListener { show(i) } }
            val pill = FrameLayout(this); val ic = ImageView(this).apply { setImageResource(t.second) }
            pill.addView(ic, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            val lb = tx(t.first, 11f, SUB, true).apply { gravity = Gravity.CENTER; includeFontPadding = false }
            pills.add(pill); ics.add(ic); lbs.add(lb)
            item.addView(pill, lp(dp(56), dp(30))); item.addView(lb, lp(-2, -2).apply { setMargins(0, dp(4), 0, 0) })
            nav.addView(item, lp(0, -2, 1f))
        }
        root.addView(View(this).apply { setBackgroundColor(0x1AFFFFFF) }, lp(-1, dp(1)))
        root.addView(nav)
        setContentView(root); show(0); preview()
        val rxf = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(doneRx, rxf, Context.RECEIVER_EXPORTED) else registerReceiver(doneRx, rxf)
        Updater.cleanup(this); refreshUpdateUi()
        if (Updater.due(this)) Updater.check(this) { refreshUpdateUi() }
        if (g && p.l("onb") == 0L) { p.putL("onb", 1); welcome() }
    }

    private fun welcome() = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Welcome")
        .setMessage("1. Allow calendar access when asked.\n2. Long-press your home screen, tap Widgets, and add Calendar Widget (or Next event).\n3. Tap the gear on a widget to style that widget on its own.\n4. Reminders and the live notification are under Alerts.")
        .setPositiveButton("Got it", null).show()

    private fun preview() {
        holder.removeAllViews()
        try {
            val root = CalWidget.view(this, p, wid, true).apply(this, holder)
            val lv = root.findViewById<ListView>(R.id.list); val par = lv.parent as ViewGroup; par.removeView(lv)
            val f = Factory(this, wid); f.onDataSetChanged()
            for (i in 0 until minOf(f.getCount(), 5)) par.addView(f.getViewAt(i).apply(this, par))
            holder.addView(root)
        } catch (_: Exception) {}
    }

    // ---------- pages ----------
    private fun stylePage(c: LinearLayout) {
        header(c, "Look", "Make it yours in a few taps")
        val t = card(c, "Theme")
        val row = LinearLayout(this)
        listOf("OLED", "Dark", "Light", "You").forEachIndexed { i, n ->
            val cs = themeColors(this, i)
            row.addView(tx(n, 13f, cs[1], true).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(18), 0, dp(18))
                background = GradientDrawable().apply { setColor(cs[0]); cornerRadius = dp(14).toFloat(); setStroke(dp(2), cs[3]) }
                setOnClickListener { cs.forEachIndexed { j, col -> p.put(TKEYS[j], col) }; p.put("auto", 0); CalWidget.refresh(this@SettingsActivity); recreate() }
            }, lp(0, -2, 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        t.addView(row)
        toggle(t, "Auto light / dark", "OLED at night, Light by day", "auto") { recreate() }
        val k = card(c, "Colours")
        colorRow(k, "Accent", "acc"); colorRow(k, "Background", "bg")
        val b = card(c, "Event blocks")
        slider(b, "Opacity", "bopa", 0, 100, "%"); seg(b, "Corners", "round", listOf("Square", "Soft", "Round"))
        val m = more(c, "Fine-tune")
        val k2 = card(m, "More colours"); colorRow(k2, "Title text", "title"); colorRow(k2, "Time text", "sub")
        val x = card(m, "Text and spacing")
        slider(x, "Text size", "size", 12, 28, " sp"); slider(x, "Spacing", "gap", 0, 12, " dp"); seg(x, "Font", "font", listOf("Sans", "Narrow", "Serif", "Mono", "Fun"))
        if (wid != 0) card(c, "This widget").addView(button("Reset to default look", false) { p.resetWidget(); CalWidget.refresh(this); recreate() })
    }

    private fun eventsPage(c: LinearLayout) {
        header(c, "Agenda", "What the widget shows")
        if (wid == 0) cals(c)
        val a = card(c, "Events")
        slider(a, "Days shown", "days", 1, 14, " days")
        toggle(a, "Keep current event in view", "Earlier ones sit above, scroll up to see them", "anch")
        toggle(a, "Show finished events", "Dimmed", "ended")
        val h = card(c, "Header buttons")
        toggle(h, "Refresh", null, "bref"); toggle(h, "Add event", null, "badd"); toggle(h, "Settings gear", "If hidden, open settings from the app icon", "bcfg")
        val m = more(c)
        val d = card(m, "Display")
        toggle(d, "Calendar colour bar", null, "bar"); toggle(d, "Progress on current event", null, "prog"); toggle(d, "24-hour time", null, "h24")
        seg(d, "Date format", "dfmt", listOf("Wednesday, Nov 18", "Wed, 18 Nov", "Wed, 18 Nov · W47", "Wednesday 18 November"))
        val t = card(m, "Taps")
        seg(t, "Tap the date", "dtap", listOf("Open calendar", "Month view")); seg(t, "Tap an event", "tap", listOf("Open", "Day", "Nothing")); seg(t, "Open with", "app", listOf("Google", "Notion"))
    }

    private fun cals(c: LinearLayout) {
        val list = Repo.calendars(this); if (list.isEmpty()) return
        val hid = p.hidden().toMutableSet(); val box = card(c, "Calendars")
        list.forEach { (id, name, col) ->
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
            row.addView(View(this).apply { background = dot(col or 0xFF000000.toInt()) }, lp(dp(12), dp(12)).apply { setMargins(0, 0, dp(12), 0) })
            row.addView(tx(name, 15f, TXT), lp(0, -2, 1f))
            row.addView(Switch(this).apply {
                isChecked = id !in hid; thumbTintList = csl(ACC, SUB); trackTintList = csl((ACC and 0xFFFFFF) or 0x66000000, CARD2)
                setOnCheckedChangeListener { _, on -> if (on) hid.remove(id) else hid.add(id); p.setHidden(hid); CalWidget.refresh(this@SettingsActivity); preview() }
            })
            box.addView(row)
        }
    }

    private fun alertsPage(c: LinearLayout) {
        header(c, "Alerts", "Notifications, kept simple")
        val r = card(c, "Reminders")
        toggle(r, "Notify before events", "Needs notification permission", "rem") { if (it) askReminderPerms() }
        seg(r, "How early", "lead", listOf("5 min", "10 min", "15 min", "30 min"))
        val l = card(c, "Live notification")
        toggle(l, "Show the current event", "Progress card with time remaining", "live") { if (it) askNotif() }
        l.addView(button("Send a preview", false) { askNotif(); Live.demo(this, p) })
        val m = card(c, "Morning briefing")
        toggle(m, "Daily summary", "Today's events as a notification", "morn") { if (it) askNotif() }
        slider(m, "Send at", "mornh", 5, 10, ":00")
        val s = more(c, "Notification style")
        val cd = card(s, "Card")
        seg(cd, "Layout", "nstyle", listOf("Compact", "Detailed")); seg(cd, "Colour", "ncol", listOf("Dark", "Event", "Accent"))
        toggle(cd, "Show next event", "Detailed layout only", "nnext")
        val tm = card(s, "Time remaining")
        seg(tm, "Format", "tfmt", listOf("Live clock", "3h 2m", "03:02")); seg(tm, "Label", "tlab", listOf("left", "remaining", "none"))
        tm.addView(tx("Seconds only appear in the live clock. Other formats refresh every minute.", 12f, SUB).apply { setPadding(0, dp(8), 0, dp(4)) })
        val bc = card(s, "Progress bar")
        seg(bc, "Compact thickness", "nbhc", listOf("Medium", "Thick", "XL", "Max")); seg(bc, "Expanded thickness", "nbh", listOf("Thin", "Medium", "Thick", "XL"))
        seg(bc, "Shape", "nbr", listOf("Round", "Soft", "Square")); seg(bc, "Colour", "nbc", listOf("Accent", "Event", "White", "Custom"))
        colorRow(bc, "Custom colour", "nbcc"); toggle(bc, "Show percentage", "Beside the bar", "npct")
        val v = card(s, "Visibility")
        seg(v, "Priority", "nprio", listOf("Quiet", "Prominent")); toggle(v, "Show on lock screen", "Your phone's setting must also allow it", "nlock")
        toggle(v, "Keep pinned", "Comes back if swiped away", "pin"); seg(v, "Refresh progress", "lint", listOf("5 min", "15 min"))
    }

    // ---------- rules ----------
    private var rulesCol: LinearLayout? = null
    private fun rulesPage(c: LinearLayout) { rulesCol = c; fillRules() }

    private fun fillRules() {
        val c = rulesCol ?: return; c.removeAllViews()
        header(c, "Rules", "Colour and behaviour for events that match a keyword")
        val a = card(c, "Colouring"); toggle(a, "Colour events by rule", "A rule's colour replaces the calendar colour", "kwc")
        val l = card(c, "Your rules")
        val rules = Rules.load(p)
        if (rules.isEmpty()) l.addView(tx("No rules yet. A rule can colour matching events, hide their progress bar, keep them out of the live notification, or count them as study time.", 13f, SUB).apply { setPadding(0, dp(8), 0, dp(8)) })
        rules.forEachIndexed { i, r ->
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, dp(12)); setOnClickListener { ruleDialog(i) } }
            row.addView(View(this).apply { background = dot(r.color) }, lp(dp(14), dp(14)).apply { setMargins(0, 0, dp(14), 0) })
            row.addView(texts(r.k, listOfNotNull(if (r.prog) "Progress" else "No progress", if (r.notif) "In notification" else "Not in notification", if (r.study) "Study" else null).joinToString(" · ")), lp(0, -2, 1f))
            row.addView(tx("›", 22f, SUB)); l.addView(row)
        }
        l.addView(button("Add rule") { ruleDialog(-1) })
        c.addView(tx("The first matching rule wins, from the top.", 12f, SUB).apply { setPadding(dp(6), dp(4), 0, dp(20)) })
    }

    private fun commitRules(l: List<Rule>) { Rules.save(p, l); CalWidget.refresh(this); preview(); fillRules() }

    private fun ruleDialog(idx: Int) {
        val list = Rules.load(p).toMutableList(); val old = list.getOrNull(idx)
        var color = old?.color ?: PAL[list.size % PAL.size]
        var prog = old?.prog ?: true; var notif = old?.notif ?: true; var study = old?.study ?: true
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(4)) }
        val kw = EditText(this).apply { hint = "Keyword, e.g. Audit"; setHintTextColor(SUB); setTextColor(TXT); setSingleLine(); setText(old?.k ?: ""); background = rr(CARD2, 12); setPadding(dp(14), dp(12), dp(14), dp(12)) }
        box.addView(kw)
        val sw = View(this).apply { background = dot(color) }
        val crow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, dp(4)); setOnClickListener { pickColor("Colour", color) { color = it; sw.background = dot(it) } } }
        crow.addView(tx("Colour", 15f, TXT), lp(0, -2, 1f)); crow.addView(sw, lp(dp(30), dp(30))); box.addView(crow)
        swRow(box, "Show progress bar", prog) { prog = it }
        swRow(box, "Show in live notification", notif) { notif = it }
        swRow(box, "Counts as study time", study) { study = it }
        val b = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(if (old == null) "New rule" else "Edit rule").setView(box)
            .setPositiveButton("Save") { _, _ ->
                val k = kw.text.toString().trim()
                if (k.isNotEmpty()) { val r = Rule(k, color, prog, notif, study); if (old == null) list.add(r) else list[idx] = r; commitRules(list) }
            }.setNegativeButton("Cancel", null)
        if (old != null) b.setNeutralButton("Delete") { _, _ -> list.removeAt(idx); commitRules(list) }
        b.show()
    }

    private fun studyPage(c: LinearLayout) {
        header(c, "Study", "Chips shown under the header")
        val s = card(c, "Info chips")
        toggle(s, "Study hours", "Done / planned today, from rules marked as study", "sumr")
        toggle(s, "Next free slot", null, "free"); toggle(s, "Day progress bar", null, "dayp")
        val cd = card(c, "Countdown")
        field(cd, "Label, e.g. CA Inter", "cdl")
        val db = button("") { pickDate() }; dateBtn = db; dateLabel()
        cd.addView(db); cd.addView(button("Clear countdown", false) { p.putL("cdt", 0); CalWidget.refresh(this); dateLabel(); preview() })
        val m = more(c, "Fine-tune")
        val f = card(m, "Free slot"); slider(f, "Minimum length", "freemin", 30, 180, " min")
        val d = card(m, "Day"); slider(d, "Day starts", "wake", 0, 12, ":00"); slider(d, "Day ends", "sleep", 13, 24, ":00")
    }

    private fun dateLabel() { dateBtn?.text = if (p.l("cdt") > 0) "Date: " + SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(p.l("cdt")) else "Pick a date" }

    private fun pickDate() {
        val c = Calendar.getInstance(); if (p.l("cdt") > 0) c.timeInMillis = p.l("cdt")
        DatePickerDialog(this, android.R.style.Theme_DeviceDefault_Dialog_Alert, { _, y, m, d ->
            p.putL("cdt", Calendar.getInstance().apply { clear(); set(y, m, d) }.timeInMillis); CalWidget.refresh(this); dateLabel(); preview()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun morePage(c: LinearLayout) {
        header(c, "More", "Backup, updates and diagnostics")
        val b = card(c, "Backup")
        b.addView(button("Export settings") { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE, "calwidget-settings.json"), 11) })
        b.addView(button("Import settings", false) { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 12) })
        val u = card(c, "Updates")
        updStatus = tx("", 14f, TXT)
        u.addView(updStatus)
        updBtn = button("Download and install") { startUpdate() }.apply { visibility = View.GONE }
        u.addView(updBtn)
        u.addView(button("Check for updates", false) {
            Updater.check(this) { r -> refreshUpdateUi(); if (r < 0) Toast.makeText(this, "Couldn't check. The repo must be public and have a release.", Toast.LENGTH_LONG).show() }
        })
        u.addView(button("Share this app", false) {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Calendar Widget for Android - download the APK here: https://github.com/Chaitanyaa04/Calendar-Widget/releases/latest"), "Share app link"))
        })
        refreshUpdateUi()
        val h = card(c, "Health")
        val f = SimpleDateFormat("h:mm a", Locale.getDefault())
        h.addView(tx(if (p.l("lastr") > 0) "Last refresh ${f.format(p.l("lastr"))} · ${p.l("cntd")} today" else "No refresh yet", 14f, TXT))
        val err = p.s("err")
        h.addView(tx(if (err.isEmpty()) "No errors recorded" else "Last error: $err", 12f, SUB).apply { setPadding(0, dp(6), 0, dp(6)) })
        if (err.isNotEmpty()) h.addView(button("Clear error", false) { p.putS("err", ""); recreate() })
        val t = card(c, "Help")
        t.addView(tx("Choose which calendars show in the Google Calendar app or in Calendars under Events.", 13f, SUB))
        t.addView(button("Show welcome again", false) { welcome() })
    }

    // ---------- permissions, files, updates ----------
    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        CalWidget.refresh(this)
        when (code) { 1 -> recreate(); 2 -> askExact() }
    }

    private fun askReminderPerms() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2) else askExact()
    }

    private fun askExact() {
        if (Build.VERSION.SDK_INT >= 31 && !getSystemService(AlarmManager::class.java).canScheduleExactAlarms())
            try { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
    }

    private fun askNotif() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 3)
    }

    override fun onActivityResult(rc: Int, res: Int, d: Intent?) {
        super.onActivityResult(rc, res, d)
        val u = d?.data ?: return
        try {
            if (rc == 11) contentResolver.openOutputStream(u)?.use { it.write(p.dump().toByteArray()) }
            else if (rc == 12) { p.load(contentResolver.openInputStream(u)!!.bufferedReader().readText()); CalWidget.refresh(this); recreate() }
        } catch (_: Exception) { Toast.makeText(this, "Couldn't read or write that file", Toast.LENGTH_SHORT).show() }
    }

    private fun refreshUpdateUi() {
        val has = Updater.available(this)
        banner?.visibility = if (has) View.VISIBLE else View.GONE
        banner?.text = "Update available  ·  Build ${Updater.latest(this)}  ·  Tap to install"
        updStatus?.text = if (has) "Installed build ${Updater.installed(this)}  ·  New build ${Updater.latest(this)} is ready" else "Installed build ${Updater.installed(this)}  ·  You're up to date"
        updBtn?.visibility = if (has) View.VISIBLE else View.GONE
    }

    private fun startUpdate() {
        if (!Updater.available(this)) return
        dlId = Updater.download(this)
        Toast.makeText(this, "Downloading update. The installer opens when it's done.", Toast.LENGTH_LONG).show()
    }

    private fun installNow() {
        if (!packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "Allow installs for this app, then tap Install again.", Toast.LENGTH_LONG).show()
            try { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
            return
        }
        Updater.install(this, dlId)
    }

    override fun onDestroy() { try { unregisterReceiver(doneRx) } catch (_: Exception) {}; super.onDestroy() }
}

// Per-widget settings run in their own task so they never reuse the main settings screen.
class WidgetSettings : SettingsActivity()
