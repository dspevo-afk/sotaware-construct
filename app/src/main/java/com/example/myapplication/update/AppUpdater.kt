package com.example.myapplication.update

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.example.myapplication.BuildConfig
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.lang.ref.WeakReference
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

/**
 * In-app updates without USB: reads latest.json from c4130 ([BuildConfig.UPDATE_URL]), offers an
 * Update button, downloads the APK, checks its SHA-256 and hands it to Android's installer.
 * Android itself refuses an APK signed with a different key, so only builds signed with the same
 * debug key as the installed app can update it. Published by tools/publish_update.ps1.
 */
object AppUpdater {
    private const val PREFS = "app_update"
    private const val RECHECK_MS = 10 * 60 * 1000L
    private const val SNOOZE_MS = 4 * 60 * 60 * 1000L
    private const val MAX_APK_BYTES = 300L * 1024 * 1024
    const val RESULT_ACTION = "com.sotaware.construct.UPDATE_RESULT"

    private class Info(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val sha256: String,
        val notes: String,
    )

    @Volatile private var checking = false
    @Volatile private var installing = false
    private var lastCheck = 0L
    private var waitingForPermission: Info? = null
    private var offerDialog: AlertDialog? = null
    private var pendingConfirm: Intent? = null
    private var foreground = WeakReference<Activity>(null)

    /** Call from Activity.onResume: checks at most every 10 minutes and resumes interrupted steps. */
    fun onResume(activity: Activity) {
        foreground = WeakReference(activity)
        pendingConfirm?.let { confirm ->
            pendingConfirm = null
            runCatching { activity.startActivity(confirm) }
            return
        }
        waitingForPermission?.let { info ->
            waitingForPermission = null
            if (canInstall(activity)) {
                download(activity, info)
                return
            }
        }
        val now = SystemClock.elapsedRealtime()
        if (lastCheck != 0L && now - lastCheck < RECHECK_MS) return
        check(activity, manual = false)
    }

    fun onPause(activity: Activity) {
        if (foreground.get() === activity) foreground.clear()
    }

    val installedVersion: String get() = BuildConfig.VERSION_NAME

    /** [manual] = the user asked, so also say "up to date" or why the check failed. */
    fun check(activity: Activity, manual: Boolean) {
        if (checking || installing) return
        checking = true
        lastCheck = SystemClock.elapsedRealtime()
        val ref = WeakReference(activity)
        Thread({
            var found: Info? = null
            var problem: String? = null
            var nothingPublished = false
            try {
                found = fetch()
                nothingPublished = found == null
            } catch (e: Exception) {
                problem = e.message ?: e.javaClass.simpleName
            }
            val a = ref.get()
            if (a == null) {
                checking = false
                return@Thread
            }
            a.runOnUiThread {
                checking = false
                if (!alive(a)) return@runOnUiThread
                val info = found
                when {
                    info != null && info.versionCode > BuildConfig.VERSION_CODE ->
                        if (manual || !snoozed(a, info.versionCode)) offer(a, info)
                    !manual -> Unit
                    problem != null -> message(a, "Could not check for updates",
                        "The update server did not answer ($problem). Check that Tailscale is connected.")
                    else -> message(a, "Up to date",
                        "You have the newest version (${BuildConfig.VERSION_NAME})." +
                            if (nothingPublished) " No update has been published yet." else "")
                }
            }
        }, "app-update-check").start()
    }

    private fun alive(a: Activity?) = a != null && !a.isFinishing && !a.isDestroyed

    private fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    private fun snoozed(context: Context, versionCode: Int): Boolean {
        val p = context.getSharedPreferences(PREFS, 0)
        return p.getInt("snooze_code", -1) == versionCode && System.currentTimeMillis() < p.getLong("snooze_until", 0)
    }

    private fun snooze(context: Context, versionCode: Int) {
        context.getSharedPreferences(PREFS, 0).edit()
            .putInt("snooze_code", versionCode)
            .putLong("snooze_until", System.currentTimeMillis() + SNOOZE_MS)
            .apply()
    }

    private fun message(a: Activity, title: String, text: String) {
        AlertDialog.Builder(a).setTitle(title).setMessage(text).setPositiveButton("OK", null).show()
    }

    /** null = nothing published yet. */
    private fun fetch(): Info? {
        val url = URL(BuildConfig.UPDATE_URL)
        val c = url.openConnection() as HttpsURLConnection
        try {
            c.connectTimeout = 8000
            c.readTimeout = 10000
            c.useCaches = false
            c.instanceFollowRedirects = false
            c.setRequestProperty("Cache-Control", "no-cache")
            val code = c.responseCode
            if (code == 404) return null
            if (code != 200) throw IOException("HTTP $code")
            val j = JSONObject(readSmall(c.inputStream))
            val apk = URL(url, j.getString("apk"))
            if (apk.protocol != "https" || !apk.host.equals(url.host, ignoreCase = true) || apk.port != url.port) {
                throw IOException("update file is on another server")
            }
            val sha = j.optString("sha256", "").lowercase(Locale.ROOT)
            if (!sha.matches(Regex("[0-9a-f]{64}"))) throw IOException("update has no checksum")
            val versionCode = j.getInt("versionCode")
            return Info(
                versionCode = versionCode,
                versionName = j.optString("versionName", versionCode.toString()),
                apkUrl = apk.toString(),
                sha256 = sha,
                notes = j.optString("notes", ""),
            )
        } finally {
            c.disconnect()
        }
    }

    private fun readSmall(input: InputStream): String = input.use { stream ->
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = stream.read(buf)
            if (n <= 0) break
            if (out.size() + n > 65536) throw IOException("update info too big")
            out.write(buf, 0, n)
        }
        out.toString(Charsets.UTF_8.name())
    }

    private fun offer(a: Activity, info: Info) {
        if (offerDialog?.isShowing == true) return
        val text = "Version ${info.versionName} is ready (you have ${BuildConfig.VERSION_NAME})." +
            if (info.notes.isEmpty()) "" else "\n\nWhat's new:\n${info.notes}"
        offerDialog = AlertDialog.Builder(a)
            .setTitle("App update available")
            .setMessage(text)
            .setNegativeButton("Later") { _, _ -> snooze(a, info.versionCode) }
            .setPositiveButton("Update") { _, _ -> startUpdate(a, info) }
            .show()
    }

    private fun startUpdate(a: Activity, info: Info) {
        if (canInstall(a)) {
            download(a, info)
            return
        }
        AlertDialog.Builder(a)
            .setTitle("One-time setup")
            .setMessage(
                "Android needs your OK once before this app can install its own updates.\n\n" +
                    "On the next screen turn on \"Allow from this source\", then press Back."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Open setting") { _, _ ->
                waitingForPermission = info
                try {
                    a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName)))
                } catch (e: ActivityNotFoundException) {
                    waitingForPermission = null
                    message(a, "Setting not found",
                        "Open Android Settings > Apps > Special app access > Install unknown apps, and allow this app.")
                }
            }
            .show()
    }

    private fun dp(c: Context, v: Int) = Math.round(v * c.resources.displayMetrics.density)

    private fun download(a: Activity, info: Info) {
        if (installing) return
        installing = true
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(a, 24), dp(a, 12), dp(a, 24), 0)
        }
        val label = TextView(a).apply { text = "Downloading version ${info.versionName}..." }
        val bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
        }
        box.addView(label)
        box.addView(bar)
        val cancelled = AtomicBoolean()
        val dialog = AlertDialog.Builder(a)
            .setTitle("Updating")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> cancelled.set(true) }
            .show()
        val app = a.applicationContext
        Thread({
            var problem: String? = null
            try {
                val apk = fetchApk(app, info, cancelled) { pct ->
                    a.runOnUiThread {
                        bar.isIndeterminate = false
                        bar.progress = pct
                    }
                }
                a.runOnUiThread { label.text = "Handing the update to Android..." }
                install(app, apk)
            } catch (e: Exception) {
                problem = e.message ?: e.javaClass.simpleName
            }
            a.runOnUiThread {
                installing = false
                if (dialog.isShowing) dialog.dismiss()
                val why = problem
                if (why != null && !cancelled.get() && alive(a)) {
                    message(a, "Update failed", "$why\n\nNothing was changed. Try again later.")
                }
            }
        }, "app-update-download").start()
    }

    private fun fetchApk(ctx: Context, info: Info, cancelled: AtomicBoolean, progress: (Int) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("no space for the download")
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "update-${info.versionCode}.apk")
        val c = URL(info.apkUrl).openConnection() as HttpsURLConnection
        try {
            c.connectTimeout = 10000
            c.readTimeout = 30000
            c.useCaches = false
            c.instanceFollowRedirects = false
            val code = c.responseCode
            if (code != 200) throw IOException("download failed (HTTP $code)")
            val total = c.contentLengthLong
            if (total > MAX_APK_BYTES) throw IOException("update file too big")
            val sha = MessageDigest.getInstance("SHA-256")
            c.inputStream.use { input ->
                FileOutputStream(out).use { fos ->
                    val buf = ByteArray(65536)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        if (cancelled.get()) throw IOException("cancelled")
                        done += n
                        if (done > MAX_APK_BYTES) throw IOException("update file too big")
                        sha.update(buf, 0, n)
                        fos.write(buf, 0, n)
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != last) {
                                last = pct
                                progress(pct)
                            }
                        }
                    }
                    fos.fd.sync()
                }
            }
            val hex = sha.digest().joinToString("") { String.format(Locale.ROOT, "%02x", it) }
            if (hex != info.sha256) throw IOException("the download was damaged (checksum mismatch)")
            return out
        } catch (e: Exception) {
            out.delete()
            throw e
        } finally {
            c.disconnect()
        }
    }

    private fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setSize(apk.length())
            // After the first in-app update this app is its own installer, so later updates may skip Android's prompt.
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { output ->
                        input.copyTo(output, 65536)
                        session.fsync(output)
                    }
                }
                val result = Intent(ctx, UpdateInstallReceiver::class.java).setAction(RESULT_ACTION)
                val pending = PendingIntent.getBroadcast(
                    ctx, id, result, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
    }

    /** Android wants the user to confirm: show its screen now, or as soon as the app is opened again. */
    internal fun confirm(context: Context, confirm: Intent) {
        val a = foreground.get()
        if (alive(a)) {
            try {
                a!!.startActivity(confirm)
                return
            } catch (e: RuntimeException) {
                // keep it for the next resume
            }
        }
        pendingConfirm = confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        Toast.makeText(context, context.getString(com.example.myapplication.R.string.update_open_app_to_finish), Toast.LENGTH_LONG).show()
    }
}

/** Result of an in-app update install session (see [AppUpdater]). */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) AppUpdater.confirm(context, confirm)
            }
            PackageInstaller.STATUS_SUCCESS, PackageInstaller.STATUS_FAILURE_ABORTED -> Unit
            else -> {
                Toast.makeText(context, context.getString(com.example.myapplication.R.string.update_failed), Toast.LENGTH_LONG).show()
            }
        }
    }
}
