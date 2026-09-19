package com.example.robloxkeeper

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

//shoutout claude

class KeeperService : Service() {

    companion object {
        var isRunning = false
        @Volatile var isManuallyPaused = false
        val client = OkHttpClient()
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "keeper_channel"
        private const val TAG = "KeeperService"
        private const val TARGET_PACKAGE = "com.roblox.client"
        private const val FIREFOX_PACKAGE = "org.mozilla.firefox"
        private const val DEEPLINK = "https://www.roblox.com/share?code=c00bc130c108844d800e144db1ed8352&type=Server"
        private const val CHECK_URL = "https://inventories.jailbreakchangelogs.com/bots/connected"
        private const val CHECK_INTERVAL = 10000L
        private const val HEARTBEAT_INTERVAL = 5 * 60 * 1000L // 5 minutes

        const val ACTION_LOG_UPDATE = "com.example.robloxkeeper.ACTION_LOG_UPDATE"
        const val EXTRA_LOG_TEXT = "extra_log_text"
        private const val PREFS_NAME = "keeper_prefs"
        private const val PREFS_KEY_LOGS = "service_logs"
        private const val MAX_LOG_LINES = 120

        fun getSavedLogs(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(PREFS_KEY_LOGS, "") ?: ""
        }

        fun clearSavedLogs(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(PREFS_KEY_LOGS).apply()
            context.sendBroadcast(
                Intent(ACTION_LOG_UPDATE)
                    .setPackage(context.packageName)
                    .putExtra(EXTRA_LOG_TEXT, "")
            )
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastNotForeground = false
    private var botId: String = ""
    private var logcatThread: Thread? = null
    private var lastLoggedRobloxForeground: Boolean? = null
    private var lastLoggedFirefoxForeground: Boolean? = null

    private val timerTask = object : Runnable {
        override fun run() {
            Thread {
                refreshBotId()
                val isRobloxForeground = isForegroundApp(TARGET_PACKAGE)
                val isFirefoxForeground = if (isRobloxForeground) false else isForegroundApp(FIREFOX_PACKAGE)
                handler.post {
                    Log.d(TAG, "Is Roblox in foreground? $isRobloxForeground")

                    if (isRobloxForeground != lastLoggedRobloxForeground || isFirefoxForeground != lastLoggedFirefoxForeground) {
                        logEvent("State: robloxForeground=$isRobloxForeground, firefoxForeground=$isFirefoxForeground")
                        lastLoggedRobloxForeground = isRobloxForeground
                        lastLoggedFirefoxForeground = isFirefoxForeground
                    }

                    if (isRobloxForeground) {
                        lastNotForeground = false
                    } else {
                        if (lastNotForeground) {
                            openDeeplink()
                            lastNotForeground = false
                        } else {
                            lastNotForeground = true
                        }
                    }
                }
            }.start()

            handler.postDelayed(this, CHECK_INTERVAL)
        }
    }

    private val heartbeatTask = object : Runnable {
        override fun run() {
            // Run network call on a background thread
            Thread {
                checkHeartbeat()
            }.start()

            handler.postDelayed(this, HEARTBEAT_INTERVAL)
        }
    }

    // Polls for a Main World hop request written by MainBotCode.luau's
    // Rejoin()/writeMainJoinRequest (BOT_HOP_METHOD == 2 only). Runs
    // independently of foreground detection, since the deep link hot-routes
    // an already-running Roblox client straight into the target instance —
    // no force-stop/relaunch needed.
    private var lastMainJoinRequestRaw: String? = null
    private var lastDeferredJoinRequestRaw: String? = null
    @Volatile private var suppressLegacyRelaunchUntil: Long = 0L
    private val MAIN_HOP_SUPPRESSION_MS = 15000L
    private val MAIN_JOIN_REQUEST_MAX_AGE_S = 60L

    private val mainJoinTask = object : Runnable {
        override fun run() {
            Thread { checkMainJoinRequest() }.start()
            handler.postDelayed(this, CHECK_INTERVAL)
        }
    }

    private fun checkMainJoinRequest() {
        if (botId.isBlank()) return
        try {
            val file = File(
                Environment.getExternalStorageDirectory(),
                "Delta/Workspace/mainJoinRequest$botId.json"
            )
            if (!file.exists()) return

            val raw = file.readText().trim()
            if (raw.isBlank()) return

            if (isRelaunchSuppressed()) {
                // Don't mark it as seen: once un-paused/Firefox closes, this
                // same request should still get a chance to fire (subject to
                // its own staleness check) instead of being silently dropped.
                if (raw != lastDeferredJoinRequestRaw) {
                    logEvent("Main World hop deferred: paused / Firefox is open")
                    lastDeferredJoinRequestRaw = raw
                }
                return
            }
            lastDeferredJoinRequestRaw = null

            if (raw == lastMainJoinRequestRaw) return
            lastMainJoinRequestRaw = raw

            val json = JSONObject(raw)
            val createdAt = json.optLong("createdAt", 0L)
            val ageSeconds = System.currentTimeMillis() / 1000 - createdAt
            if (createdAt <= 0L || ageSeconds > MAIN_JOIN_REQUEST_MAX_AGE_S) {
                // Leftover from before this service was (re)started, or from
                // a session long gone. Firing it would fight the heartbeat/
                // crash recovery over a target that's no longer relevant —
                // just ignore it and let normal recovery handle Roblox instead.
                logEvent("Ignoring stale Main World hop request (${ageSeconds}s old)")
                return
            }

            val placeId = json.optLong("placeId", 606849621L)
            val jobId = if (json.isNull("jobId")) null else json.optString("jobId", "").ifBlank { null }

            val url = if (jobId != null) {
                "roblox://experiences/start?placeId=$placeId&gameInstanceId=$jobId"
            } else {
                "roblox://experiences/start?placeId=$placeId"
            }

            logEvent("Main World hop requested: $url")
            // Give the legacy foreground/heartbeat/crash triggers a window to
            // back off, so they can't fire the static share-link relaunch
            // while this hop is in flight.
            suppressLegacyRelaunchUntil = System.currentTimeMillis() + MAIN_HOP_SUPPRESSION_MS

            val cmd = "am start -W -a android.intent.action.VIEW -d \"$url\" $TARGET_PACKAGE"
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val output = process.inputStream.bufferedReader().readText().trim()
            val errorOutput = process.errorStream.bufferedReader().readText().trim()
            process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)

            logEvent("Main World hop result: $output" + if (errorOutput.isNotBlank()) " | ERR: $errorOutput" else "")
        } catch (e: Exception) {
            Log.e(TAG, "Main join check failed", e)
            logEvent("Main World hop error: ${e.message ?: "unknown"}")
        }
    }

    // Re-reads BotId.txt and updates the cached botId if it changed (e.g. the
    // account on this phone was swapped). Without this, botId would only ever
    // be read once at service startup and silently go stale until Keeper was
    // manually restarted.
    private fun refreshBotId() {
        try {
            val file = File(
                Environment.getExternalStorageDirectory(),
                "Delta/Workspace/BotId.txt"
            )
            val newId = file.readText().trim()
            if (newId.isBlank()) return
            if (newId != botId) {
                val previous = botId
                botId = newId
                logEvent(if (previous.isBlank()) "Bot ID loaded: $newId" else "Bot ID changed: $previous -> $newId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read BotId.txt", e)
            if (botId.isBlank()) {
                logEvent("ERROR: Could not read BotId.txt — ${e.message}")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForeground(NOTIFICATION_ID, createNotification())

        refreshBotId()
        logEvent("Service started, bot ID: $botId")
        handler.post(timerTask)
        handler.post(heartbeatTask)
        handler.post(mainJoinTask)
        startLogcatMonitor()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacks(timerTask)
        handler.removeCallbacks(heartbeatTask)
        handler.removeCallbacks(mainJoinTask)
        logcatThread?.interrupt()
        clearSavedLogs(this)
        Log.d(TAG, "Service stopped and logs cleared")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun killRoblox() {
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "am force-stop $TARGET_PACKAGE"))
            Log.d(TAG, "Force-stopped $TARGET_PACKAGE via root")
            logEvent("Force-stopped $TARGET_PACKAGE via root shell")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to kill Roblox", e)
            logEvent("Failed to kill Roblox: ${e.message ?: "unknown"}")
        }
    }

    private fun checkHeartbeat() {
        if (botId.isBlank()) {
            logEvent("Heartbeat check skipped: no bot ID loaded")
            return
        }

        try {
            val request = Request.Builder()
                .url(CHECK_URL)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    logEvent("Heartbeat check failed: HTTP ${response.code}")
                    return
                }

                val rawJson = response.body!!.string() // save it first
                val json = JSONObject(rawJson)         // then parse from the saved string
                val bots = json.getJSONArray("recent_heartbeats")

                var matchedBot: JSONObject? = null
                for (i in 0 until bots.length()) {
                    val bot = bots.getJSONObject(i)
                    if (bot.getString("id") == botId) {
                        matchedBot = bot
                        break
                    }
                }

                if (matchedBot != null) {
                    val lastHeartbeat = matchedBot.getLong("last_heartbeat")
                    val nowSeconds = System.currentTimeMillis() / 1000
                    val secondsAgo = nowSeconds - lastHeartbeat
                    val minutes = secondsAgo / 60
                    val seconds = secondsAgo % 60
                    val state = matchedBot.getString("client_state")

                    logEvent("Heartbeat: state=$state, last seen ${minutes}m ${seconds}s ago")

                    if (secondsAgo >= 150) {
                        logEvent("Heartbeat stale (${minutes}m ${seconds}s) — relaunching and killing Roblox")
                        handler.post {
                            killRoblox()
                            lastNotForeground = false
                        }
                        handler.postDelayed({ openDeeplink() }, 2000)
                    } else Unit
                } else {
                    logEvent("Heartbeat: bot ID $botId not found — relaunching")
                    handler.post {
                        killRoblox()
                        lastNotForeground = false
                    }
                    handler.postDelayed({ openDeeplink() }, 2000)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Heartbeat check error", e)
            logEvent("Heartbeat check error: ${e.message ?: "unknown"}")
        }
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Keeper Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Roblox Keeper")
            .setContentText("Monitoring Roblox status")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun isForegroundApp(packageName: String): Boolean {
        // Try dumpsys window displays first (Android 10+), fall back to activity activities
        for (cmd in listOf(
            "dumpsys window displays",
            "dumpsys activity activities"
        )) {
            var process: java.lang.Process? = null
            try {
                process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                val output = process.inputStream.bufferedReader().readText()
                process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                val matchLine = output.lineSequence().firstOrNull {
                    it.contains("mCurrentFocus") || it.contains("mFocusedApp") || it.contains("mResumedActivity")
                }
                Log.d(TAG, "$cmd match: $matchLine")
                if (matchLine?.contains(packageName) == true) return true
            } catch (e: Exception) {
                Log.e(TAG, "Foreground check failed ($cmd)", e)
            } finally {
                process?.destroy()
            }
        }
        return false
    }

    // True when Roblox shouldn't be forced to the foreground right now: the
    // user manually paused it (e.g. waiting on a Delta key page), or Firefox
    // is the one currently on screen (the same key-page workflow), so a
    // relaunch would yank focus away from what they're actually doing.
    private fun isRelaunchSuppressed(): Boolean {
        if (isManuallyPaused) return true
        return isForegroundApp(FIREFOX_PACKAGE)
    }

    private fun openDeeplink() {
        if (System.currentTimeMillis() < suppressLegacyRelaunchUntil) {
            logEvent("Skipped legacy relaunch: Main World hop in flight")
            return
        }
        if (isRelaunchSuppressed()) {
            logEvent("Skipped legacy relaunch: paused / Firefox is open")
            return
        }
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "am start -a android.intent.action.VIEW -d \"$DEEPLINK\""))
            Log.d(TAG, "Launched deeplink via am start")
            logEvent("Relaunch triggered: $DEEPLINK")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open deeplink", e)
            logEvent("Relaunch failed: ${e.message ?: "unknown error"}")
        }
    }

    private fun startLogcatMonitor() {
        logcatThread = Thread {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "logcat -s ActivityManager:I"))
                val reader = process.inputStream.bufferedReader()
                var line: String? = null
                while (!Thread.currentThread().isInterrupted && reader.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    if (l.contains(TARGET_PACKAGE) && (l.contains("died") || l.contains("crash"))) {
                        logEvent("Logcat: Roblox crash detected — relaunching")
                        handler.post {
                            openDeeplink()
                            lastNotForeground = false
                        }
                    }
                }
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                Log.e(TAG, "Logcat monitor error", e)
                logEvent("Logcat monitor stopped: ${e.message}")
            }
        }
        logcatThread?.start()
    }

    private fun logEvent(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val formatted = "[$timestamp] $message"

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(PREFS_KEY_LOGS, "") ?: ""
        val combined = if (existing.isBlank()) formatted else "$existing\n$formatted"
        val trimmed = combined
            .lineSequence()
            .filter { it.isNotBlank() }
            .toList()
            .takeLast(MAX_LOG_LINES)
            .joinToString("\n")
        prefs.edit().putString(PREFS_KEY_LOGS, trimmed).apply()

        sendBroadcast(
            Intent(ACTION_LOG_UPDATE)
                .setPackage(packageName)
                .putExtra(EXTRA_LOG_TEXT, formatted)
        )
        Log.d(TAG, formatted)
    }
}
