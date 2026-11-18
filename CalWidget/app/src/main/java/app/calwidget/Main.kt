package app.calwidget

import android.app.*
import android.app.job.*
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.*
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
    "alpha" to 100, "size" to 20, "days" to 7, "h24" to 0, "bar" to 1, "prog" to 1
)

class Prefs(c: Context) {
    private val p = c.getSharedPreferences("cfg", 0)
    fun i(k: String) = p.getInt(k, DEF[k]!!)
    fun put(k: String, v: Int) = p.edit().putInt(k, v).apply()
}

data class Ev(val id: Long, val title: String, val start: Long, val end: Long, val allDay: Boolean, val color: Int)

object Repo {
    // Reads the on-device Calendar Provider, which Google Calendar's own sync keeps up to date (no login/API needed).
    fun load(c: Context, days: Int): List<Ev> {
        if (c.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val from = cal.timeInMillis
        cal.add(Calendar.DAY_OF_YEAR, days)
        val b = I.CONTENT_URI.buildUpon()
        ContentUris.appendId(b, from); ContentUris.appendId(b, cal.timeInMillis)
        val out = ArrayList<Ev>()
        val proj = arrayOf(I.EVENT_ID, I.TITLE, I.BEGIN, I.END, I.ALL_DAY, I.EVENT_COLOR, I.CALENDAR_COLOR)
        try {
            c.contentResolver.query(b.build(), proj, "${I.VISIBLE}=1", null, "${I.BEGIN} ASC")?.use { q ->
                while (q.moveToNext()) {
                    val ad = q.getInt(4) == 1
                    var s = q.getLong(2); var e = q.getLong(3)
                    if (ad) { val tz = TimeZone.getDefault(); s -= tz.getOffset(s); e -= tz.getOffset(e) }
                    val ec = q.getInt(5)
                    out.add(Ev(q.getLong(0), q.getString(1)?.ifBlank { null } ?: "(No title)", s, e, ad, (if (ec != 0) ec else q.getInt(6)) or 0xFF000000.toInt()))
                }
            }
        } catch (_: Exception) {}
        return out.sortedBy { it.start }
    }
    fun time(ms: Long, h24: Boolean) = SimpleDateFormat(if (h24) "HH:mm" else "h:mm a", Locale.getDefault()).format(ms).lowercase()
}

class CalWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = refresh(c)
    override fun onEnabled(c: Context) = ChangeJob.schedule(c)
    override fun onDisabled(c: Context) {
        c.getSystemService(JobScheduler::class.java).cancel(1)
        c.getSystemService(AlarmManager::class.java).cancel(alarmIntent(c))
    }
    override fun onReceive(c: Context, i: Intent) {
        super.onReceive(c, i)
        if (i.action != AppWidgetManager.ACTION_APPWIDGET_UPDATE) refresh(c)
    }

    companion object {
        private fun alarmIntent(c: Context) = PendingIntent.getBroadcast(c, 0,
            Intent(c, CalWidget::class.java).setAction("app.calwidget.REFRESH"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        fun refresh(c: Context) {
            val m = AppWidgetManager.getInstance(c)
            val ids = m.getAppWidgetIds(ComponentName(c, CalWidget::class.java))
            if (ids.isEmpty()) return
            val p = Prefs(c)
            for (id in ids) m.updateAppWidget(id, view(c, p))
            m.notifyAppWidgetViewDataChanged(ids, R.id.list)
            scheduleNext(c, p)
            if (c.getSystemService(JobScheduler::class.java).getPendingJob(1) == null) ChangeJob.schedule(c)
        }

        // One inexact, non-waking alarm at the next moment the display actually changes. Zero polling.
        private fun scheduleNext(c: Context, p: Prefs) {
            val now = System.currentTimeMillis()
            val ev = Repo.load(c, p.i("days"))
            val mid = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 5) }.timeInMillis
            var t = mid
            for (e in ev) { if (e.start > now) t = minOf(t, e.start); if (e.end > now) t = minOf(t, e.end) }
            if (p.i("prog") == 1 && ev.any { it.start <= now && it.end > now && !it.allDay }) t = minOf(t, now + 15 * 60000)
            c.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC, maxOf(t, now + 2000), alarmIntent(c))
        }

        private fun open(c: Context, rc: Int, f: Intent.() -> Unit) = PendingIntent.getActivity(c, rc,
            Intent(c, Open::class.java).apply(f), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        private fun view(c: Context, p: Prefs): RemoteViews {
            val r = RemoteViews(c.packageName, R.layout.widget)
            val tc = p.i("title")
            r.setInt(R.id.bg, "setColorFilter", p.i("bg"))
            r.setInt(R.id.bg, "setImageAlpha", p.i("alpha") * 255 / 100)
            r.setTextViewText(R.id.date, SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).format(Date()))
            r.setTextColor(R.id.date, tc)
            r.setTextViewTextSize(R.id.date, TypedValue.COMPLEX_UNIT_SP, p.i("size") + 2f)
            r.setInt(R.id.add, "setColorFilter", tc); r.setInt(R.id.cfg, "setColorFilter", tc)
            r.setOnClickPendingIntent(R.id.date, open(c, 1) {})
            r.setOnClickPendingIntent(R.id.add, open(c, 2) { putExtra("new", true) })
            r.setOnClickPendingIntent(R.id.cfg, PendingIntent.getActivity(c, 3, Intent(c, SettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            r.setRemoteAdapter(R.id.list, Intent(c, ListService::class.java))
            r.setPendingIntentTemplate(R.id.list, PendingIntent.getActivity(c, 4, Intent(c, Open::class.java), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
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
    override fun onGetViewFactory(i: Intent): RemoteViewsFactory = Factory(applicationContext)
}

class Factory(private val c: Context) : RemoteViewsService.RemoteViewsFactory {
    private var rows = listOf<Any>()
    override fun onCreate() {}
    override fun onDestroy() {}
    override fun onDataSetChanged() {
        val p = Prefs(c); val now = System.currentTimeMillis()
        val day = SimpleDateFormat("yyyyMMdd", Locale.US)
        val today = day.format(now); val tomorrow = day.format(now + 86400000L)
        val out = ArrayList<Any>(); var last = ""
        for (e in Repo.load(c, p.i("days")).filter { it.end > now }) {
            val k = day.format(maxOf(e.start, now))
            if (k != last) {
                last = k
                out.add(when (k) { today -> "TODAY"; tomorrow -> "TOMORROW"
                    else -> SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(maxOf(e.start, now)).uppercase() })
            }
            out.add(e)
        }
        rows = out
    }
    override fun getCount() = rows.size
    override fun getViewAt(i: Int): RemoteViews {
        val p = Prefs(c); val row = rows[i]
        if (row is String) return RemoteViews(c.packageName, R.layout.hdr).apply {
            setTextViewText(R.id.h, row); setTextColor(R.id.h, p.i("sub"))
        }
        val e = row as Ev; val now = System.currentTimeMillis(); val h24 = p.i("h24") == 1
        val live = e.start <= now && e.end > now && !e.allDay
        val range = if (e.allDay) "All day" else "${Repo.time(e.start, h24)} - ${Repo.time(e.end, h24)}"
        val sub = if (live) { val m = (e.end - now) / 60000; "${if (m >= 60) "${m / 60}h " else ""}${m % 60}m remaining  •  $range" } else range
        return RemoteViews(c.packageName, R.layout.item).apply {
            val sz = p.i("size").toFloat()
            setTextViewText(R.id.t, e.title); setTextColor(R.id.t, p.i("title")); setTextViewTextSize(R.id.t, TypedValue.COMPLEX_UNIT_SP, sz)
            setTextViewText(R.id.s, sub); setTextColor(R.id.s, p.i("sub")); setTextViewTextSize(R.id.s, TypedValue.COMPLEX_UNIT_SP, sz * 0.7f)
            setViewVisibility(R.id.bar, if (p.i("bar") == 1) View.VISIBLE else View.GONE)
            setInt(R.id.bar, "setBackgroundColor", e.color)
            val showP = live && p.i("prog") == 1
            setViewVisibility(R.id.p, if (showP) View.VISIBLE else View.GONE)
            if (showP) {
                setProgressBar(R.id.p, 100, ((now - e.start) * 100 / (e.end - e.start)).toInt(), false)
                if (Build.VERSION.SDK_INT >= 31) {
                    setColorStateList(R.id.p, "setProgressTintList", ColorStateList.valueOf(p.i("acc")))
                    setColorStateList(R.id.p, "setProgressBackgroundTintList", ColorStateList.valueOf((p.i("sub") and 0xFFFFFF) or 0x40000000))
                }
            }
            setOnClickFillInIntent(R.id.row, Intent().putExtra("id", e.id).putExtra("b", e.start).putExtra("e", e.end))
        }
    }
    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount() = 2
    override fun getItemId(i: Int) = i.toLong()
    override fun hasStableIds() = true
}

class Open : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val id = intent.getLongExtra("id", 0)
        val x = when {
            intent.getBooleanExtra("new", false) -> Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
            id > 0 -> Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id))
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, intent.getLongExtra("b", 0))
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, intent.getLongExtra("e", 0))
            else -> Intent(Intent.ACTION_VIEW, Uri.parse("content://com.android.calendar/time/" + System.currentTimeMillis()))
        }
        try { startActivity(x) } catch (_: Exception) {}
        finish()
    }
}

class SettingsActivity : Activity() {
    private lateinit var p: Prefs
    private val presets = listOf(0xFF000000, 0xFFFFFFFF, 0xFF212121, 0xFFFF4081, 0xFF7C4DFF, 0xFF2979FF, 0xFF1DE9B6, 0xFF66BB6A, 0xFFFFC107, 0xFFFF6E40, 0xFFF44336, 0xFFBDBDBD).map { it.toInt() }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun save(k: String, v: Int) { p.put(k, v); CalWidget.refresh(this) }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b); p = Prefs(this)
        if (checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.READ_CALENDAR), 1)
        val r = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(24), dp(20), dp(40)) }
        setContentView(ScrollView(this).apply { addView(r) })
        label(r, "Colours")
        color(r, "Background", "bg"); color(r, "Title text", "title"); color(r, "Time text", "sub"); color(r, "Accent (progress bar)", "acc")
        label(r, "Layout")
        seek(r, "Background opacity", "alpha", 0, 100, "%"); seek(r, "Text size", "size", 12, 28, " sp"); seek(r, "Days ahead", "days", 1, 14, " days")
        switch(r, "24-hour time", "h24"); switch(r, "Calendar colour bar", "bar"); switch(r, "Progress bar on current event", "prog")
        label(r, "Tip: choose which calendars show in the Google Calendar app (menu → calendars).")
    }
    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) = CalWidget.refresh(this)

    private fun label(r: LinearLayout, t: String) = r.addView(TextView(this).apply { text = t; textSize = 17f; setTypeface(null, Typeface.BOLD); setPadding(0, dp(22), 0, dp(6)) })

    private fun color(r: LinearLayout, name: String, k: String) {
        r.addView(TextView(this).apply { text = name; setPadding(0, dp(10), 0, dp(4)) })
        val hex = EditText(this).apply { hint = "#RRGGBB"; setSingleLine(); textSize = 14f; setText(String.format("#%06X", p.i(k) and 0xFFFFFF)) }
        val sw = LinearLayout(this)
        presets.forEach { col ->
            sw.addView(View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(col); setStroke(2, Color.GRAY) }
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { setMargins(dp(3), 0, dp(3), 0) }
                setOnClickListener { hex.setText(String.format("#%06X", col and 0xFFFFFF)) }
            })
        }
        hex.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { if (s?.length == 7) try { save(k, Color.parseColor(s.toString())) } catch (_: Exception) {} }
        })
        r.addView(HorizontalScrollView(this).apply { addView(sw) }); r.addView(hex)
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

    private fun switch(r: LinearLayout, name: String, k: String) = r.addView(Switch(this).apply {
        text = name; isChecked = p.i(k) == 1; setPadding(0, dp(12), 0, dp(12))
        setOnCheckedChangeListener { _, on -> save(k, if (on) 1 else 0) }
    })
}
