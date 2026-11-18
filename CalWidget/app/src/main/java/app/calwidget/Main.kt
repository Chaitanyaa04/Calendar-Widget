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
import android.provider.CalendarContract
import android.provider.CalendarContract.Instances as I
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*

private val DEF = mapOf(
    "bg" to 0xFF000000.toInt(), "title" to 0xFFE6E6E6.toInt(), "sub" to 0xFFBDBDBD.toInt(), "acc" to 0xFFFF4081.toInt(),
    "size" to 20, "bopa" to 14, "round" to 1, "gap" to 3, "tap" to 0, "icons" to 1, "ended" to 1, "dfmt" to 0, "app" to 0, "rem" to 0, "lead" to 1, "auto" to 0, "live" to 0, "lint" to 1, "kwc" to 1, "sumr" to 1, "free" to 1, "freemin" to 60, "dayp" to 1, "wake" to 6, "sleep" to 23, "font" to 0, "dtap" to 1, "morn" to 0, "mornh" to 7, "pin" to 1, "days" to 7, "h24" to 0, "bar" to 1, "prog" to 1
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
            for (id in ids) m.updateAppWidget(id, view(c, Prefs(c, id), id))
            if (ids.isNotEmpty()) m.notifyAppWidgetViewDataChanged(ids, R.id.list)
            NextWidget.update(c)
            scheduleNext(c, p)
            if (c.getSystemService(JobScheduler::class.java).getPendingJob(1) == null) ChangeJob.schedule(c)
        }

        // One inexact, non-waking alarm at the next moment the display actually changes. Zero polling.
        private fun scheduleNext(c: Context, p: Prefs) {
            val now = System.currentTimeMillis()
            val ev = Repo.load(c, p.i("days"))
            Reminders.schedule(c, p, ev); Live.update(c, p, ev); Morning.schedule(c, p)
            val mid = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 5) }.timeInMillis
            var t = mid
            for (e in ev) { if (e.start > now) t = minOf(t, e.start); if (e.end > now) t = minOf(t, e.end) }
            if ((p.i("prog") == 1 || p.i("live") == 1) && ev.any { it.start <= now && it.end > now && !it.allDay }) t = minOf(t, now + (if (p.i("lint") == 0) 5 else 15) * 60000L)
            c.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC, maxOf(t, now + 2000), alarmIntent(c))
        }

        private fun open(c: Context, rc: Int, f: Intent.() -> Unit) = PendingIntent.getActivity(c, rc,
            Intent(c, Open::class.java).apply(f), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        fun view(c: Context, p: Prefs, id: Int, preview: Boolean = false): RemoteViews {
            val r = RemoteViews(c.packageName, WIDGETS[p.i("font")])
            val tc = p.i("title")
            r.setInt(R.id.bg, "setColorFilter", p.i("bg") or 0xFF000000.toInt())
            r.setInt(R.id.bg, "setImageAlpha", Color.alpha(p.i("bg")))
            for (v in listOf(R.id.add, R.id.cfg, R.id.ref)) r.setViewVisibility(v, if (p.i("icons") == 1) View.VISIBLE else View.GONE)
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
            val span = (p.i("sleep") - p.i("wake")) * 3600000L
            val dp = ((System.currentTimeMillis() - t0 - p.i("wake") * 3600000L) * 100 / span).toInt().coerceIn(0, 100)
            r.setViewVisibility(R.id.dayp, if (p.i("dayp") == 1) View.VISIBLE else View.GONE)
            r.setProgressBar(R.id.dayp, 100, dp, false)
            if (Build.VERSION.SDK_INT >= 31) {
                r.setColorStateList(R.id.dayp, "setProgressTintList", ColorStateList.valueOf(p.i("acc")))
                r.setColorStateList(R.id.dayp, "setProgressBackgroundTintList", ColorStateList.valueOf((p.i("sub") and 0xFFFFFF) or 0x40000000))
            }
            r.setOnClickPendingIntent(R.id.date, if (p.i("dtap") == 1) PendingIntent.getActivity(c, 12, Intent(c, Month::class.java), PendingIntent.FLAG_IMMUTABLE) else open(c, 1) {})
            val g = Repo.freeGap(Repo.load(c, 1), p)
            r.setOnClickPendingIntent(R.id.add, open(c, 2) {
                putExtra("new", true)
                if (g != null) { val b = (g.first + 899999) / 900000 * 900000; putExtra("gb", b); putExtra("ge", minOf(g.second, b + 3600000L).coerceAtLeast(b + 900000L)) }
            })
            r.setOnClickPendingIntent(R.id.cfg, PendingIntent.getActivity(c, 100 + id, Intent(c, SettingsActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id), PendingIntent.FLAG_IMMUTABLE))
            if (!preview) {
                r.setRemoteAdapter(R.id.list, Intent(c, ListService::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).apply { data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME)) })
                r.setPendingIntentTemplate(R.id.list, PendingIntent.getActivity(c, 4, Intent(c, Open::class.java), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
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

data class Summary(val t: String)
val PAL = listOf(0xFFEF5350, 0xFF42A5F5, 0xFF66BB6A, 0xFFFFCA28, 0xFFAB47BC, 0xFF26C6DA, 0xFFFF7043, 0xFFEC407A).map { it.toInt() }

// Silent, ongoing notification: system-driven countdown clock + progress bar; auto-dismisses when the event ends.
object Live {
    fun update(c: Context, p: Prefs, ev: List<Ev>) {
        val nm = c.getSystemService(NotificationManager::class.java)
        val now = System.currentTimeMillis()
        val e = if (p.i("live") == 1) ev.firstOrNull { !it.allDay && it.start <= now && it.end > now } else null
        if (e == null) { nm.cancel(77); return }
        nm.createNotificationChannel(NotificationChannel("live", "Current event (silent)", NotificationManager.IMPORTANCE_LOW))
        val h24 = p.i("h24") == 1; val pct = ((now - e.start) * 100 / (e.end - e.start)).toInt()
        nm.notify(77, Notification.Builder(c, "live").setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle(e.title).setContentText("${Repo.time(e.start, h24)} - ${Repo.time(e.end, h24)}  •  $pct%")
            .setProgress(100, pct, false).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(true).setWhen(e.end)
            .setUsesChronometer(true).setChronometerCountDown(true).setTimeoutAfter(e.end - now).setColor(p.i("acc") or 0xFF000000.toInt())
            .apply { if (p.i("pin") == 1) setDeleteIntent(PendingIntent.getBroadcast(c, 10, Intent(c, Remind::class.java), PendingIntent.FLAG_IMMUTABLE)) }
            .setContentIntent(PendingIntent.getActivity(c, 0, Intent(c, Open::class.java), PendingIntent.FLAG_IMMUTABLE)).build())
    }
}

object NowLine

class Factory(private val c: Context, private val wid: Int) : RemoteViewsService.RemoteViewsFactory {
    private var rows = listOf<Any>()
    private var nextStart = -1L
    override fun onCreate() {}
    override fun onDestroy() {}
    override fun onDataSetChanged() {
        val p = Prefs(c, wid); val now = System.currentTimeMillis()
        val day = SimpleDateFormat("yyyyMMdd", Locale.US)
        val today = day.format(now); val tomorrow = day.format(now + 86400000L)
        val all = Repo.load(c, p.i("days"))
        val evs = all.filter { p.i("ended") == 1 || it.end > now }
        nextStart = evs.firstOrNull { it.start > now && !it.allDay }?.start ?: -1L
        val out = ArrayList<Any>(); var last = ""; var line = false
        val h24 = p.i("h24") == 1
        val t0 = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        fun f(ms: Long) = if (ms < 60000) "0m" else Repo.fmt(ms)
        val kws = p.s("kw").split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (p.i("sumr") == 1 && kws.isNotEmpty()) {
            var total = 0L; var done = 0L
            for (e in all) if (!e.allDay && e.start < t0 + 86400000L && e.end > t0 && kws.any { e.title.lowercase().contains(it) }) {
                total += e.end - e.start; done += (minOf(e.end, now) - e.start).coerceAtLeast(0)
            }
            if (total > 0) out.add(Summary("STUDY  ${f(done)} done · ${f(total - done)} left · ${f(total)} planned"))
        }
        if (p.i("free") == 1) {
            val need = p.i("freemin") * 60000L; val dEnd = t0 + p.i("sleep") * 3600000L
            var cur = maxOf(now, t0 + p.i("wake") * 3600000L); var gap: Pair<Long, Long>? = null
            for (b in all.filter { !it.allDay && it.end > cur && it.start < dEnd }.sortedBy { it.start }) {
                if (b.start - cur >= need) { gap = cur to b.start; break }
                cur = maxOf(cur, b.end)
            }
            if (gap == null && dEnd - cur >= need) gap = cur to dEnd
            gap?.let { out.add(Summary("FREE  ${Repo.time(it.first, h24)} - ${Repo.time(it.second, h24)} (${Repo.fmt(it.second - it.first)})")) }
        }
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
        rows = out
    }
    override fun getCount() = rows.size
    override fun getViewAt(i: Int): RemoteViews {
        val p = Prefs(c, wid); val row = rows[i]
        if (row is String) return RemoteViews(c.packageName, HDRS[p.i("font")]).apply { setTextViewText(R.id.h, row); setTextColor(R.id.h, p.i("sub")) }
        if (row is Summary) return RemoteViews(c.packageName, HDRS[p.i("font")]).apply { setTextViewText(R.id.h, row.t); setTextColor(R.id.h, p.i("acc")) }
        if (row === NowLine) return RemoteViews(c.packageName, R.layout.nowline).apply { setInt(R.id.line, "setBackgroundColor", p.i("acc")) }
        val e = row as Ev; val now = System.currentTimeMillis(); val h24 = p.i("h24") == 1
        val ended = e.end <= now; val live = e.start <= now && !ended && !e.allDay; val next = e.start == nextStart
        val dim = if (ended) 0.4f else 1f
        val kws = p.s("kw").split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val ki = kws.indexOfFirst { e.title.lowercase().contains(it) }
        val col = if (ki >= 0 && p.i("kwc") == 1) PAL[ki % PAL.size] else e.color
        fun a(col: Int) = (col and 0xFFFFFF) or ((Color.alpha(col) * dim).toInt() shl 24)
        val badge = when { e.allDay || ended -> null; live -> "${Repo.fmt(e.end - now)} left"; next -> "in ${Repo.fmt(e.start - now)}"; else -> Repo.fmt(e.end - e.start) }
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
            val showP = live && p.i("prog") == 1
            setViewVisibility(R.id.p, if (showP) View.VISIBLE else View.GONE)
            if (showP) {
                setProgressBar(R.id.p, 100, ((now - e.start) * 100 / (e.end - e.start)).toInt(), false)
                if (Build.VERSION.SDK_INT >= 31) {
                    setColorStateList(R.id.p, "setProgressTintList", ColorStateList.valueOf(p.i("acc")))
                    setColorStateList(R.id.p, "setProgressBackgroundTintList", ColorStateList.valueOf((p.i("sub") and 0xFFFFFF) or 0x40000000))
                }
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
                    r.setProgressBar(R.id.np, 100, ((now - e.start) * 100 / (e.end - e.start)).toInt(), false)
                    if (Build.VERSION.SDK_INT >= 31) {
                        r.setColorStateList(R.id.np, "setProgressTintList", ColorStateList.valueOf(p.i("acc")))
                        r.setColorStateList(R.id.np, "setProgressBackgroundTintList", ColorStateList.valueOf((p.i("sub") and 0xFFFFFF) or 0x40000000))
                    }
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

class SettingsActivity : Activity() {
    private lateinit var p: Prefs
    private var wid = 0
    private lateinit var holder: FrameLayout
    private val presets = listOf(0xFF000000, 0xFFFFFFFF, 0xFF212121, 0xFFFF4081, 0xFF7C4DFF, 0xFF2979FF, 0xFF1DE9B6, 0xFF66BB6A, 0xFFFFC107, 0xFFFF6E40, 0xFFF44336, 0xFFBDBDBD).map { it.toInt() }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun save(k: String, v: Int) { p.put(k, v); CalWidget.refresh(this); preview() }

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
                    AlertDialog.Builder(this).setTitle("Update available").setMessage("Build $tag is ready (you have $mine).")
                        .setPositiveButton("Download") { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.setNegativeButton("Later", null).show()
                } else toast("You're up to date (build $mine)")
            } catch (_: Exception) { toast("Couldn't check. The repo must be public and have a release.") }
        }.start()
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b); wid = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0); p = Prefs(this, wid)
        if (checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.READ_CALENDAR), 1)
        val r = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(24), dp(20), dp(40)) }
        holder = FrameLayout(this)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(holder, LinearLayout.LayoutParams(-1, dp(250)).apply { setMargins(dp(12), dp(8), dp(12), dp(4)) })
            addView(ScrollView(this@SettingsActivity).apply { addView(r) }, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        if (wid != 0) {
            label(r, "This widget only")
            r.addView(Button(this).apply { isAllCaps = false; text = "Reset to default look"; setOnClickListener { p.resetWidget(); CalWidget.refresh(this@SettingsActivity); recreate() } })
        } else cals(r)
        themes(r)
        label(r, "Colours")
        color(r, "Background", "bg"); color(r, "Title text", "title"); color(r, "Time text", "sub"); color(r, "Accent (progress bar)", "acc")
        label(r, "Layout")
        seek(r, "Text size", "size", 12, 28, " sp"); seek(r, "Days ahead", "days", 1, 14, " days")
        label(r, "Event blocks")
        seek(r, "Block opacity", "bopa", 0, 100, "%"); seek(r, "Space between events", "gap", 0, 12, " dp")
        radio(r, "Corners", "round", listOf("Square", "Soft", "Round"))
        label(r, "Behaviour")
        radio(r, "Tap on event", "tap", listOf("Open event", "Open day", "Nothing"))
        radio(r, "Open calendar with", "app", listOf("Google / default", "Notion Calendar"))
        switch(r, "Show finished events (dimmed)", "ended"); switch(r, "Show buttons (refresh, +, settings)", "icons")
        switch(r, "24-hour time", "h24"); switch(r, "Calendar colour bar", "bar"); switch(r, "Progress bar on current event", "prog")
        radio(r, "Font", "font", listOf("Default", "Condensed", "Serif", "Mono", "Casual"))
        radio(r, "Tap on date", "dtap", listOf("Open calendar", "Month popup"))
        radio(r, "Date format", "dfmt", listOf("Wednesday, Nov 18", "Wed, 18 Nov", "Wed, 18 Nov · W47", "Wednesday 18 November"))
        if (wid == 0) {
        label(r, "Reminders")
        switch(r, "Notify before events", "rem") { if (it) askReminderPerms() }
        radio(r, "Notify me", "lead", listOf("5 min", "10 min", "15 min", "30 min") .map { "$it before" })
        label(r, "Live progress notification")
        switch(r, "Silent notification with countdown + progress bar", "live") {
            if (it && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 3)
        }
        radio(r, "Update progress every", "lint", listOf("5 min", "15 min"))
        switch(r, "Keep it pinned (comes back if swiped away)", "pin")
        label(r, "Morning summary")
        switch(r, "Daily notification with today's events", "morn") { if (it) askNotif() }
        seek(r, "Send at", "mornh", 5, 10, ":00")
        }
        label(r, "Student tools")
        text(r, "Subject keywords, comma separated (e.g. Acc, Audit, Tax)", "kw")
        switch(r, "Colour events by keyword", "kwc"); switch(r, "Study hours summary", "sumr"); switch(r, "Next free slot", "free")
        seek(r, "Minimum free slot", "freemin", 30, 180, " min")
        switch(r, "Day progress bar", "dayp"); seek(r, "Day starts at", "wake", 0, 12, ":00"); seek(r, "Day ends at", "sleep", 13, 24, ":00")
        label(r, "Main event countdown")
        text(r, "Label (e.g. CA Inter)", "cdl")
        val fmtD = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
        r.addView(Button(this).apply {
            isAllCaps = false; text = if (p.l("cdt") > 0) "Date: " + fmtD.format(p.l("cdt")) else "Pick countdown date"
            setOnClickListener {
                val c = Calendar.getInstance(); if (p.l("cdt") > 0) c.timeInMillis = p.l("cdt")
                DatePickerDialog(this@SettingsActivity, { _, y, m, d ->
                    p.putL("cdt", Calendar.getInstance().apply { clear(); set(y, m, d) }.timeInMillis)
                    text = "Date: " + fmtD.format(p.l("cdt")); CalWidget.refresh(this@SettingsActivity)
                }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
            }
        })
        r.addView(Button(this).apply { isAllCaps = false; text = "Clear countdown"; setOnClickListener { p.putL("cdt", 0); CalWidget.refresh(this@SettingsActivity); recreate() } })
        label(r, "Backup & diagnostics")
        r.addView(Button(this).apply { isAllCaps = false; text = "Export settings"; setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE, "calwidget-settings.json"), 11) } })
        r.addView(Button(this).apply { isAllCaps = false; text = "Import settings"; setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 12) } })
        r.addView(TextView(this).apply {
            val f = SimpleDateFormat("h:mm a", Locale.getDefault()); setPadding(0, dp(12), 0, 0)
            text = if (p.l("lastr") > 0) "Last refresh: ${f.format(p.l("lastr"))} · ${p.l("cntd")} refreshes today" else "No refresh yet"
        })
        r.addView(Button(this).apply { isAllCaps = false; text = "Check for updates"; setOnClickListener { checkUpdate() } })
        label(r, "Tip: choose which calendars show in the Google Calendar app (menu → calendars).")
        preview()
        if (wid == 0 && p.l("onb") == 0L) {
            p.putL("onb", 1)
            AlertDialog.Builder(this).setTitle("Welcome").setMessage("1. Allow calendar access when asked.\n2. Long-press your home screen, tap Widgets, and add Calendar Widget (or Next event).\n3. Tap the gear on a widget to style that widget on its own.\n4. Turn on reminders and the live notification below if you want them.").setPositiveButton("Got it", null).show()
        }
    }
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

    private fun themes(r: LinearLayout) {
        label(r, "Theme")
        val row = LinearLayout(this)
        listOf("OLED", "Dark", "Light", "You").forEachIndexed { i, n ->
            row.addView(Button(this).apply {
                text = n; isAllCaps = false; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { themeColors(this@SettingsActivity, i).forEachIndexed { j, c -> p.put(TKEYS[j], c) }; p.put("auto", 0); CalWidget.refresh(this@SettingsActivity); recreate() }
            })
        }
        r.addView(row)
        switch(r, "Auto light/dark (OLED at night, Light by day)", "auto") { recreate() }
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

    private fun text(r: LinearLayout, hint: String, k: String) {
        r.addView(EditText(this).apply {
            this.hint = hint; setSingleLine(); setText(p.s(k)); textSize = 14f
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { p.putS(k, s.toString()); CalWidget.refresh(this@SettingsActivity); preview() }
            })
        })
    }

    private fun cals(r: LinearLayout) {
        val hid = p.hidden().toMutableSet()
        val list = Repo.calendars(this)
        if (list.isEmpty()) return
        label(r, "Calendars")
        list.forEach { (id, name, col) ->
            r.addView(CheckBox(this).apply {
                text = name; isChecked = id !in hid; buttonTintList = ColorStateList.valueOf(col or 0xFF000000.toInt())
                setOnCheckedChangeListener { _, on -> if (on) hid.remove(id) else hid.add(id); p.setHidden(hid); CalWidget.refresh(this@SettingsActivity) }
            })
        }
    }

    private fun radio(r: LinearLayout, name: String, k: String, opts: List<String>) {
        r.addView(TextView(this).apply { text = name; setPadding(0, dp(12), 0, dp(4)) })
        r.addView(RadioGroup(this).apply {
            orientation = if (opts.sumOf { it.length } > 28) RadioGroup.VERTICAL else RadioGroup.HORIZONTAL
            opts.forEachIndexed { i, o -> addView(RadioButton(this@SettingsActivity).apply { text = o; id = 1000 + i; isChecked = p.i(k) == i }) }
            setOnCheckedChangeListener { _, id -> save(k, id - 1000) }
        })
    }

    private fun label(r: LinearLayout, t: String) = r.addView(TextView(this).apply { text = t; textSize = 17f; setTypeface(null, Typeface.BOLD); setPadding(0, dp(22), 0, dp(6)) })

    private fun color(r: LinearLayout, name: String, k: String) {
        r.addView(TextView(this).apply { text = name; setPadding(0, dp(10), 0, dp(4)) })
        val hex = EditText(this).apply { hint = "#AARRGGBB"; setSingleLine(); textSize = 14f; setText(String.format("#%08X", p.i(k))) }
        val sw = LinearLayout(this)
        presets.forEach { col ->
            sw.addView(View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(col); setStroke(2, Color.GRAY) }
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { setMargins(dp(3), 0, dp(3), 0) }
                setOnClickListener { hex.setText(String.format("#%08X", (p.i(k) and 0xFF000000.toInt()) or (col and 0xFFFFFF))) }
            })
        }
        hex.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { if (s?.length == 7 || s?.length == 9) try { save(k, Color.parseColor(s.toString())) } catch (_: Exception) {} }
        })
        r.addView(HorizontalScrollView(this).apply { addView(sw) }); r.addView(hex)
        val ot = TextView(this).apply { text = "Opacity: ${Color.alpha(p.i(k)) * 100 / 255}%" }
        r.addView(ot)
        r.addView(SeekBar(this).apply {
            max = 100; progress = Color.alpha(p.i(k)) * 100 / 255
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, user: Boolean) { ot.text = "Opacity: $v%" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { save(k, (p.i(k) and 0xFFFFFF) or ((progress * 255 / 100) shl 24)); hex.setText(String.format("#%08X", p.i(k))) }
            })
        })
    }

    private fun seek(r: LinearLayout, name: String, k: String, min: Int, max: Int, unit: String) {
        val tv = TextView(this).apply { setPadding(0, dp(10), 0, 0); text = "$name: ${p.i(k)}$unit" }
        r.addView(tv)
        r.addView(SeekBar(this).apply {
            this.max = max - min; progress = p.i(k) - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, user: Boolean) { tv.text = "$name: ${v + min}$unit" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { save(k, progress + min) }
            })
        })
    }

    private fun switch(r: LinearLayout, name: String, k: String, after: ((Boolean) -> Unit)? = null) = r.addView(Switch(this).apply {
        text = name; isChecked = p.i(k) == 1; setPadding(0, dp(12), 0, dp(12))
        setOnCheckedChangeListener { _, on -> save(k, if (on) 1 else 0); after?.invoke(on) }
    })
}
