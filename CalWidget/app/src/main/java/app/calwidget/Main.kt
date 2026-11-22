package app.calwidget

import android.app.*
import android.app.job.*
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.*
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.provider.Settings
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import org.json.JSONObject
import org.json.JSONArray
import android.provider.CalendarContract
import android.provider.CalendarContract.Instances as I
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*

private val DEF = mapOf(
    "bg" to 0xFF000000.toInt(), "title" to 0xFFE6E6E6.toInt(), "sub" to 0xFFBDBDBD.toInt(), "acc" to 0xFFFF4081.toInt(),
    "size" to 20, "bopa" to 14, "round" to 1, "gap" to 3, "tap" to 0, "icons" to 1, "ended" to 1, "dfmt" to 0, "app" to 0, "rem" to 0, "lead" to 1, "auto" to 0, "live" to 0, "lint" to 1, "kwc" to 1, "sumr" to 1, "free" to 1, "freemin" to 60, "dayp" to 1, "wake" to 6, "sleep" to 23, "font" to 0, "dtap" to 1, "morn" to 0, "mornh" to 7, "pin" to 1, "days" to 7, "refocus" to 1, "rtime" to 1, "bref" to 1, "badd" to 1, "bcfg" to 1, "anch" to 1, "tfmt" to 0, "tlab" to 0, "nbh" to 2, "nbhc" to 2, "nbr" to 0, "nbc" to 0, "nbcc" to 0xFFFFFFFF.toInt(), "nstyle" to 1, "ncol" to 0, "npct" to 1, "nnext" to 1, "nprio" to 1, "nlock" to 1, "h24" to 0, "bar" to 1, "prog" to 1
)

val WIDGETS = intArrayOf(R.layout.widget, R.layout.widget_f1, R.layout.widget_f2, R.layout.widget_f3, R.layout.widget_f4)
val ITEMS = intArrayOf(R.layout.item, R.layout.item_f1, R.layout.item_f2, R.layout.item_f3, R.layout.item_f4)
val HDRS = intArrayOf(R.layout.hdr, R.layout.hdr_f1, R.layout.hdr_f2, R.layout.hdr_f3, R.layout.hdr_f4)
val TKEYS = listOf("bg", "title", "sub", "acc")
val DATEFMT = listOf("EEEE, MMM d", "EEE, d MMM", "EEE, d MMM '· W'w", "EEEE d MMMM")
fun themeColors(c: Context, i: Int): List<Int> = when {
    i == 0 -> listOf(0xFF000000, 0xFFE6E6E6, 0xFFBDBDBD, 0xFFFF4081).map { it.toInt() }
    i == 1 -> listOf(0xFF202124, 0xFFE8EAED, 0xFF9AA0A6, 0xFF8AB4F8).map { it.toInt() }
    i == 2 -> listOf(0xFFFFFFFF, 0xFF202124, 0xFF5F6368, 0xFF1A73E8).map { it.toInt() }
    Build.VERSION.SDK_INT >= 31 -> listOf(android.R.color.system_neutral1_900, android.R.color.system_neutral1_50, android.R.color.system_neutral2_300, android.R.color.system_accent1_200).map { c.getColor(it) }
    else -> themeColors(c, 1)
}

class Prefs(private val c: Context, private val wid: Int = 0) {
    private val p = c.getSharedPreferences("cfg", 0)
    private fun key(k: String) = if (wid != 0 && p.contains("w$wid:$k")) "w$wid:$k" else k
    private fun wk(k: String) = if (wid != 0) "w$wid:$k" else k
    fun resetWidget() { val e = p.edit(); p.all.keys.filter { it.startsWith("w$wid:") }.forEach { e.remove(it) }; e.apply() }
    fun i(k: String): Int {
        val ix = TKEYS.indexOf(k)
        if (ix >= 0 && p.getInt(key("auto"), 0) == 1) {
            val dark = (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            return themeColors(c, if (dark) 0 else 2)[ix]
        }
        return p.getInt(key(k), DEF[k]!!)
    }
    fun put(k: String, v: Int) = p.edit().putInt(wk(k), v).apply()
    fun dump(): String { val o = JSONObject(); p.all.forEach { (k, v) -> o.put(k, v) }; return o.toString() }
    fun load(js: String) {
        val o = JSONObject(js); val e = p.edit()
        o.keys().forEach { k -> when (val v = o.get(k)) { is Int -> e.putInt(k, v); is Long -> e.putLong(k, v); is String -> e.putString(k, v) } }
        e.apply()
    }
    fun s(k: String) = p.getString(key(k), "")!!
    fun putS(k: String, v: String) = p.edit().putString(wk(k), v).apply()
    fun l(k: String) = p.getLong(key(k), 0L)
    fun putL(k: String, v: Long) = p.edit().putLong(wk(k), v).apply()
    fun hidden(): Set<Long> = p.getString("hid", "")!!.split(",").mapNotNull { it.toLongOrNull() }.toSet()
    fun setHidden(s: Set<Long>) = p.edit().putString("hid", s.joinToString(",")).apply()
}

data class Ev(val id: Long, val title: String, val start: Long, val end: Long, val allDay: Boolean, val color: Int, val cal: Long = 0)

object Repo {
    // Reads the on-device Calendar Provider, which Google Calendar's own sync keeps up to date (no login/API needed).
    fun load(c: Context, days: Int, startMs: Long = 0L): List<Ev> {
        if (c.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        if (startMs > 0) cal.timeInMillis = startMs
        val from = cal.timeInMillis
        cal.add(Calendar.DAY_OF_YEAR, days)
        val b = I.CONTENT_URI.buildUpon()
        ContentUris.appendId(b, from); ContentUris.appendId(b, cal.timeInMillis)
        val out = ArrayList<Ev>()
        val proj = arrayOf(I.EVENT_ID, I.TITLE, I.BEGIN, I.END, I.ALL_DAY, I.EVENT_COLOR, I.CALENDAR_COLOR, I.CALENDAR_ID)
        val hid = Prefs(c).hidden()
        try {
            c.contentResolver.query(b.build(), proj, "${I.VISIBLE}=1", null, "${I.BEGIN} ASC")?.use { q ->
                while (q.moveToNext()) {
                    val ad = q.getInt(4) == 1
                    var s = q.getLong(2); var e = q.getLong(3)
                    if (ad) { val tz = TimeZone.getDefault(); s -= tz.getOffset(s); e -= tz.getOffset(e) }
                    if (q.getLong(7) in hid) continue
                    val ec = q.getInt(5)
                    out.add(Ev(q.getLong(0), q.getString(1)?.ifBlank { null } ?: "(No title)", s, e, ad, (if (ec != 0) ec else q.getInt(6)) or 0xFF000000.toInt(), q.getLong(7)))
                }
            }
        } catch (_: Exception) {}
        return out.sortedBy { it.start }
    }
    fun calendars(c: Context): List<Triple<Long, String, Int>> {
        val o = ArrayList<Triple<Long, String, Int>>()
        if (c.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return o
        val u = CalendarContract.Calendars.CONTENT_URI
        try { c.contentResolver.query(u, arrayOf("_id", "calendar_displayName", "calendar_color"), "visible=1", null, "calendar_displayName")?.use { while (it.moveToNext()) o.add(Triple(it.getLong(0), it.getString(1) ?: "?", it.getInt(2))) } } catch (_: Exception) {}
        return o
    }
    // Asks Android to pull fresh data from Google right now (used by the refresh button).
    fun sync(c: Context) {
        try {
            val seen = HashSet<String>()
            c.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf("account_name", "account_type"), null, null, null)?.use {
                while (it.moveToNext()) if (it.getString(1) != "LOCAL" && seen.add(it.getString(0) + it.getString(1)))
                    ContentResolver.requestSync(android.accounts.Account(it.getString(0), it.getString(1)), CalendarContract.AUTHORITY,
                        Bundle().apply { putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true); putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true) })
            }
        } catch (_: Exception) {}
    }
    fun freeGap(all: List<Ev>, p: Prefs): Pair<Long, Long>? {
        val now = System.currentTimeMillis()
        val t0 = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val need = p.i("freemin") * 60000L; val dEnd = t0 + p.i("sleep") * 3600000L
        var cur = maxOf(now, t0 + p.i("wake") * 3600000L)
        for (b in all.filter { !it.allDay && it.end > cur && it.start < dEnd }.sortedBy { it.start }) {
            if (b.start - cur >= need) return cur to b.start
            cur = maxOf(cur, b.end)
        }
        return if (dEnd - cur >= need) cur to dEnd else null
    }
    fun remain(ms: Long, fmt: Int, lab: Int): String {
        val m = maxOf(ms, 0L) / 60000; val h = m / 60; val mm = m % 60
        val core = if (fmt == 2) String.format("%02d:%02d", h, mm) else if (h > 0) "${h}h ${mm}m" else "${mm}m"
        return core + arrayOf(" left", " remaining", "")[lab]
    }
    fun fmt(ms: Long): String { val m = ms / 60000; return when { m >= 1440 -> "${m / 1440}d"; m >= 60 -> "${m / 60}h" + (if (m % 60 > 0) "${m % 60}m" else ""); else -> "${maxOf(m, 1)}m" } }
    fun time(ms: Long, h24: Boolean) = SimpleDateFormat(if (h24) "HH:mm" else "h:mm a", Locale.getDefault()).format(ms).lowercase()
}

class CalWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = refresh(c)
    override fun onEnabled(c: Context) = ChangeJob.schedule(c)
    override fun onDisabled(c: Context) {
        if (AppWidgetManager.getInstance(c).getAppWidgetIds(ComponentName(c, NextWidget::class.java)).isNotEmpty()) return
        c.getSystemService(JobScheduler::class.java).cancel(1)
        c.getSystemService(AlarmManager::class.java).cancel(alarmIntent(c))
    }
    override fun onReceive(c: Context, i: Intent) {
        super.onReceive(c, i)
        if (i.action == "app.calwidget.SYNC") Repo.sync(c)
        if (i.action != AppWidgetManager.ACTION_APPWIDGET_UPDATE) refresh(c)
    }

    companion object {
        private fun alarmIntent(c: Context) = PendingIntent.getBroadcast(c, 0,
            Intent(c, CalWidget::class.java).setAction("app.calwidget.REFRESH"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        fun refresh(c: Context) {
            val m = AppWidgetManager.getInstance(c)
            val ids = m.getAppWidgetIds(ComponentName(c, CalWidget::class.java))
            if (ids.isEmpty() && m.getAppWidgetIds(ComponentName(c, NextWidget::class.java)).isEmpty()) return
            val p = Prefs(c)
            val dk = System.currentTimeMillis() / 86400000L
            if (p.l("cday") != dk) { p.putL("cday", dk); p.putL("cntd", 0) }
            p.putL("cntd", p.l("cntd") + 1); p.putL("lastr", System.currentTimeMillis())
            for (id in ids) m.updateAppWidget(id, try { view(c, Prefs(c, id), id) } catch (e: Exception) {
                p.putS("err", e.toString().take(160))
                RemoteViews(c.packageName, R.layout.err).apply { setTextViewText(R.id.e, "Couldn't draw the widget. Open the app, then More, then Health.") }
            })
            if (ids.isNotEmpty()) m.notifyAppWidgetViewDataChanged(ids, R.id.list)
            try {
                NextWidget.update(c)
                scheduleNext(c, p)
                if (c.getSystemService(JobScheduler::class.java).getPendingJob(1) == null) ChangeJob.schedule(c)
            } catch (e: Exception) { p.putS("err", e.toString().take(160)) }
        }

        // One inexact, non-waking alarm at the next moment the display actually changes. Zero polling.
        private fun scheduleNext(c: Context, p: Prefs) {
            val now = System.currentTimeMillis()
            val ev = Repo.load(c, p.i("days"))
            Reminders.schedule(c, p, ev); Live.update(c, p, ev); Morning.schedule(c, p)
            val mid = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 5) }.timeInMillis
            var t = mid
            for (e in ev) { if (e.start > now) t = minOf(t, e.start); if (e.end > now) t = minOf(t, e.end) }
            if ((p.i("prog") == 1 || p.i("live") == 1) && ev.any { it.start <= now && it.end > now && !it.allDay }) t = minOf(t, now + (if (p.i("live") == 1 && p.i("tfmt") != 0) 1 else if (p.i("lint") == 0) 5 else 15) * 60000L)
            // Android never tells a widget "home screen is visible", so re-focus on a timer. The alarm is non-waking:
            // if the phone slept past it, it fires the moment the screen turns on.
            if (p.i("anch") == 1 && p.i("refocus") == 1) t = minOf(t, now + intArrayOf(2, 5, 15)[p.i("rtime")] * 60000L)
            c.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC, maxOf(t, now + 2000), alarmIntent(c))
        }

        private fun open(c: Context, rc: Int, f: Intent.() -> Unit) = PendingIntent.getActivity(c, rc,
            Intent(c, Open::class.java).apply(f), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        fun view(c: Context, p: Prefs, id: Int, preview: Boolean = false): RemoteViews {
            val r = RemoteViews(c.packageName, WIDGETS[p.i("font")])
            val tc = p.i("title")
            r.setInt(R.id.bg, "setColorFilter", p.i("bg") or 0xFF000000.toInt())
            r.setInt(R.id.bg, "setImageAlpha", Color.alpha(p.i("bg")))
            r.setViewVisibility(R.id.ref, if (p.i("bref") == 1) View.VISIBLE else View.GONE)
            r.setViewVisibility(R.id.add, if (p.i("badd") == 1) View.VISIBLE else View.GONE)
            r.setViewVisibility(R.id.cfg, if (p.i("bcfg") == 1) View.VISIBLE else View.GONE)
            r.setInt(R.id.ref, "setColorFilter", tc)
            r.setOnClickPendingIntent(R.id.ref, PendingIntent.getBroadcast(c, 5, Intent(c, CalWidget::class.java).setAction("app.calwidget.SYNC"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            r.setTextViewText(R.id.date, SimpleDateFormat(DATEFMT[p.i("dfmt")], Locale.getDefault()).format(Date()))
            r.setTextColor(R.id.date, tc)
            r.setTextViewTextSize(R.id.date, TypedValue.COMPLEX_UNIT_SP, p.i("size") + 2f)
            r.setInt(R.id.add, "setColorFilter", tc); r.setInt(R.id.cfg, "setColorFilter", tc)
            val t0 = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
            val cd = p.l("cdt"); val d = if (cd > 0) Math.round((cd - t0) / 86400000.0).toInt() else -1
            r.setViewVisibility(R.id.cdWrap, if (d >= 0) View.VISIBLE else View.GONE)
            if (d >= 0) {
                r.setTextViewText(R.id.cd, (if (p.s("cdl").isBlank()) "" else p.s("cdl").trim() + " ") + (if (d == 0) "today" else "${d}d"))
                r.setInt(R.id.cdPill, "setColorFilter", p.i("acc") or 0xFF000000.toInt()); r.setInt(R.id.cdPill, "setImageAlpha", if (d <= 7) 255 else 80)
                r.setTextColor(R.id.cd, if (d <= 7) Color.WHITE else tc)
            }
            val td = Repo.load(c, 1); val rules = Rules.load(p); val now0 = System.currentTimeMillis()
            fun f(ms: Long) = if (ms < 60000) "0m" else Repo.fmt(ms)
            var stTxt: String? = null; var frTxt: String? = null
            if (p.i("sumr") == 1 && rules.any { it.study }) {
                var total = 0L; var done = 0L
                for (e in td) if (!e.allDay && Rules.match(rules, e.title)?.study == true) { total += e.end - e.start; done += (minOf(e.end, now0) - e.start).coerceAtLeast(0) }
                if (total > 0) stTxt = "STUDY ${f(done)} / ${f(total)}"
            }
            if (p.i("free") == 1) Repo.freeGap(td, p)?.let { frTxt = "FREE ${Repo.time(it.first, p.i("h24") == 1)} · ${Repo.fmt(it.second - it.first)}" }
            fun chip(wrap: Int, pill: Int, tv: Int, t: String?) {
                r.setViewVisibility(wrap, if (t != null) View.VISIBLE else View.GONE)
                if (t != null) { r.setTextViewText(tv, t); r.setTextColor(tv, tc); r.setInt(pill, "setColorFilter", p.i("acc") or 0xFF000000.toInt()); r.setInt(pill, "setImageAlpha", 70) }
            }
            chip(R.id.stWrap, R.id.stPill, R.id.st, stTxt); chip(R.id.frWrap, R.id.frPill, R.id.fr, frTxt)
            r.setViewVisibility(R.id.chips, if (stTxt != null || frTxt != null) View.VISIBLE else View.GONE)
            val span = (p.i("sleep") - p.i("wake")) * 3600000L
            val dp = ((System.currentTimeMillis() - t0 - p.i("wake") * 3600000L) * 100 / span).toInt().coerceIn(0, 100)
            r.setViewVisibility(R.id.dayp, if (p.i("dayp") == 1) View.VISIBLE else View.GONE)
            r.bar(R.id.dtrack, R.id.dfill, dp, p)
            r.setOnClickPendingIntent(R.id.date, if (p.i("dtap") == 1) PendingIntent.getActivity(c, 12, Intent(c, Month::class.java), PendingIntent.FLAG_IMMUTABLE) else open(c, 1) {})
            val g = Repo.freeGap(td, p)
            r.setOnClickPendingIntent(R.id.add, open(c, 2) {
                putExtra("new", true)
                if (g != null) { val b = (g.first + 899999) / 900000 * 900000; putExtra("gb", b); putExtra("ge", minOf(g.second, b + 3600000L).coerceAtLeast(b + 900000L)) }
            })
            r.setOnClickPendingIntent(R.id.cfg, PendingIntent.getActivity(c, 100 + id, Intent(c, WidgetSettings::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK), PendingIntent.FLAG_IMMUTABLE))
            if (!preview) {
                r.setRemoteAdapter(R.id.list, Intent(c, ListService::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).apply { data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME)) })
                r.setPendingIntentTemplate(R.id.list, PendingIntent.getActivity(c, 4, Intent(c, Open::class.java), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                // Keep the current event pinned near the top: earlier events sit above and appear when you scroll up.
                if (p.i("anch") == 1 && p.i("ended") == 1) {
                    val rs = buildRows(c, p).rows; val now = System.currentTimeMillis(); val a = anchorIndex(rs, now)
                    if (a > 0) {
                        val hdp = AppWidgetManager.getInstance(c).getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 300)
                        val room = hdp - 72 - (if (stTxt != null || frTxt != null) 28 else 0) - (if (p.i("dayp") == 1) 9 else 0)
                        val sz = p.i("size"); val gp = p.i("gap")
                        fun hh(row: Any): Int = when {
                            row === NowLine -> 10
                            row is Ev -> (2 * gp + 16 + sz * 1.25 + sz * 0.9 + (if (row.start <= now && row.end > now && !row.allDay && p.i("prog") == 1) 11 else 0)).toInt()
                            else -> 26
                        }
                        var t = a; var used = 0
                        while (t < rs.size) { used += hh(rs[t]); if (used > room) break; t++ }
                        r.setScrollPosition(R.id.list, maxOf(a, minOf(t - 1, rs.size - 1)))
                    }
                }
            }
            return r
        }
    }
}

// Re-runs only when the calendar database changes (i.e. when Google sync delivers something) – battery-friendly.
class ChangeJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        CalWidget.refresh(this); schedule(this); jobFinished(params, false); return false
    }
    override fun onStopJob(params: JobParameters) = true
    companion object {
        fun schedule(c: Context) {
            val ji = JobInfo.Builder(1, ComponentName(c, ChangeJob::class.java))
                .addTriggerContentUri(JobInfo.TriggerContentUri(CalendarContract.CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                .setTriggerContentUpdateDelay(1000).setTriggerContentMaxDelay(5000).build()
            c.getSystemService(JobScheduler::class.java).schedule(ji)
        }
    }
}

class ListService : RemoteViewsService() {
    override fun onGetViewFactory(i: Intent): RemoteViewsFactory = Factory(applicationContext, i.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0))
}

fun RemoteViews.bar2(track: Int, fill: Int, pct: Int, tc: Int, fc: Int, shape: Int = 0, box: Int = 0, hDp: Int = 0) {
    setImageViewResource(track, intArrayOf(R.drawable.pill, R.drawable.soft, R.drawable.rect)[shape])
    setImageViewResource(fill, intArrayOf(R.drawable.clip_pill, R.drawable.clip_soft, R.drawable.clip_rect)[shape])
    if (box != 0 && hDp > 0 && Build.VERSION.SDK_INT >= 31) setViewLayoutHeight(box, hDp.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
    setInt(track, "setColorFilter", tc or 0xFF000000.toInt()); setInt(track, "setImageAlpha", Color.alpha(tc))
    setInt(fill, "setColorFilter", fc or 0xFF000000.toInt()); setInt(fill, "setImageLevel", pct.coerceIn(0, 100) * 100)
}
fun RemoteViews.bar(track: Int, fill: Int, pct: Int, p: Prefs) = bar2(track, fill, pct, (p.i("sub") and 0xFFFFFF) or 0x32000000, p.i("acc"))

data class Rule(val k: String, val color: Int, val prog: Boolean, val notif: Boolean, val study: Boolean)
object Rules {
    fun load(p: Prefs): List<Rule> {
        val js = p.s("rules")
        if (js.isNotEmpty()) try {
            val a = JSONArray(js)
            return (0 until a.length()).map { val o = a.getJSONObject(it); Rule(o.getString("k"), o.getInt("c"), o.optInt("p", 1) == 1, o.optInt("n", 1) == 1, o.optInt("s", 1) == 1) }
        } catch (_: Exception) {}
        return p.s("kw").split(",").map { it.trim() }.filter { it.isNotEmpty() }.mapIndexed { i, k -> Rule(k, PAL[i % PAL.size], true, true, true) }
    }
    fun save(p: Prefs, l: List<Rule>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("k", it.k).put("c", it.color).put("p", if (it.prog) 1 else 0).put("n", if (it.notif) 1 else 0).put("s", if (it.study) 1 else 0)) }
        p.putS("rules", a.toString())
    }
    fun match(l: List<Rule>, title: String) = l.firstOrNull { title.contains(it.k, ignoreCase = true) }
}

data class Summary(val t: String)
val PAL = listOf(0xFFEF5350, 0xFF42A5F5, 0xFF66BB6A, 0xFFFFCA28, 0xFFAB47BC, 0xFF26C6DA, 0xFFFF7043, 0xFFEC407A).map { it.toInt() }

// Ongoing notification with a custom progress card; countdown clock is drawn by Android itself (no wake-ups).
object Live {
    fun update(c: Context, p: Prefs, ev: List<Ev>) {
        val now = System.currentTimeMillis(); val rules = Rules.load(p)
        val e = if (p.i("live") == 1) ev.firstOrNull { !it.allDay && it.start <= now && it.end > now && Rules.match(rules, it.title)?.notif != false } else null
        if (e == null) { c.getSystemService(NotificationManager::class.java).cancel(77); return }
        post(c, p, e, ev.firstOrNull { !it.allDay && it.start > now })
    }
    fun demo(c: Context, p: Prefs) {
        val now = System.currentTimeMillis()
        post(c, p, Ev(0, "Audit revision", now - 40 * 60000L, now + 80 * 60000L, false, p.i("acc")), Ev(0, "Lunch", now + 95 * 60000L, now + 140 * 60000L, false, 0))
    }
    private fun post(c: Context, p: Prefs, e: Ev, next: Ev?) {
        val nm = c.getSystemService(NotificationManager::class.java)
        val now = System.currentTimeMillis(); val rule = Rules.match(Rules.load(p), e.title)
        val prominent = p.i("nprio") == 1; val lock = p.i("nlock") == 1
        val chan = "live_${p.i("nprio")}_${p.i("nlock")}"
        nm.createNotificationChannel(NotificationChannel(chan, "Current event", if (prominent) NotificationManager.IMPORTANCE_DEFAULT else NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null); enableVibration(false); setShowBadge(false)
            lockscreenVisibility = if (lock) Notification.VISIBILITY_PUBLIC else Notification.VISIBILITY_PRIVATE
        })
        val h24 = p.i("h24") == 1; val pct = ((now - e.start) * 100 / maxOf(e.end - e.start, 1)).toInt().coerceIn(0, 100)
        val showBar = rule?.prog != false
        val evCol = (if (rule != null && p.i("kwc") == 1) rule.color else e.color) or 0xFF000000.toInt()
        val mode = p.i("ncol")
        val bgc = when (mode) { 1 -> evCol; 2 -> p.i("acc") or 0xFF000000.toInt(); else -> 0xFF1C1C21.toInt() }
        var barCol = (when (p.i("nbc")) { 0 -> p.i("acc"); 1 -> evCol; 3 -> p.i("nbcc"); else -> Color.WHITE }) or 0xFF000000.toInt()
        if (barCol == bgc) barCol = Color.WHITE
        val hDp = intArrayOf(4, 8, 12, 16)[p.i("nbh")]; val hc = intArrayOf(10, 14, 18, 20)[p.i("nbhc")]; val tf = p.i("tfmt"); val lab = p.i("tlab")
        val soft = 0xCCFFFFFF.toInt()
        val range = "${Repo.time(e.start, h24)} - ${Repo.time(e.end, h24)}"
        fun paint(r: RemoteViews, big: Boolean) {
            r.setInt(R.id.nb_bg, "setColorFilter", bgc); r.setInt(R.id.nb_bg, "setImageAlpha", 255)
            r.setTextViewText(R.id.nb_t, e.title); r.setTextColor(R.id.nb_t, Color.WHITE)
            r.setViewVisibility(R.id.nb_c, if (tf == 0) View.VISIBLE else View.GONE)
            r.setViewVisibility(R.id.nb_r, if (tf == 0) View.GONE else View.VISIBLE)
            if (tf == 0) {
                r.setChronometer(R.id.nb_c, SystemClock.elapsedRealtime() + (e.end - now), "%s" + arrayOf(" left", " remaining", "")[lab], true)
                r.setChronometerCountDown(R.id.nb_c, true); r.setTextColor(R.id.nb_c, Color.WHITE)
            } else { r.setTextViewText(R.id.nb_r, Repo.remain(e.end - now, tf, lab)); r.setTextColor(R.id.nb_r, Color.WHITE) }
            r.setViewVisibility(R.id.nb_row, if (showBar) View.VISIBLE else View.GONE)
            r.bar2(R.id.nb_btrack, R.id.nb_bfill, pct, 0x66FFFFFF, barCol, p.i("nbr"), R.id.nb_bar, if (big) hDp else hc)
            r.setViewVisibility(R.id.nb_p, if (p.i("npct") == 1) View.VISIBLE else View.GONE)
            r.setTextViewText(R.id.nb_p, "$pct%"); r.setTextColor(R.id.nb_p, Color.WHITE)
            if (big) {
                r.setTextViewText(R.id.nb_s, range); r.setTextColor(R.id.nb_s, soft)
                val nx = if (p.i("nnext") == 1 && next != null) "Next · ${next.title} at ${Repo.time(next.start, h24)}" else ""
                r.setViewVisibility(R.id.nb_next, if (nx.isEmpty()) View.GONE else View.VISIBLE)
                r.setTextViewText(R.id.nb_next, nx); r.setTextColor(R.id.nb_next, soft)
            }
        }
        val b = Notification.Builder(c, chan).setSmallIcon(R.drawable.ic_t_events).setContentTitle(e.title).setContentText("${Repo.remain(e.end - now, if (tf == 2) 2 else 1, lab)}  •  $pct%")
            .setStyle(Notification.DecoratedCustomViewStyle()).setCustomContentView(RemoteViews(c.packageName, R.layout.notif_small).also { paint(it, false) })
            .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false).setTimeoutAfter(maxOf(e.end - now, 1000L)).setCategory(Notification.CATEGORY_PROGRESS)
            .setColor(evCol).setVisibility(if (lock) Notification.VISIBILITY_PUBLIC else Notification.VISIBILITY_PRIVATE)
            .setContentIntent(PendingIntent.getActivity(c, 0, Intent(c, Open::class.java), PendingIntent.FLAG_IMMUTABLE))
        if (p.i("nstyle") == 1) b.setCustomBigContentView(RemoteViews(c.packageName, R.layout.notif_big).also { paint(it, true) })
        if (!lock) b.setPublicVersion(Notification.Builder(c, chan).setSmallIcon(R.drawable.ic_t_events).setContentTitle("Event in progress").build())
        if (p.i("pin") == 1) b.setDeleteIntent(PendingIntent.getBroadcast(c, 10, Intent(c, Remind::class.java), PendingIntent.FLAG_IMMUTABLE))
        nm.notify(77, b.build())
    }
}

object NowLine

class RowSet(val rows: List<Any>, val nextStart: Long)

fun buildRows(c: Context, p: Prefs): RowSet {
    val now = System.currentTimeMillis()
    if (c.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return RowSet(listOf("OPEN THE APP AND ALLOW CALENDAR ACCESS"), -1L)
    val day = SimpleDateFormat("yyyyMMdd", Locale.US)
    val today = day.format(now); val tomorrow = day.format(now + 86400000L)
    val evs = Repo.load(c, p.i("days")).filter { p.i("ended") == 1 || it.end > now }
    val out = ArrayList<Any>(); var last = ""; var line = false
    fun flush() { if (!line && last == today) { out.add(NowLine); line = true } }
    for (e in evs) {
        val ref = maxOf(e.start, now); val k = day.format(ref)
        if (k != last) {
            flush(); last = k
            out.add(when (k) { today -> "TODAY"; tomorrow -> "TOMORROW"; else -> SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(ref).uppercase() })
        }
        if (k == today && !line && e.start > now) { out.add(NowLine); line = true }
        out.add(e)
    }
    flush()
    return RowSet(out, evs.firstOrNull { it.start > now && !it.allDay }?.start ?: -1L)
}

fun anchorIndex(rows: List<Any>, now: Long): Int {
    val live = rows.indexOfFirst { it is Ev && it.start <= now && it.end > now && !it.allDay }
    if (live >= 0) return live
    val nl = rows.indexOf(NowLine)
    return if (nl >= 0) nl else 0
}

class Factory(private val c: Context, private val wid: Int) : RemoteViewsService.RemoteViewsFactory {
    private var rows = listOf<Any>()
    private var nextStart = -1L
    override fun onCreate() {}
    override fun onDestroy() {}
    override fun onDataSetChanged() {
        try { build() } catch (e: Exception) { Prefs(c).putS("err", e.toString().take(160)); rows = listOf("COULDN'T LOAD EVENTS - OPEN THE APP, MORE, HEALTH") }
    }
    private fun build() { val rs = buildRows(c, Prefs(c, wid)); rows = rs.rows; nextStart = rs.nextStart }
    override fun getCount() = rows.size
    override fun getViewAt(i: Int): RemoteViews = try { make(i) } catch (e: Exception) {
        RemoteViews(c.packageName, R.layout.hdr).apply { setTextViewText(R.id.h, "ERROR - OPEN THE APP, MORE, HEALTH") }
    }
    private fun make(i: Int): RemoteViews {
        val p = Prefs(c, wid); val row = rows[i]
        if (row is String) return RemoteViews(c.packageName, HDRS[p.i("font")]).apply { setTextViewText(R.id.h, row); setTextColor(R.id.h, p.i("sub")) }
        if (row is Summary) return RemoteViews(c.packageName, HDRS[p.i("font")]).apply { setTextViewText(R.id.h, row.t); setTextColor(R.id.h, p.i("acc")) }
        if (row === NowLine) return RemoteViews(c.packageName, R.layout.nowline).apply { setInt(R.id.line, "setBackgroundColor", p.i("acc")) }
        val e = row as Ev; val now = System.currentTimeMillis(); val h24 = p.i("h24") == 1
        val ended = e.end <= now; val live = e.start <= now && !ended && !e.allDay; val next = e.start == nextStart
        val dim = if (ended) 0.4f else 1f
        val rule = Rules.match(Rules.load(p), e.title)
        val col = if (rule != null && p.i("kwc") == 1) rule.color else e.color
        fun a(col: Int) = (col and 0xFFFFFF) or ((Color.alpha(col) * dim).toInt() shl 24)
        val badge = when { e.allDay || ended -> null; live -> Repo.remain(e.end - now, if (p.i("tfmt") == 2) 2 else 1, p.i("tlab")); next -> "in ${Repo.fmt(e.start - now)}"; else -> Repo.fmt(e.end - e.start) }
        val range = if (e.allDay) "All day" else "${Repo.time(e.start, h24)} - ${Repo.time(e.end, h24)}"
        val gap = (p.i("gap") * c.resources.displayMetrics.density).toInt()
        return RemoteViews(c.packageName, ITEMS[p.i("font")]).apply {
            val sz = p.i("size").toFloat()
            setViewPadding(R.id.row, 0, gap, 0, gap)
            setImageViewResource(R.id.blk, intArrayOf(R.drawable.blk0, R.drawable.blk1, R.drawable.blk2)[p.i("round")])
            setInt(R.id.blk, "setColorFilter", col); setInt(R.id.blk, "setImageAlpha", (p.i("bopa") * 255 / 100 * dim).toInt())
            setTextViewText(R.id.t, e.title); setTextColor(R.id.t, a(if (next) p.i("acc") else p.i("title"))); setTextViewTextSize(R.id.t, TypedValue.COMPLEX_UNIT_SP, sz)
            setTextViewText(R.id.s, range); setTextColor(R.id.s, a(p.i("sub"))); setTextViewTextSize(R.id.s, TypedValue.COMPLEX_UNIT_SP, sz * 0.7f)
            setViewVisibility(R.id.bar, if (p.i("bar") == 1) View.VISIBLE else View.GONE)
            setInt(R.id.bar, "setBackgroundColor", col)
            setViewVisibility(R.id.badgeWrap, if (badge != null) View.VISIBLE else View.GONE)
            if (badge != null) {
                setTextViewText(R.id.badge, badge); setTextViewTextSize(R.id.badge, TypedValue.COMPLEX_UNIT_SP, sz * 0.6f)
                setTextColor(R.id.badge, if (live) Color.WHITE else p.i("title"))
                setInt(R.id.pill, "setColorFilter", p.i("acc") or 0xFF000000.toInt()); setInt(R.id.pill, "setImageAlpha", if (live) 255 else 80)
            }
            val showP = live && p.i("prog") == 1 && rule?.prog != false
            setViewVisibility(R.id.p, if (showP) View.VISIBLE else View.GONE)
            if (showP) {
                bar(R.id.ptrack, R.id.pfill, ((now - e.start) * 100 / (e.end - e.start)).toInt(), p)
            }
            val f = Intent()
            when (p.i("tap")) { 0 -> f.putExtra("id", e.id).putExtra("b", e.start).putExtra("e", e.end); 2 -> f.putExtra("none", true) }
            setOnClickFillInIntent(R.id.row, f)
        }
    }
    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount() = 3
    override fun getItemId(i: Int) = i.toLong()
    override fun hasStableIds() = true
}

object Reminders {
    private fun pi(c: Context, e: Ev?) = PendingIntent.getBroadcast(c, 9, Intent(c, Remind::class.java).apply {
        if (e != null) { putExtra("t", e.title); putExtra("s", e.start) }
    }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun schedule(c: Context, p: Prefs, ev: List<Ev>) {
        val am = c.getSystemService(AlarmManager::class.java)
        val lead = intArrayOf(5, 10, 15, 30)[p.i("lead")] * 60000L
        val now = System.currentTimeMillis()
        val e = if (p.i("rem") == 1) ev.filter { !it.allDay && it.start - lead > now }.minByOrNull { it.start } else null
        if (e == null) { am.cancel(pi(c, null)); return }
        val t = e.start - lead
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi(c, e))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi(c, e))
    }
}

class Remind : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val s = i.getLongExtra("s", 0)
        if (s > 0) {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("rem", "Event reminders", NotificationManager.IMPORTANCE_HIGH))
            val mins = (s - System.currentTimeMillis() + 30000) / 60000
            nm.notify((s / 60000).toInt(), Notification.Builder(c, "rem").setSmallIcon(android.R.drawable.ic_menu_my_calendar)
                .setContentTitle(i.getStringExtra("t")).setContentText("Starts in ${mins}m  •  ${Repo.time(s, Prefs(c).i("h24") == 1)}")
                .setAutoCancel(true).setContentIntent(PendingIntent.getActivity(c, 0, Intent(c, Open::class.java), PendingIntent.FLAG_IMMUTABLE)).build())
        }
        CalWidget.refresh(c)
    }
}

class NextWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = CalWidget.refresh(c)
    override fun onEnabled(c: Context) = ChangeJob.schedule(c)
    companion object {
        fun update(c: Context) {
            val m = AppWidgetManager.getInstance(c)
            val ids = m.getAppWidgetIds(ComponentName(c, NextWidget::class.java))
            if (ids.isEmpty()) return
            val p = Prefs(c); val now = System.currentTimeMillis(); val h24 = p.i("h24") == 1
            val e = Repo.load(c, p.i("days")).firstOrNull { it.end > now && !it.allDay }
            val live = e != null && e.start <= now
            val r = RemoteViews(c.packageName, R.layout.next)
            r.setInt(R.id.nbg, "setColorFilter", p.i("bg") or 0xFF000000.toInt()); r.setInt(R.id.nbg, "setImageAlpha", Color.alpha(p.i("bg")))
            r.setTextViewText(R.id.nl, if (e == null) "ALL CLEAR" else if (live) "NOW · ENDS IN" else "NEXT · STARTS IN")
            r.setTextViewText(R.id.nt, e?.title ?: "No more events")
            r.setTextViewText(R.id.ns, if (e == null) "" else "${Repo.time(e.start, h24)} - ${Repo.time(e.end, h24)}")
            r.setTextColor(R.id.nl, p.i("sub")); r.setTextColor(R.id.nt, p.i("title")); r.setTextColor(R.id.ns, p.i("sub"))
            r.setViewVisibility(R.id.nch, if (e == null) View.GONE else View.VISIBLE)
            r.setViewVisibility(R.id.np, if (live) View.VISIBLE else View.GONE)
            if (e != null) {
                r.setChronometer(R.id.nch, SystemClock.elapsedRealtime() + ((if (live) e.end else e.start) - now), null, true)
                r.setChronometerCountDown(R.id.nch, true)
                r.setTextColor(R.id.nch, p.i("acc")); r.setTextViewTextSize(R.id.nch, TypedValue.COMPLEX_UNIT_SP, 30f)
                if (live) {
                    r.bar(R.id.ntrack, R.id.nfill, ((now - e.start) * 100 / (e.end - e.start)).toInt(), p)
                }
                r.setOnClickPendingIntent(R.id.nroot, PendingIntent.getActivity(c, 6, Intent(c, Open::class.java).putExtra("id", e.id).putExtra("b", e.start).putExtra("e", e.end), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
            m.updateAppWidget(ids, r)
        }
    }
}

object Morning {
    private fun pi(c: Context) = PendingIntent.getBroadcast(c, 11, Intent(c, MorningRx::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    fun schedule(c: Context, p: Prefs) {
        val am = c.getSystemService(AlarmManager::class.java)
        if (p.i("morn") == 0) { am.cancel(pi(c)); return }
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, p.i("mornh")); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis() + 1000) add(Calendar.DAY_OF_YEAR, 1)
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi(c))
    }
}

class MorningRx : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val p = Prefs(c)
        if (p.i("morn") == 1) {
            val now = System.currentTimeMillis(); val h24 = p.i("h24") == 1
            val ev = Repo.load(c, 1).filter { it.end > now }
            val lines = ev.take(6).joinToString("\n") { (if (it.allDay) "All day" else Repo.time(it.start, h24)) + "  " + it.title }
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("morn", "Morning summary", NotificationManager.IMPORTANCE_DEFAULT))
            nm.notify(88, Notification.Builder(c, "morn").setSmallIcon(android.R.drawable.ic_menu_my_calendar)
                .setContentTitle(if (ev.isEmpty()) "No events today 🎉" else "Today: ${ev.size} event${if (ev.size > 1) "s" else ""}")
                .setContentText(lines.lineSequence().firstOrNull() ?: "").setStyle(Notification.BigTextStyle().bigText(lines)).setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(c, 0, Intent(c, Open::class.java), PendingIntent.FLAG_IMMUTABLE)).build())
        }
        CalWidget.refresh(c)
    }
}

class Month : Activity() {
    private val base = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    private lateinit var box: LinearLayout
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        setContentView(ScrollView(this).apply { addView(box) })
        draw()
    }
    private fun tv(t: String, col: Int, sz: Float = 14f, click: (() -> Unit)? = null) = TextView(this).apply {
        text = t; setTextColor(col); textSize = sz; gravity = android.view.Gravity.CENTER; setPadding(0, 14, 0, 14)
        if (click != null) setOnClickListener { click() }
    }
    private fun draw() {
        val p = Prefs(this); val tc = p.i("title"); val sc = p.i("sub"); val ac = p.i("acc")
        box.removeAllViews(); box.setBackgroundColor(p.i("bg") or 0xFF000000.toInt())
        val head = LinearLayout(this)
        head.addView(tv("‹", tc, 24f) { base.add(Calendar.MONTH, -1); draw() }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(tv(SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(base.time), tc, 18f), LinearLayout.LayoutParams(0, -2, 3f))
        head.addView(tv("›", tc, 24f) { base.add(Calendar.MONTH, 1); draw() }, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(head)
        val n = base.getActualMaximum(Calendar.DAY_OF_MONTH)
        val cnt = HashMap<Int, Int>()
        for (e in Repo.load(this, n, base.timeInMillis)) { val d = Calendar.getInstance().apply { timeInMillis = maxOf(e.start, base.timeInMillis) }.get(Calendar.DAY_OF_MONTH); cnt[d] = (cnt[d] ?: 0) + 1 }
        val g = GridLayout(this).apply { columnCount = 7 }
        fun cell(v: View) { g.addView(v, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply { width = 0 }) }
        val fd = base.firstDayOfWeek
        for (i in 0 until 7) cell(tv(SimpleDateFormat("EEEEE", Locale.getDefault()).format(Calendar.getInstance().apply { set(Calendar.DAY_OF_WEEK, (fd - 1 + i) % 7 + 1) }.time), sc, 12f))
        repeat((base.get(Calendar.DAY_OF_WEEK) - fd + 7) % 7) { cell(View(this)) }
        val now = Calendar.getInstance()
        for (d in 1..n) {
            val day = (base.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, d) }
            val today = day.get(Calendar.YEAR) == now.get(Calendar.YEAR) && day.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
            cell(tv("$d\n" + "•".repeat(minOf(cnt[d] ?: 0, 3)).ifEmpty { " " }, if (today) ac else tc) { openDay(day.timeInMillis) })
        }
        box.addView(g)
    }
    private fun openDay(ms: Long) {
        val notion = packageManager.getLaunchIntentForPackage("com.cron.calendar")
        if (Prefs(this).i("app") == 1 && notion != null) startActivity(notion)
        else try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("content://com.android.calendar/time/$ms"))) } catch (_: Exception) { notion?.let { startActivity(it) } }
        finish()
    }
}

class Open : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (intent.getBooleanExtra("none", false)) { finish(); return }
        val id = intent.getLongExtra("id", 0)
        val x = when {
            intent.getBooleanExtra("new", false) -> Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).apply {
                val s = this@Open.intent
                if (s.hasExtra("gb")) { putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, s.getLongExtra("gb", 0)); putExtra(CalendarContract.EXTRA_EVENT_END_TIME, s.getLongExtra("ge", 0)) }
            }
            id > 0 -> Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id))
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, intent.getLongExtra("b", 0))
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, intent.getLongExtra("e", 0))
            else -> Intent(Intent.ACTION_VIEW, Uri.parse("content://com.android.calendar/time/" + System.currentTimeMillis()))
        }
        val notion = packageManager.getLaunchIntentForPackage("com.cron.calendar")
        if (Prefs(this).i("app") == 1 && notion != null) startActivity(notion)
        else try { startActivity(x) } catch (_: Exception) { notion?.let { startActivity(it) } }
        finish()
    }
}

