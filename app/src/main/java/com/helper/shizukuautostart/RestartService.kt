package com.helper.shizukuautostart

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider
import kotlin.math.min

/**
 * Watches the unlock events and keeps asking every installed Shizuku manager
 * to start until the Shizuku server really answers on its binder.
 *
 * It does not modify the Shizuku app. It only uses the entry points the
 * installed manager already exports:
 *  - the standard binder request every Shizuku build listens to
 *    ([ShizukuManagers.ACTION_REQUEST_BINDER]),
 *  - the start / watchdog broadcasts a fork exports (discovered at runtime,
 *    so original Shizuku, Shizuku Next, Shizuku+ and future forks all work),
 *  - a binder request through this app's own Shizuku provider.
 */
class RestartService : Service() {

    companion object {
        private const val TAG = "ShizukuHelper"
        const val ACTION_START = "com.helper.shizukuautostart.action.START"
        const val ACTION_ATTEMPT = "com.helper.shizukuautostart.action.ATTEMPT"
        private const val CHANNEL_ID = "helper"
        private const val CHANNEL_MANAGER_ID = "manager"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_OPEN_ID = 1002

        /** 10s, then slower, capped at 30s. Never gives up while unlocked. */
        private val RETRY_DELAYS_MS = longArrayOf(10_000L, 15_000L, 20_000L, 30_000L)

        @Volatile var isRunning = false
        @Volatile var lastStatus = "Starting..."
        @Volatile var attempts = 0
        @Volatile var nextAttemptAt = 0L

        /** Every Shizuku-compatible manager found on the last scan. */
        @Volatile var managers: List<ShizukuManagers.Manager> = emptyList()

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, RestartService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RestartService::class.java))
        }

        /** The app to open when the user has to start Shizuku by hand. */
        fun primaryManager(): ShizukuManagers.Manager? =
            managers.firstOrNull { it.supportsAutomation } ?: managers.firstOrNull()

        /** True when an installed manager exposes its own watchdog. */
        fun watchdogSupported(): Boolean =
            managers.any { ShizukuManagers.supportsWatchdog(it) }
    }

    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private var enabled = true
    private var fallbackNotified = false

    private val retryRunnable = Runnable { attempt("retry") }
    private val checkRunnable = Runnable {
        if (isShizukuRunning()) onShizukuRunning() else scheduleNext()
    }

    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_USER_PRESENT -> onUnlock("user-present")
                Intent.ACTION_SCREEN_ON -> if (isUnlockedNow()) onUnlock("screen-on")
                Intent.ACTION_SCREEN_OFF -> pause("Screen off - paused until unlock")
            }
        }
    }

    private val shizukuStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getIntExtra("status", -1)
            Log.i(TAG, "SHIZUKU_CHANGED status=$status")
            if (status == 1) {
                onShizukuRunning()
            } else if (status == 0 && enabled && isUnlockedNow()) {
                attempts = 0
                scheduleNext(2_000L, "Shizuku stopped - restarting")
            }
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener { onShizukuRunning() }
    private val binderDead = Shizuku.OnBinderDeadListener {
        Log.i(TAG, "Shizuku binder died")
        if (enabled && isUnlockedNow()) {
            attempts = 0
            scheduleNext(1_000L, "Shizuku stopped - restarting")
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        prefs = getSharedPreferences("helper", Context.MODE_PRIVATE)
        enabled = prefs.getBoolean("enabled", true)

        createChannels()
        registerReceivers()
        refreshManagers()
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(binderReceived, handler)
            Shizuku.addBinderDeadListener(binderDead, handler)
        }.onFailure { Log.w(TAG, "cannot listen to Shizuku binder", it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(buildNotification())

        enabled = prefs.getBoolean("enabled", true)

        when (intent?.action) {
            ACTION_ATTEMPT -> attempt("manual")
            else -> if (enabled) attempt("service-start") else lastStatus = "Helper disabled"
        }
        updateNotification()
        return START_STICKY
    }

    private fun registerReceivers() {
        val lockFilter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        val stateFilter = IntentFilter("moe.shizuku.privileged.api.SHIZUKU_CHANGED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(lockReceiver, lockFilter, Context.RECEIVER_EXPORTED)
            registerReceiver(shizukuStateReceiver, stateFilter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(lockReceiver, lockFilter)
            @Suppress("DEPRECATION")
            registerReceiver(shizukuStateReceiver, stateFilter)
        }
    }

    /** Re-scans installed apps, so installing / switching a fork is picked up. */
    private fun refreshManagers() {
        managers = ShizukuManagers.discover(this)
        Log.i(TAG, "managers: " + managers.joinToString { it.packageName })
    }

    private fun attempt(reason: String) {
        handler.removeCallbacks(retryRunnable)
        handler.removeCallbacks(checkRunnable)
        nextAttemptAt = 0L

        if (!enabled) {
            lastStatus = "Helper disabled"
            updateNotification()
            return
        }
        if (isShizukuRunning()) {
            onShizukuRunning()
            return
        }
        if (!isUnlockedNow()) {
            lastStatus = "Waiting for unlock"
            updateNotification()
            return
        }

        refreshManagers()
        attempts++
        val names = managers.map { it.displayName() }
        lastStatus = if (names.isEmpty()) {
            "Attempt $attempts: no Shizuku app found ($reason)"
        } else {
            "Attempt $attempts: asking ${names.joinToString()} to start ($reason)"
        }
        updateNotification()

        Thread {
            try {
                val token = prefs.getString("token", "").orEmpty().trim()
                // The user chooses whether the manager's own watchdog is also
                // woken, or whether this app does the whole restart job alone.
                val useWatchdog = prefs.getBoolean("watchdog", true)
                if (managers.isEmpty()) {
                    Log.w(TAG, "no Shizuku manager installed")
                }
                managers.forEach { manager ->
                    // Ask the manager to start / resume (fork-specific, discovered).
                    val sent = ShizukuManagers.trigger(this, manager, token, useWatchdog)
                    // Standard request every Shizuku build understands.
                    sendBinderRequest(manager.packageName)
                    Log.i(TAG, "asked ${manager.packageName}: $sent start signal(s)")
                }
                // The manager also starts the server when another app asks for its
                // binder ("start on boot" must be on).
                runCatching { ShizukuProvider.requestBinderForNonProviderProcess(this) }
                    .onFailure { Log.w(TAG, "binder request failed", it) }
            } catch (t: Throwable) {
                Log.w(TAG, "attempt $attempts failed", t)
            }
            if (managers.isNotEmpty() && managers.none { it.supportsAutomation }) {
                postOpenManagerNotification()
            }
            handler.postDelayed(checkRunnable, 3_000L)
        }.start()
    }

    private fun sendBinderRequest(packageName: String) {
        runCatching {
            sendBroadcast(
                Intent(ShizukuManagers.ACTION_REQUEST_BINDER)
                    .setPackage(packageName)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            )
        }.onFailure { Log.w(TAG, "binder request to $packageName failed", it) }
    }

    /**
     * Some managers (the ones without an automation receiver) cannot be started
     * from another app. Rather than pretending, offer a one-tap way to open
     * them, and keep the request loop running in the background.
     */
    private fun postOpenManagerNotification() {
        if (fallbackNotified) return
        val manager = primaryManager() ?: return
        val launch = packageManager.getLaunchIntentForPackage(manager.packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            this,
            2,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_MANAGER_ID)
            .setContentTitle("Shizuku is stopped")
            .setContentText("Tap to open ${manager.displayName()} and start it once")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_OPEN_ID, notification)
        }
        fallbackNotified = true
    }

    private fun scheduleNext(delayMs: Long = retryDelay(), why: String = "Not running yet") {
        handler.removeCallbacks(retryRunnable)
        val delay = delayMs
        nextAttemptAt = System.currentTimeMillis() + delay
        lastStatus = "$why - retrying in ${delay / 1000}s"
        updateNotification()
        handler.postDelayed(retryRunnable, delay)
        Log.i(TAG, "next attempt in ${delay}ms")
    }

    private fun retryDelay(): Long =
        RETRY_DELAYS_MS[min(maxOf(attempts - 1, 0), RETRY_DELAYS_MS.size - 1)]

    private fun onUnlock(reason: String) {
        Log.i(TAG, "unlocked ($reason)")
        if (!enabled) return
        attempts = 0
        fallbackNotified = false
        handler.removeCallbacks(retryRunnable)
        handler.removeCallbacks(checkRunnable)
        nextAttemptAt = 0L
        refreshManagers()
        handler.postDelayed({ attempt("unlock:$reason") }, 500L)
    }

    private fun pause(reason: String) {
        handler.removeCallbacks(retryRunnable)
        handler.removeCallbacks(checkRunnable)
        nextAttemptAt = 0L
        lastStatus = reason
        updateNotification()
    }

    private fun onShizukuRunning() {
        handler.removeCallbacks(retryRunnable)
        handler.removeCallbacks(checkRunnable)
        nextAttemptAt = 0L
        attempts = 0
        fallbackNotified = false
        lastStatus = "Shizuku is running"
        updateNotification()
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(NOTIFICATION_OPEN_ID)
        }
        Log.i(TAG, "Shizuku is running")
    }

    private fun isShizukuRunning(): Boolean =
        runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    private fun isUnlockedNow(): Boolean {
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        if (!power.isInteractive) return false
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            ?: return true
        return !keyguard.isKeyguardLocked
    }

    private fun createChannels() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Shizuku auto start",
                NotificationManager.IMPORTANCE_LOW
            )
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MANAGER_ID,
                "Manual start needed",
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
    }

    private fun buildNotification(): Notification {
        val content = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Shizuku Auto Start")
            .setContentText(lastStatus)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(content)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        isRunning = false
        runCatching {
            Shizuku.removeBinderReceivedListener(binderReceived)
            Shizuku.removeBinderDeadListener(binderDead)
        }
        runCatching { unregisterReceiver(lockReceiver) }
        runCatching { unregisterReceiver(shizukuStateReceiver) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
