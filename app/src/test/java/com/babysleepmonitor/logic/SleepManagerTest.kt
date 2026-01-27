package com.babysleepmonitor.logic

import org.junit.Test
import org.junit.Assert.*
import com.babysleepmonitor.data.db.SleepDao
import com.babysleepmonitor.data.db.SleepSessionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job

class SleepManagerTest {

    class TestLogger : ISleepLogger {
        override fun i(tag: String, msg: String) { println("[INFO] $tag: $msg") }
        override fun e(tag: String, msg: String, tr: Throwable?) { println("[ERROR] $tag: $msg") }
    }

    class MockDao : SleepDao {
        override suspend fun insertSession(session: SleepSessionEntity) {}
        override suspend fun getAllSessions(): List<SleepSessionEntity> = emptyList()
    }

    var currentTime = 100000.0 // Start at some time

    @Test
    fun testSleepLogic() {
        val dao = MockDao()
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        val logger = TestLogger()
        
        val timeProvider = { (currentTime * 1000).toLong() }
        
        val manager = SleepManager(dao, scope, logger, timeProvider)
        
        println("=== Test Case 1: Startup ===")
        // 0-10s -> Calibrating
        manager.startSession() // Reset session time to current logic time
        
        var state = manager.update(0.0)
        println("T=0s State: $state")
        assertEquals("Should be CALIBRATING at start", SleepState.CALIBRATING, state)
        
        currentTime += 5.0
        state = manager.update(0.0)
        println("T=5s State: $state")
        assertEquals("Should still be CALIBRATING at 5s", SleepState.CALIBRATING, state)
        
        currentTime += 6.0 // Total 11s from start
        state = manager.update(0.0)
        println("T=11s State: $state")
        assertNotEquals("Should NOT be CALIBRATING after 10s", SleepState.CALIBRATING, state)
        
        println("=== Test Case 2: Deep Sleep ===")
        // Send motion ~500k for 30s -> Should be DEEP_SLEEP
        // Deep range: 100k - 800k. Needs 30s quiet.
        val deepMotion = 500_000.0
        
        // Feed 40s of deep motion
        for (i in 1..40) {
            currentTime += 1.0
            state = manager.update(deepMotion)
        }
        println("T=40s Deep State: $state")
        assertEquals("Should be DEEP_SLEEP", SleepState.DEEP_SLEEP, state)
        
        println("=== Test Case 3: Awake ===")
        // Send motion ~5M for 10s -> Should be AWAKE
        val awakeMotion = 5_000_000.0
        
        currentTime += 1.0
        state = manager.update(awakeMotion)
        println("T=Aggressive Motion Start: $state")
        // Hysteresis (0.5s) means we might not switch immediately if confirmation is needed.
        // Current logic: pending SPASM.
        
        currentTime += 1.0
        state = manager.update(awakeMotion)
        assertEquals("Should be SPASM after confirmation", SleepState.SPASM, state)
        
        // Feed 5s more (Total > 5s high motion -> Awake)
        // We already fed 2s. Need > 5s duration.
        // User says "Awake: Motion > 3M for > 5s".
        // handleTransition checks time in state?
        // No, determineState returns AWAKE if sustained? 
        // My logic: If recentMax > 3M -> SPASM.
        // handleTransition promotes SPASM -> AWAKE if held > 5s.
        
        for (i in 1..10) {
             currentTime += 1.0
             state = manager.update(awakeMotion)
        }
        println("T=After 6s High Motion: $state")
        assertEquals("Sustained high motion should be AWAKE", SleepState.AWAKE, state)
        
        println("=== Test Case 4: No Breathing ===")
        // Send motion 0 for 30s. BPM 0.
        // We are currently AWAKE.
        // Drop motion to 0.
        
        // Feed 0 motion for 40s
        for (i in 1..40) {
            currentTime += 1.0
            state = manager.update(0.0)
            if (i % 10 == 0) println("T=Zero Motion @ ${i}s: $state")
        }
        
        assertEquals("Should be NO_BREATHING", SleepState.NO_BREATHING, state)
    }
}
