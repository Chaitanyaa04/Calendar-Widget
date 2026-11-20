package app.calwidget

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// Checks GitHub for a newer build, downloads it with Android's DownloadManager and hands it to the system installer.
object Updater {
    private const val API = "https://api.github.com/repos/Chaitanyaa04/Calendar-Widget/releases/latest"
    private const val PREFIX = "CalWidget-update"

    @Suppress("DEPRECATION")
    fun installed(c: Context): Long = c.packageManager.getPackageInfo(c.packageName, 0).versionCode.toLong()
    fun latest(c: Context): Long = Prefs(c).l("upv")
    fun available(c: Context): Boolean = latest(c) > installed(c) && Prefs(c).s("upurl").isNotEmpty()
    fun due(c: Context): Boolean = System.currentTimeMillis() - Prefs(c).l("upchk") > 6 * 3600_000L

    // result: 1 = newer build exists, 0 = up to date, -1 = couldn't check
    fun check(c: Context, done: (Int) -> Unit) {
        Thread {
            var r = -1
            try {
                val u = URL(API).openConnection() as HttpURLConnection
                u.connectTimeout = 8000; u.readTimeout = 8000
                val j = JSONObject(u.inputStream.bufferedReader().readText())
                val tag = j.getString("tag_name").removePrefix("v").toLong()
                val p = Prefs(c)
                p.putL("upv", tag); p.putS("upurl", j.getJSONArray("assets").getJSONObject(0).getString("browser_download_url")); p.putL("upchk", System.currentTimeMillis())
                r = if (tag > installed(c)) 1 else 0
            } catch (_: Exception) {}
            Handler(Looper.getMainLooper()).post { done(r) }
        }.start()
    }

    private fun dir(c: Context) = c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
    private fun wipe(c: Context) { dir(c)?.listFiles()?.filter { it.name.startsWith(PREFIX) }?.forEach { it.delete() } }

    // Removes the downloaded installer once that version is installed.
    fun cleanup(c: Context) { if (installed(c) >= Prefs(c).l("dlver")) wipe(c) }

    fun download(c: Context): Long {
        wipe(c)
        val p = Prefs(c); p.putL("dlver", p.l("upv"))
        val req = DownloadManager.Request(Uri.parse(p.s("upurl"))).setTitle("Calendar Widget update").setDescription("Build ${p.l("upv")}")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(c, Environment.DIRECTORY_DOWNLOADS, "$PREFIX-${p.l("upv")}.apk")
        return (c.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
    }

    fun install(c: Context, id: Long) {
        val uri = (c.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).getUriForDownloadedFile(id) ?: return
        c.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
