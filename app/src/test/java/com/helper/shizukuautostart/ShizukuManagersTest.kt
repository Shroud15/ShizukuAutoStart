package com.helper.shizukuautostart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fork discovery is only as good as the action classifier: it decides which
 * exported broadcasts are safe to send to a Shizuku manager. Sending a stop or
 * an "off" action would make Shizuku worse, so those must never match.
 */
class ShizukuManagersTest {

    @Test
    fun `candidates cover the shizuku next actions`() {
        val candidates = ShizukuManagers.candidateActions("moe.shizuku.privileged.api")
        assertTrue(candidates.contains("moe.shizuku.privileged.api.START"))
        assertTrue(candidates.contains("moe.shizuku.privileged.api.WATCHDOG_ON"))
    }

    @Test
    fun `candidates include the legacy actions for renamed forks`() {
        val candidates = ShizukuManagers.candidateActions("af.shizuku.plus.api")
        assertTrue(candidates.contains("af.shizuku.plus.api.START"))
        assertTrue(candidates.contains("moe.shizuku.privileged.api.START"))
        assertTrue(candidates.contains("moe.shizuku.privileged.api.WATCHDOG_ON"))
    }

    @Test
    fun `dangerous actions are never candidates`() {
        ShizukuManagers.candidateActions("com.example.newfork").forEach { action ->
            val lower = action.lowercase()
            assertFalse(lower.contains(".stop"))
            assertFalse(lower.contains("watchdog_off"))
            assertFalse(lower.contains("watchdog_toggle"))
            assertFalse(lower.contains("kill"))
            assertFalse(lower.contains("cancel"))
        }
    }

    @Test
    fun `start and watchdog actions are accepted`() {
        assertTrue(ShizukuManagers.isStartAction("moe.shizuku.privileged.api.START"))
        assertTrue(ShizukuManagers.isStartAction("moe.shizuku.privileged.api.WATCHDOG_ON"))
        assertTrue(ShizukuManagers.isStartAction("af.shizuku.plus.api.AUTO_START"))
        assertTrue(ShizukuManagers.isStartAction("com.example.newfork.ACTION_START"))
    }

    @Test
    fun `stop and disable actions are rejected`() {
        assertFalse(ShizukuManagers.isStartAction("moe.shizuku.privileged.api.STOP"))
        assertFalse(ShizukuManagers.isStartAction("moe.shizuku.privileged.api.WATCHDOG_OFF"))
        assertFalse(ShizukuManagers.isStartAction("moe.shizuku.privileged.api.WATCHDOG_STATUS"))
        assertFalse(ShizukuManagers.isStartAction("moe.shizuku.privileged.api.CANCEL_START"))
        assertFalse(ShizukuManagers.isStartAction("moe.shizuku.privileged.api.KILL_SERVER"))
    }

    @Test
    fun `binder requests and system broadcasts are ignored`() {
        assertFalse(ShizukuManagers.isStartAction(ShizukuManagers.ACTION_REQUEST_BINDER))
        assertFalse(ShizukuManagers.isStartAction("android.intent.action.BOOT_COMPLETED"))
        assertFalse(ShizukuManagers.isStartAction("android.intent.action.MY_PACKAGE_REPLACED"))
        assertFalse(ShizukuManagers.isStartAction(""))
        assertFalse(ShizukuManagers.isStartAction("   "))
    }

    @Test
    fun `signals skip a bare START without a token but keep watchdog actions`() {
        val next = ShizukuManagers.Manager(
            "moe.shizuku.privileged.api",
            "Shizuku Next",
            listOf("moe.shizuku.privileged.api.START", "moe.shizuku.privileged.api.WATCHDOG_ON")
        )
        val withoutToken = ShizukuManagers.signals(next, "")
        assertTrue(withoutToken.any { it.action == "moe.shizuku.privileged.api.WATCHDOG_ON" })
        assertFalse(withoutToken.any { it.action == "moe.shizuku.privileged.api.START" })

        val withToken = ShizukuManagers.signals(next, "secret")
        assertTrue(withToken.any { it.action == "moe.shizuku.privileged.api.START" })
        assertTrue(withToken.all { it.packageName == "moe.shizuku.privileged.api" })
    }

    @Test
    fun `a renamed fork gets its own start action without a token`() {
        val fork = ShizukuManagers.Manager(
            "com.example.shizukufork",
            "Fake Shizuku Fork",
            listOf("com.example.shizukufork.AUTO_START")
        )
        val signals = ShizukuManagers.signals(fork, "")
        assertTrue(signals.any { it.action == "com.example.shizukufork.AUTO_START" })
        assertTrue(signals.all { it.packageName == "com.example.shizukufork" })
    }

    @Test
    fun `token gating only affects a bare START action`() {
        val tokenFree = ShizukuManagers.Manager(
            "moe.shizuku.privileged.api",
            "Shizuku Next",
            listOf("moe.shizuku.privileged.api.START", "moe.shizuku.privileged.api.WATCHDOG_ON")
        )
        assertTrue(tokenFree.canStartWithoutUser(""))

        val tokenOnly = ShizukuManagers.Manager(
            "af.shizuku.plus.api",
            "Shizuku+",
            listOf("af.shizuku.plus.api.START")
        )
        assertFalse(tokenOnly.canStartWithoutUser(""))
        assertTrue(tokenOnly.canStartWithoutUser("secret"))

        val noAutomation = ShizukuManagers.Manager("moe.shizuku.privileged.api", "Shizuku", emptyList())
        assertFalse(noAutomation.supportsAutomation)
        assertFalse(noAutomation.canStartWithoutUser("secret"))
    }

    @Test
    fun `watchdog support is detected from the probed actions`() {
        val next = ShizukuManagers.Manager(
            "moe.shizuku.privileged.api",
            "Shizuku Next",
            listOf("moe.shizuku.privileged.api.START", "moe.shizuku.privileged.api.WATCHDOG_ON")
        )
        assertTrue(ShizukuManagers.supportsWatchdog(next))
        assertTrue(ShizukuManagers.isWatchdogAction("moe.shizuku.privileged.api.WATCHDOG_ON"))
        assertFalse(ShizukuManagers.isWatchdogAction("moe.shizuku.privileged.api.START"))

        val plain = ShizukuManagers.Manager(
            "com.example.fork",
            "Fork",
            listOf("com.example.fork.AUTO_START")
        )
        assertFalse(ShizukuManagers.supportsWatchdog(plain))
    }

    @Test
    fun `turning the watcher off keeps the app doing the restart itself`() {
        val next = ShizukuManagers.Manager(
            "moe.shizuku.privileged.api",
            "Shizuku Next",
            listOf(
                "moe.shizuku.privileged.api.START",
                "moe.shizuku.privileged.api.WATCHDOG_ON",
                "moe.shizuku.privileged.api.AUTO_START"
            )
        )
        val withWatcher = ShizukuManagers.signals(next, "secret", useWatchdog = true)
        assertTrue(withWatcher.any { it.action.endsWith(".WATCHDOG_ON") })

        val withoutWatcher = ShizukuManagers.signals(next, "secret", useWatchdog = false)
        assertFalse(withoutWatcher.any { it.action.endsWith(".WATCHDOG_ON") })
        // The app still asks the manager to start on its own.
        assertTrue(withoutWatcher.any { it.action.endsWith(".START") })
        assertTrue(withoutWatcher.any { it.action.endsWith(".AUTO_START") })
    }
}
