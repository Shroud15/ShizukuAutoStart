package com.helper.shizukuautostart

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.MotionEvent
import android.view.TouchDelegate
import android.view.View
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.helper.shizukuautostart.databinding.ActivityMainBinding
import rikka.shizuku.Shizuku
import rikka.sui.Sui
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var binding: ActivityMainBinding

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    private var lastRunning: Boolean? = null
    private var statusColor = 0
    private var batteryVisible = true

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = getSharedPreferences("helper", Context.MODE_PRIVATE)
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupHeader()
        setupAutomation()
        setupToken()
        setupActions()
        // The lamp stays the size of the old dot, but keeps a comfortable
        // touch target around it.
        expandStatusDotTouchTarget()

        binding.tokenField.setText(prefs.getString("token", "").orEmpty())

        lastRunning = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        applyStatusAppearance(lastRunning == true, animate = false)

        batteryVisible = !isBatteryExempt()
        binding.batteryButton.visibility = if (batteryVisible) View.VISIBLE else View.GONE

        addExpressivePress(
            binding.themeButton,
            binding.helpButton,
            binding.wakeButton,
            binding.saveTokenButton,
            binding.startButton,
            binding.openButton,
            binding.batteryButton
        )
        playEntrance()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        }

        if (prefs.getBoolean("enabled", true)) {
            runCatching { RestartService.start(this) }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!RestartService.isRunning) {
            RestartService.managers = ShizukuManagers.discover(this)
        }
        refresh()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    // ---------------------------------------------------------------------
    // Setup
    // ---------------------------------------------------------------------

    private fun setupHeader() {
        binding.themeButton.setIconResource(
            if (isDarkTheme()) R.drawable.ic_light_mode else R.drawable.ic_dark_mode
        )
        binding.themeButton.setOnClickListener {
            binding.themeButton.animate()
                .rotationBy(180f)
                .setDuration(420L)
                .setInterpolator(OvershootInterpolator(1.3f))
                .start()

            val next = !isDarkTheme()
            prefs.edit().putBoolean("dark", next).apply()
            // Let the icon start its turn before the activity recreates.
            handler.postDelayed({
                AppCompatDelegate.setDefaultNightMode(
                    if (next) AppCompatDelegate.MODE_NIGHT_YES
                    else AppCompatDelegate.MODE_NIGHT_NO
                )
            }, 180L)
        }
        binding.helpButton.setOnClickListener { showHelp() }
    }

    private fun setupAutomation() {
        binding.enableSwitch.isChecked = prefs.getBoolean("enabled", true)
        binding.enableSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("enabled", checked).apply()
            if (checked) {
                runCatching { RestartService.start(this) }
                snackbar(getString(R.string.helper_started))
            } else {
                RestartService.stop(this)
                snackbar(getString(R.string.helper_stopped))
            }
            animateSection(binding.automationCard)
            refresh()
        }

        binding.watchdogSwitch.isChecked = prefs.getBoolean("watchdog", true)
        binding.watchdogSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("watchdog", checked).apply()
            animateSection(binding.automationCard)
            refresh()
        }
    }

    private fun setupToken() {
        // The caret belongs to the field only while the user is editing it.
        binding.tokenField.isCursorVisible = false
        binding.tokenField.setOnFocusChangeListener { _, hasFocus ->
            binding.tokenField.isCursorVisible = hasFocus
            if (hasFocus) {
                // Touching the field is the only thing that brings the normal
                // label back after a save; the confirmation stays put until then.
                binding.saveTokenButton.setText(R.string.save_token)
                showKeyboard()
            } else {
                hideKeyboard()
            }
        }

        binding.saveTokenButton.setOnClickListener {
            // Drop focus and the caret the moment Save is pressed, so the
            // saved state is shown without a blinking cursor or keyboard.
            binding.tokenField.clearFocus()
            binding.tokenField.isCursorVisible = false
            hideKeyboard()

            prefs.edit()
                .putString("token", binding.tokenField.text?.toString().orEmpty().trim())
                .apply()
            snackbar(getString(R.string.token_saved))
            showSavedState()
            animateSection(binding.tokenCard)
            refresh()
        }
    }

    /**
     * Turns the save button into a "Saved" confirmation. It stays that way until
     * the token field is touched again, so the user always sees whether the
     * current token has been stored.
     */
    private fun showSavedState() {
        binding.saveTokenButton.setText(R.string.saved)
        springScale(binding.saveTokenButton, 1.04f)
        handler.postDelayed({
            springScale(binding.saveTokenButton, 1f)
        }, 140L)
    }

    private fun setupActions() {
        binding.startButton.setOnClickListener {
            prefs.edit().putBoolean("enabled", true).apply()
            binding.enableSwitch.isChecked = true
            runCatching { RestartService.start(this) }
            snackbar(getString(R.string.helper_started))
            animateSection(binding.actionGroup)
            refresh()
        }

        binding.openButton.setOnClickListener {
            val manager = RestartService.primaryManager()
                ?: ShizukuManagers.discover(this).firstOrNull()
            val pkg = manager?.packageName ?: ShizukuManagers.KNOWN_PACKAGES.first()
            val launch = packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                startActivity(launch)
            } else {
                snackbar(getString(R.string.no_manager))
            }
        }

        binding.batteryButton.setOnClickListener {
            if (isBatteryExempt()) {
                snackbar(getString(R.string.battery_already))
            } else {
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
                val opened = runCatching { startActivity(intent) }.isSuccess
                if (!opened) snackbar(getString(R.string.battery_open))
            }
        }

        binding.wakeButton.setOnClickListener { wakeWatchers() }
        binding.statusDot.setOnClickListener {
            val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
            snackbar(
                getString(if (running) R.string.dot_says_on else R.string.dot_says_off)
            )
        }
    }

    /**
     * The lamp keeps the size of the old status dot, so the touch target is
     * widened to the usual 48dp minimum without changing anything on screen.
     */
    private fun expandStatusDotTouchTarget() {
        val dot = binding.statusDot
        val row = dot.parent as? View ?: return
        row.post {
            val bounds = Rect()
            dot.getHitRect(bounds)
            val half = (48 * resources.displayMetrics.density / 2f).toInt()
            val cx = bounds.centerX()
            val cy = bounds.centerY()
            bounds.set(cx - half, cy - half, cx + half, cy + half)
            row.touchDelegate = TouchDelegate(bounds, dot)
        }
    }

    // ---------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------

    private fun refresh() {
        val enabled = prefs.getBoolean("enabled", true)
        if (binding.enableSwitch.isChecked != enabled) {
            binding.enableSwitch.isChecked = enabled
        }

        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (lastRunning != running) {
            lastRunning = running
            applyStatusAppearance(running, animate = true)
        }
        binding.statusValue.text = getString(
            when {
                running -> R.string.status_running
                !enabled -> R.string.status_disabled
                else -> R.string.status_stopped
            }
        )
        binding.statusProgress.visibility = if (enabled && !running) View.VISIBLE else View.GONE

        val watchdogSupported = RestartService.watchdogSupported()
        val groupVisibility = if (watchdogSupported) View.VISIBLE else View.GONE
        if (binding.watchdogGroup.visibility != groupVisibility) {
            binding.watchdogGroup.visibility = groupVisibility
            if (watchdogSupported) animateSection(binding.watchdogGroup)
        }
        if (binding.watchdogSwitch.isChecked != prefs.getBoolean("watchdog", true)) {
            binding.watchdogSwitch.isChecked = prefs.getBoolean("watchdog", true)
        }

        binding.managersText.text = managersDescription()
        binding.statusDetail.text = detailDescription()
        updateBatteryButton()
    }

    private fun managersDescription(): String {
        val managers = RestartService.managers
        val token = prefs.getString("token", "").orEmpty().trim()
        val sui = runCatching { Sui.isSui() }.getOrDefault(false)
        return when {
            managers.isEmpty() && sui -> getString(R.string.managers_sui)
            managers.isEmpty() -> getString(R.string.managers_none)
            else -> buildString {
                managers.forEach { manager ->
                    val mode = when {
                        !manager.supportsAutomation -> getString(R.string.mode_manual)
                        manager.canStartWithoutUser(token) -> getString(R.string.mode_automatic)
                        else -> getString(R.string.mode_token)
                    }
                    append("• ").append(manager.displayName()).append(" — ").append(mode)
                        .append('\n')
                }
                if (sui) append("• Sui — ").append(getString(R.string.mode_sui))
            }.trimEnd()
        }
    }

    private fun detailDescription(): String = buildString {
        append(RestartService.lastStatus)
        append(" · ")
        append(getString(if (RestartService.isRunning) R.string.service_running else R.string.service_stopped))
        if (RestartService.attempts > 0) {
            append(" · ").append(getString(R.string.attempts, RestartService.attempts))
        }
        val wait = RestartService.nextAttemptAt - System.currentTimeMillis()
        if (wait > 0) {
            append(" · ").append(getString(R.string.next_attempt, TimeUnit.MILLISECONDS.toSeconds(wait)))
        }
    }

    private fun wakeWatchers() {
        val managers = RestartService.managers.filter { ShizukuManagers.supportsWatchdog(it) }
        if (managers.isEmpty()) {
            snackbar(getString(R.string.no_watchdog))
            return
        }
        val token = prefs.getString("token", "").orEmpty().trim()
        binding.wakeButton.isEnabled = false
        snackbar(getString(R.string.waking_watcher))
        Thread {
            var sent = 0
            managers.forEach {
                if (ShizukuManagers.wakeWatchdog(applicationContext, it, token)) sent++
            }
            runOnUiThread {
                binding.wakeButton.isEnabled = true
                snackbar(
                    getString(
                        if (sent > 0) R.string.watcher_woken else R.string.watcher_failed
                    )
                )
                if (sent > 0) {
                    animateSection(binding.automationCard)
                    pulse(binding.statusCard)
                }
            }
        }.start()
    }

    // ---------------------------------------------------------------------
    // Battery restriction button
    // ---------------------------------------------------------------------

    private fun isBatteryExempt(): Boolean {
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return runCatching { power.isIgnoringBatteryOptimizations(packageName) }.getOrDefault(false)
    }

    private fun updateBatteryButton() {
        val exempt = isBatteryExempt()
        when {
            exempt && batteryVisible -> {
                batteryVisible = false
                binding.batteryButton.animate()
                    .alpha(0f)
                    .scaleX(0.8f)
                    .scaleY(0.8f)
                    .setDuration(240L)
                    .withEndAction { binding.batteryButton.visibility = View.GONE }
                    .start()
            }
            !exempt && !batteryVisible -> {
                batteryVisible = true
                binding.batteryButton.apply {
                    visibility = View.VISIBLE
                    alpha = 0f
                    scaleX = 0.8f
                    scaleY = 0.8f
                }.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(380L)
                    .setInterpolator(OvershootInterpolator(1.2f))
                    .start()
            }
        }
    }

    // ---------------------------------------------------------------------
    // Expressive motion
    // ---------------------------------------------------------------------

    private fun playEntrance() {
        val items = listOf(
            binding.header,
            binding.statusCard,
            binding.managersCard,
            binding.automationCard,
            binding.tokenCard,
            binding.actionGroup
        )
        items.forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = dp(28).toFloat()
            view.scaleX = 0.9f
            view.scaleY = 0.9f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(60L + index * 55L)
                .setDuration(480L)
                .setInterpolator(OvershootInterpolator(1.15f))
                .start()
        }
    }

    private fun animateSection(view: View) {
        SpringAnimation(view, DynamicAnimation.SCALE_X, 1f).apply {
            setStartValue(0.97f)
            spring = SpringForce(1f).apply {
                stiffness = SpringForce.STIFFNESS_LOW
                dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
            }
            start()
        }
        SpringAnimation(view, DynamicAnimation.SCALE_Y, 1f).apply {
            setStartValue(0.97f)
            spring = SpringForce(1f).apply {
                stiffness = SpringForce.STIFFNESS_LOW
                dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
            }
            start()
        }
    }

    private fun pulse(view: View) {
        SpringAnimation(view, DynamicAnimation.SCALE_X, 1f).apply {
            setStartValue(0.95f)
            spring = SpringForce(1f).apply {
                stiffness = SpringForce.STIFFNESS_MEDIUM
                dampingRatio = SpringForce.DAMPING_RATIO_LOW_BOUNCY
            }
            start()
        }
        SpringAnimation(view, DynamicAnimation.SCALE_Y, 1f).apply {
            setStartValue(0.95f)
            spring = SpringForce(1f).apply {
                stiffness = SpringForce.STIFFNESS_MEDIUM
                dampingRatio = SpringForce.DAMPING_RATIO_LOW_BOUNCY
            }
            start()
        }
    }

    private fun addExpressivePress(vararg views: View) {
        views.forEach { view ->
            view.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> springScale(v, 0.94f)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> springScale(v, 1f)
                }
                false
            }
        }
    }

    private fun springScale(view: View, target: Float) {
        val stiffness = if (target < 1f) SpringForce.STIFFNESS_HIGH else SpringForce.STIFFNESS_LOW
        val damping = if (target < 1f) SpringForce.DAMPING_RATIO_NO_BOUNCY else SpringForce.DAMPING_RATIO_LOW_BOUNCY
        listOf(DynamicAnimation.SCALE_X, DynamicAnimation.SCALE_Y).forEach { property ->
            SpringAnimation(view, property, target).apply {
                spring = SpringForce(target).apply {
                    this.stiffness = stiffness
                    this.dampingRatio = damping
                }
                start()
            }
        }
    }

    private fun applyStatusAppearance(running: Boolean, animate: Boolean) {
        val background = themeColor(
            if (running) com.google.android.material.R.attr.colorPrimaryContainer
            else com.google.android.material.R.attr.colorErrorContainer
        )
        val onContainer = themeColor(
            if (running) com.google.android.material.R.attr.colorOnPrimaryContainer
            else com.google.android.material.R.attr.colorOnErrorContainer
        )
        val previous = statusColor
        statusColor = background
        if (animate && previous != 0 && previous != background) {
            ValueAnimator.ofObject(ArgbEvaluator(), previous, background).apply {
                duration = 320L
                addUpdateListener { binding.statusCard.setCardBackgroundColor(it.animatedValue as Int) }
                start()
            }
        } else {
            binding.statusCard.setCardBackgroundColor(background)
        }
        binding.statusValue.setTextColor(onContainer)
        binding.statusDetail.setTextColor(onContainer)
        binding.statusDot.setRunning(running, animate)
        if (animate) pulse(binding.statusCard)
    }

    private fun themeColor(attr: Int): Int = MaterialColors.getColor(binding.statusCard, attr)

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun isDarkTheme(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun showHelp() {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.how_to_use_title)
            .setMessage(R.string.how_to_use_body)
            .setPositiveButton(R.string.ok, null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.decorView?.let { decor ->
                decor.alpha = 0f
                decor.scaleX = 0.88f
                decor.scaleY = 0.88f
                decor.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(340L)
                    .setInterpolator(OvershootInterpolator(1.15f))
                    .start()
            }
        }
        dialog.show()
    }

    private fun snackbar(message: String) {
        Snackbar.make(binding.coordinator, message, Snackbar.LENGTH_SHORT).show()
    }

    private fun showKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(binding.tokenField, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        val token = (currentFocus ?: binding.tokenField).windowToken ?: return
        imm?.hideSoftInputFromWindow(token, 0)
        currentFocus?.clearFocus()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
