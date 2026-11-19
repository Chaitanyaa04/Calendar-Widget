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
private val PRESETS = listOf(0xFF000000, 0xFFFFFFFF, 0xFF212121, 0xFFFF4081, 0xFF7C4DFF, 0xFF2979FF, 0xFF1DE9B6, 0xFF66BB6A, 0xFFFFC107, 0xFFFF6E40, 0xFFF44336, 0xFFBDBDBD).map { it.toInt() }

class SettingsActivity : Activity() {
    private lateinit var p: Prefs
    private var wid = 0
    private lateinit var holder: FrameLayout
    private var dateBtn: TextView? = null

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
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val hex = EditText(this).apply {
            setText(String.format("#%08X", cur)); setSingleLine(); textSize = 14f; setTextColor(TXT); background = rr(CARD2, 12); setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        fun use(col: Int) { cur = col; onPick(col) }
        val grid = GridLayout(this).apply { columnCount = 6 }
        PRESETS.forEach { col ->
            grid.addView(View(this).apply {
                background = dot(col)
                setOnClickListener { val n = (cur and 0xFF000000.toInt()) or (col and 0xFFFFFF); use(n); hex.setText(String.format("#%08X", n)) }
            }, GridLayout.LayoutParams().apply { width = dp(40); height = dp(40); setMargins(dp(5), dp(5), dp(5), dp(5)) })
        }
        hex.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { if (s?.length == 7 || s?.length == 9) try { use(Color.parseColor(s.toString())) } catch (_: Exception) {} }
        })
        val op = tx("Opacity ${Color.alpha(cur) * 100 / 255}%", 13f, SUB).apply { setPadding(0, dp(12), 0, 0) }
        box.addView(grid); box.addView(hex, lp().apply { setMargins(0, dp(10), 0, 0) }); box.addView(op)
        box.addView(SeekBar(this).apply {
            max = 100; progress = Color.alpha(cur) * 100 / 255; progressTintList = ColorStateList.valueOf(ACC); thumbTintList = ColorStateList.valueOf(ACC)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, user: Boolean) { op.text = "Opacity $v%" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { val n = (cur and 0xFFFFFF) or ((progress * 255 / 100) shl 24); use(n); hex.setText(String.format("#%08X", n)) }
            })
        })
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(title).setView(box).setPositiveButton("Done", null).show()
    }

    // ---------- screen ----------
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        wid = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0); p = Prefs(this, wid)
        window.statusBarColor = BG; window.navigationBarColor = CARD
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
        root.addView(tx(if (g) "CALENDAR WIDGET  ·  DEFAULTS" else "THIS WIDGET ONLY", 11f, ACC, true).apply { letterSpacing = 0.14f; setPadding(dp(22), dp(18), dp(20), 0) })
        holder = FrameLayout(this).apply { background = rr(CARD, 24); setPadding(dp(6), dp(6), dp(6), dp(6)) }
        root.addView(holder, lp(-1, dp(200)).apply { setMargins(dp(16), dp(10), dp(16), dp(4)) })
        val body = FrameLayout(this); scrolls.forEach { body.addView(it) }
        root.addView(body, lp(-1, 0, 1f))
        val nav = LinearLayout(this).apply { setBackgroundColor(CARD); setPadding(0, dp(8), 0, dp(10)) }
        val ics = ArrayList<ImageView>(); val lbs = ArrayList<TextView>()
        fun show(i: Int) {
            scrolls.forEachIndexed { j, s -> s.visibility = if (i == j) View.VISIBLE else View.GONE }
            ics.forEachIndexed { j, v -> v.setColorFilter(if (i == j) ACC else SUB) }
            lbs.forEachIndexed { j, v -> v.setTextColor(if (i == j) ACC else SUB) }
            holder.visibility = if (tabs[i].first in listOf("Look", "Agenda", "Rules")) View.VISIBLE else View.GONE
        }
        tabs.forEachIndexed { i, t ->
            val item = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setOnClickListener { show(i) } }
            val ic = ImageView(this).apply { setImageResource(t.second) }; val lb = tx(t.first, 11f, SUB, true)
            ics.add(ic); lbs.add(lb)
            item.addView(ic, lp(dp(24), dp(24))); item.addView(lb)
            nav.addView(item, lp(0, -2, 1f))
        }
        root.addView(nav)
        setContentView(root); show(0); preview()
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
        header(c, "Look", "Theme, colours and typography")
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
        colorRow(k, "Background", "bg"); colorRow(k, "Title text", "title"); colorRow(k, "Time text", "sub"); colorRow(k, "Accent", "acc")
        val b = card(c, "Event blocks")
        slider(b, "Opacity", "bopa", 0, 100, "%"); seg(b, "Corners", "round", listOf("Square", "Soft", "Round")); slider(b, "Spacing", "gap", 0, 12, " dp")
        val x = card(c, "Text")
        slider(x, "Size", "size", 12, 28, " sp"); seg(x, "Font", "font", listOf("Sans", "Narrow", "Serif", "Mono", "Fun"))
        if (wid != 0) card(c, "This widget").addView(button("Reset to default look", false) { p.resetWidget(); CalWidget.refresh(this); recreate() })
    }

    private fun eventsPage(c: LinearLayout) {
        header(c, "Agenda", "What the widget shows and how it behaves")
        if (wid == 0) cals(c)
        val a = card(c, "Agenda")
        slider(a, "Days shown", "days", 1, 14, " days")
        toggle(a, "Show finished events", "Dimmed, above the now line", "ended"); toggle(a, "Calendar colour bar", null, "bar")
        toggle(a, "Progress on current event", null, "prog"); toggle(a, "24-hour time", null, "h24"); toggle(a, "Header buttons", "Refresh, add and settings", "icons")
        val d = card(c, "Header")
        seg(d, "Date format", "dfmt", listOf("Wednesday, Nov 18", "Wed, 18 Nov", "Wed, 18 Nov · W47", "Wednesday 18 November"))
        seg(d, "Tap the date", "dtap", listOf("Open calendar", "Month view"))
        val t = card(c, "Taps")
        seg(t, "Tap an event", "tap", listOf("Open", "Day", "Nothing")); seg(t, "Open with", "app", listOf("Google", "Notion"))
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
        header(c, "Alerts", "Reminders, the live card and your morning briefing")
        val r = card(c, "Reminders")
        toggle(r, "Notify before events", "Needs notification permission", "rem") { if (it) askReminderPerms() }
        seg(r, "How early", "lead", listOf("5 min", "10 min", "15 min", "30 min"))
        val l = card(c, "Live notification")
        toggle(l, "Show the current event", "A progress card for the event in progress", "live") { if (it) askNotif() }
        seg(l, "Layout", "nstyle", listOf("Compact", "Detailed"))
        seg(l, "Card colour", "ncol", listOf("Dark", "Event", "Accent"))
        toggle(l, "Show next event", "Detailed layout only", "nnext")
        val tm = card(c, "Time remaining")
        seg(tm, "Format", "tfmt", listOf("Live clock", "3h 2m", "03:02"))
        seg(tm, "Label", "tlab", listOf("left", "remaining", "none"))
        tm.addView(tx("Seconds only appear in the live clock. The other formats refresh every minute while the notification is on.", 12f, SUB).apply { setPadding(0, dp(8), 0, dp(4)) })
        val bc = card(c, "Progress bar")
        seg(bc, "Compact bar (collapsed)", "nbhc", listOf("Medium", "Thick", "XL", "Max"))
        seg(bc, "Expanded bar", "nbh", listOf("Thin", "Medium", "Thick", "XL"))
        seg(bc, "Shape", "nbr", listOf("Round", "Soft", "Square"))
        seg(bc, "Colour", "nbc", listOf("Accent", "Event", "White", "Custom"))
        colorRow(bc, "Custom colour", "nbcc")
        toggle(bc, "Show percentage", "Beside the bar", "npct")
        val v = card(c, "Visibility")
        seg(v, "Priority", "nprio", listOf("Quiet", "Prominent"))
        toggle(v, "Show on lock screen", "Your phone's lock-screen setting must also allow it", "nlock")
        toggle(v, "Keep pinned", "Comes back if swiped away", "pin"); seg(v, "Refresh progress", "lint", listOf("5 min", "15 min"))
        v.addView(button("Send a preview", false) { askNotif(); Live.demo(this, p) })
        val m = card(c, "Morning briefing")
        toggle(m, "Daily summary", "Today's events as a notification", "morn") { if (it) askNotif() }
        slider(m, "Send at", "mornh", 5, 10, ":00")
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
        header(c, "Study", "Planning tools for exam season")
        val s = card(c, "Study hours")
        toggle(s, "Summary on the widget", "Done, left and planned today, from rules marked as study time", "sumr")
        val f = card(c, "Free time")
        toggle(f, "Next free slot", null, "free"); slider(f, "Minimum length", "freemin", 30, 180, " min")
        val d = card(c, "Day")
        toggle(d, "Day progress bar", null, "dayp"); slider(d, "Day starts", "wake", 0, 12, ":00"); slider(d, "Day ends", "sleep", 13, 24, ":00")
        val cd = card(c, "Countdown")
        field(cd, "Label, e.g. CA Inter", "cdl")
        val db = button("") { pickDate() }; dateBtn = db; dateLabel()
        cd.addView(db); cd.addView(button("Clear countdown", false) { p.putL("cdt", 0); CalWidget.refresh(this); dateLabel(); preview() })
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
        @Suppress("DEPRECATION") val mine = packageManager.getPackageInfo(packageName, 0).versionCode
        u.addView(tx("Installed build $mine", 14f, TXT)); u.addView(button("Check for updates") { checkUpdate() })
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

    private fun checkUpdate() {
        fun toast(t: String) = runOnUiThread { Toast.makeText(this, t, Toast.LENGTH_LONG).show() }
        Thread {
            try {
                val u = java.net.URL("https://api.github.com/repos/Chaitanyaa04/Calendar-Widget/releases/latest").openConnection() as java.net.HttpURLConnection
                u.connectTimeout = 8000; u.readTimeout = 8000
                val j = JSONObject(u.inputStream.bufferedReader().readText())
                val tag = j.getString("tag_name").removePrefix("v").toLong()
                @Suppress("DEPRECATION") val mine = packageManager.getPackageInfo(packageName, 0).versionCode.toLong()
                val url = j.getJSONArray("assets").getJSONObject(0).getString("browser_download_url")
                if (tag > mine) runOnUiThread {
                    AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Update available").setMessage("Build $tag is ready (you have $mine).")
                        .setPositiveButton("Download") { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.setNegativeButton("Later", null).show()
                } else toast("You're up to date (build $mine)")
            } catch (_: Exception) { toast("Couldn't check. The repo must be public and have a release.") }
        }.start()
    }
}
