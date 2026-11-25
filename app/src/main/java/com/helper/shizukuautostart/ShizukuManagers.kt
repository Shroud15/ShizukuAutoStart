package com.helper.shizukuautostart

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/**
 * Finds every installed Shizuku-compatible manager, whatever its package name,
 * and the automation entry points it exports.
 *
 * Shizuku forks do not share an application id. Shizuku Next installs as
 * `moe.shizuku.privileged.api`, Shizuku+ as `af.shizuku.plus.api`, and every
 * other fork is free to pick its own. Instead of hard-coding one id we ask the
 * PackageManager which apps implement the standard Shizuku binder-request
 * protocol, then probe each one for the start / watchdog broadcasts it exports.
 * A future fork is picked up the same way as long as it keeps either the
 * protocol or the conventional action names.
 */
object ShizukuManagers {

    private const val TAG = "ShizukuHelper"

    /** The action every Shizuku manager (original, forks, Next) listens to. */
    const val ACTION_REQUEST_BINDER = "rikka.shizuku.intent.action.REQUEST_BINDER"

    /** Original Shizuku's id; also used by Shizuku Next and Shizuku+'s compat hub. */
    const val LEGACY_PACKAGE = "moe.shizuku.privileged.api"

    /**
     * Application ids worth probing directly even when the standard protocol
     * does not expose them to us. `af.shizuku.plus.api` is Shizuku+'s manager.
     */
    val KNOWN_PACKAGES = listOf(
        LEGACY_PACKAGE,
        "af.shizuku.plus.api"
    )

    /**
     * Action suffixes that Shizuku managers have used for "start now". They are
     * probed, not assumed: an action is only used when a real exported receiver
     * answers for it. `WATCHDOG_TOGGLE` is deliberately absent - toggling could
     * switch a watchdog off.
     */
    private val START_SUFFIXES = listOf(
        "START",
        "WATCHDOG_ON",
        "AUTO_START",
        "ACTION_START",
        "START_SERVER",
        "START_SERVICE"
    )

    /** A detected Shizuku manager and the automation it supports. */
    data class Manager(
        val packageName: String,
        val label: String,
        /** Exported broadcast actions that ask this manager to start / resume. */
        val startActions: List<String>
    ) {
        /** True when the manager exposes at least one start entry point. */
        val supportsAutomation: Boolean get() = startActions.isNotEmpty()

        /**
         * True when at least one start signal can be sent without the user
         * pasting an auth token. A bare `.START` action is token gated by the
         * Shizuku Next family, so it only counts when a token is available.
         */
        fun canStartWithoutUser(token: String): Boolean =
            signals(this, token).isNotEmpty()

        /** A human readable name such as "Shizuku Next" or "af.shizuku.plus.api". */
        fun displayName(): String = if (label.isBlank() || label == packageName) packageName else label
    }

    private fun queryFlags(): Int =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            PackageManager.MATCH_DISABLED_COMPONENTS
        } else {
            0
        }

    /** Returns every Shizuku-compatible manager installed on the device. */
    fun discover(context: Context): List<Manager> {
        val pm = context.packageManager
        val packages = LinkedHashSet<String>()

        KNOWN_PACKAGES.forEach { if (isInstalled(pm, it)) packages += it }

        runCatching {
            pm.queryBroadcastReceivers(Intent(ACTION_REQUEST_BINDER), queryFlags())
                .mapNotNullTo(packages) { it.activityInfo?.packageName }
        }.onFailure { Log.w(TAG, "receiver discovery failed", it) }

        runCatching {
            pm.queryIntentActivities(Intent(ACTION_REQUEST_BINDER), queryFlags())
                .mapNotNullTo(packages) { it.activityInfo?.packageName }
        }.onFailure { Log.w(TAG, "activity discovery failed", it) }

        return packages.mapNotNull { inspect(context, it) }
    }

    private fun isInstalled(pm: PackageManager, packageName: String): Boolean =
        runCatching { pm.getPackageInfo(packageName, 0) }.isSuccess

    private fun inspect(context: Context, packageName: String): Manager? {
        val pm = context.packageManager
        val label = runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)

        val actions = discoverStartActions(pm, packageName)
        Log.i(TAG, "manager $packageName ($label): automation=$actions")
        return Manager(packageName, label, actions)
    }

    /** Actions that are worth probing for a given manager. Pure, unit tested. */
    fun candidateActions(packageName: String): List<String> {
        val candidates = LinkedHashSet<String>()
        START_SUFFIXES.forEach { candidates += "$packageName.$it" }
        if (packageName != LEGACY_PACKAGE) {
            START_SUFFIXES.forEach { candidates += "$LEGACY_PACKAGE.$it" }
        }
        return candidates.filter { isStartAction(it) }
    }

    private fun discoverStartActions(
        pm: PackageManager,
        packageName: String
    ): List<String> = candidateActions(packageName).filter { action ->
        runCatching {
            pm.queryBroadcastReceivers(Intent(action).setPackage(packageName), queryFlags())
                .any { info -> info.activityInfo?.exported == true }
        }.getOrDefault(false)
    }

    /**
     * Whether a broadcast action looks like "please start / resume the server".
     * A safety net on top of the probed candidates: stop / off / status actions
     * must never be sent. Kept free of Android types so it can be unit tested.
     */
    fun isStartAction(action: String): Boolean {
        val value = action.trim()
        if (value.isEmpty()) return false
        // System actions are protected broadcasts and never start Shizuku.
        if (value.startsWith("android.")) return false
        val lower = value.lowercase()
        if (lower.contains("stop") || lower.contains("off") || lower.contains("kill") ||
            lower.contains("cancel") || lower.contains("status") || lower.contains("error")
        ) return false
        if (lower.contains("requestbinder") || lower.contains("request_binder")) return false
        if (lower.contains("watchdog")) return true
        return lower.contains("start")
    }

    /**
     * A broadcast to send: the manager package and the start action.
     * Kept free of Android types so the selection can be unit tested.
     */
    data class Signal(val packageName: String, val action: String)

    /** True when [action] may be sent without the user's auth token. */
    fun canSendWithoutToken(action: String): Boolean = !action.endsWith(".START")

    /** True when [action] asks the manager's own watchdog to run a probe. */
    fun isWatchdogAction(action: String): Boolean =
        action.lowercase().contains("watchdog")

    /** True when [manager] exposes its own watchdog entry point. */
    fun supportsWatchdog(manager: Manager): Boolean =
        manager.startActions.any { isWatchdogAction(it) }

    /**
     * The exact start broadcasts that will be sent to [manager]. A bare
     * `.START` action is skipped until a token is saved, because the Shizuku
     * Next family answers it with an "authentication failed" notification.
     * [useWatchdog] is the user's choice: when it is off, the app still
     * restarts the server itself, but it does not poke the manager's watchdog.
     */
    fun signals(
        manager: Manager,
        token: String,
        useWatchdog: Boolean = true
    ): List<Signal> =
        manager.startActions
            .filter { action -> useWatchdog || !isWatchdogAction(action) }
            .filter { action -> canSendWithoutToken(action) || token.isNotEmpty() }
            .map { action -> Signal(manager.packageName, action) }

    /**
     * Builds the broadcast that asks [manager] to start. Returns null when the
     * action needs an auth token that the user has not provided yet, so we do
     * not trigger the manager's "authentication failed" notification.
     */
    fun startIntent(manager: Manager, action: String, token: String): Intent? {
        if (!canSendWithoutToken(action) && token.isEmpty()) return null
        val intent = Intent(action)
            .setPackage(manager.packageName)
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        if (token.isNotEmpty()) {
            intent.putExtra("auth", token)
            intent.putExtra("token", token)
        }
        return intent
    }

    /**
     * Sends every start signal that the manager understands. Returns the number
     * of start actions that were sent. Safe for managers that export none of
     * them (nothing is delivered).
     */
    fun trigger(
        context: Context,
        manager: Manager,
        token: String,
        useWatchdog: Boolean = true
    ): Int {
        var sent = 0
        signals(manager, token, useWatchdog).forEach { signal ->
            val intent = startIntent(manager, signal.action, token) ?: return@forEach
            runCatching { context.sendBroadcast(intent) }
                .onSuccess { sent++ }
                .onFailure { Log.w(TAG, "cannot send $signal to ${manager.packageName}", it) }
        }
        return sent
    }

    /**
     * Sends only the watchdog nudge ("wake the watcher now"). Returns true when
     * at least one watchdog broadcast was delivered.
     */
    fun wakeWatchdog(context: Context, manager: Manager, token: String): Boolean {
        var sent = false
        manager.startActions
            .filter { isWatchdogAction(it) }
            .forEach { action ->
                val intent = startIntent(manager, action, token) ?: return@forEach
                runCatching { context.sendBroadcast(intent) }
                    .onSuccess { sent = true }
                    .onFailure { Log.w(TAG, "cannot send $action", it) }
            }
        Log.i(TAG, "watchdog of ${manager.packageName}: " + if (sent) "woken" else "not sent")
        return sent
    }
}
