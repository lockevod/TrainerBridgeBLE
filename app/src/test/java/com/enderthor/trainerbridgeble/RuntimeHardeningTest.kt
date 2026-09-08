package com.enderthor.trainerbridgeble

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * COORDINATOR CONTRACT ONLY. These are pure-JVM tests of the ownership primitives, with production's own
 * call shapes. They deliberately do NOT prove any Android BLE behaviour, and cannot: a really-blocked
 * connectGatt(), a GATT-server callback after close(), writeCharacteristic() blocking Binder while main
 * runs stopEmit(), an accepted advertising start whose stop is dropped and whose callback is lost, the
 * number of registrations actually live in the controller, adapter OFF/ON or a Bluetooth process death,
 * and the real Handler/main/Binder ordering on the device all remain device-validation territory.
 * Adding Robolectric would not move that line — it reproduces none of the above — so it is not used.
 *
 * Three purely LOGICAL gaps remain too, and are not covered here either:
 *  1. `ZycleClient.connect()`'s `stopped` re-check after registering its token — a field read inside a
 *     production method, not a coordinator contract.
 *  2. `MirrorServer`'s advOrphans / lastAdvCallback cross-instance handoff, including the resolved-vs-
 *     unresolved distinction. It lives entirely in an Android-typed class; a permanently-stranded orphan
 *     got through this suite once already, so treat that path as device-validated only.
 *  3. That the production toZycle lambda really dispatches write() OUTSIDE the emit monitor. Only the
 *     ownership rejection is checked below; the call shape is not.
 */
class RuntimeHardeningTest {

    @Test fun trainerWriteCompletesExactlyOnce() {
        val results = mutableListOf<Boolean>()
        val ticket = TrainerWriteTicket(7L) { results += it }
        assertTrue(ticket.complete(true))
        assertFalse(ticket.complete(false))
        assertEquals(listOf(true), results)
    }

    @Test fun targetResistanceUsesSigned16LittleEndian() {
        assertArrayEquals(byteArrayOf(0x04, 0x24, 0x00), encodeTargetResistance(36))
        assertArrayEquals(byteArrayOf(0x04, 0x10, 0x00), encodeTargetResistance(16))
    }

    // ── connect-attempt ownership (ZycleClient.connect / stop) ────────────────────────────────────
    /** An attempt invalidated while connectGatt() blocks must not publish its handle. NOT the `stopped`
     *  re-check between connect()'s first guard and replace() — that gap is closed by a plain field read in
     *  ZycleClient, which no coordinator-level test can reach (see the class KDoc). */
    @Test fun anInvalidatedConnectAttemptCannotPublishItsHandle() {
        val attempts = IdentityOwner<Any>()
        var published: String? = null
        val attempt = Any()

        attempts.replace(attempt)   // connect() registers...
        attempts.clear()            // ...stop() invalidates it while connectGatt blocks
        assertFalse(attempts.clearIfCurrent(attempt) { published = "handle" })
        assertNull(published)
    }

    /** A stale attempt returning after a newer one is live must close only its own handle: clearing the
     *  shared `connecting` latch there let a scan result start a third connection behind the live one. */
    @Test fun aStaleConnectAttemptTouchesNothingBelongingToItsReplacement() {
        val attempts = IdentityOwner<Any>()
        val stale = Any()
        val live = Any()
        var published: Any? = null

        attempts.replace(stale)
        attempts.replace(live)      // stop() + a later start() promoted a new attempt
        assertFalse(attempts.clearIfCurrent(stale) { published = stale })
        assertNull(published)
        // the live attempt still owns the outcome, so IT can still publish
        assertTrue(attempts.clearIfCurrent(live) { published = live })
        assertEquals(live, published)
    }

    // ── emit ownership (BridgeService.toZycle / stopEmit) ─────────────────────────────────────────
    /** The point of the emit token: a write admitted by the OLD mirror must never capture the NEW source. */
    @Test fun aStaleMirrorWriteCannotCaptureTheReplacementSource() {
        val emitOwner = IdentityOwner<Any>()
        val oldToken = Any()
        emitOwner.replace(oldToken)

        var captured: String? = null
        var mutated = false
        assertTrue(emitOwner.runIfCurrent(oldToken) { mutated = true; captured = "old source" })
        assertEquals("old source", captured)
        // stopEmit()'s barrier hands back exactly the token it retired.
        assertEquals(oldToken, emitOwner.clear())
        emitOwner.replace(Any())                // startEmit() with a replacement source
        captured = null; mutated = false
        assertFalse(emitOwner.runIfCurrent(oldToken) { mutated = true; captured = "new source" })
        assertNull(captured)                    // never reached the replacement
        assertFalse(mutated)                    // and left ErgBias/lastControl alone
    }


    @Test fun gattOperationTimeoutResetsClosesAndReconnectsWithoutPumping() {
        val effects = mutableListOf<String>()
        val coordinator = GattSessionCoordinator<Any>(
            resetRuntime = { effects += "reset" },
            disconnect = { effects += "disconnect" },
            close = { effects += "close" },
            reconnect = { effects += "reconnect" },
        )
        val session = Any()
        coordinator.replace(session)

        assertTrue(coordinator.timeoutIfCurrent(session, stillPending = { true }))
        assertEquals(listOf("reset", "disconnect", "close", "reconnect"), effects)
        assertNull(coordinator.current)
    }

    @Test fun staleGattTimeoutCannotRetireReplacementSession() {
        var retirements = 0
        val coordinator = GattSessionCoordinator<Any>(
            resetRuntime = { retirements++ }, disconnect = {}, close = {}, reconnect = {},
        )
        val stale = Any()
        val replacement = Any()
        coordinator.replace(stale)
        coordinator.replace(replacement)

        assertFalse(coordinator.timeoutIfCurrent(stale, stillPending = { true }))
        assertEquals(0, retirements)
        assertTrue(coordinator.runIfCurrent(replacement) {})
    }

    @Test fun advertisingFailureStopsRegistrationBeforeStateAndRetryWhileStaleFailureIsNoOp() {
        val attempts = AdvertisingAttemptCoordinator<Any>()
        val firstCallback = Any()
        val retryCallback = Any()
        val effects = mutableListOf<String>()
        attempts.begin(firstCallback) { effects += "unexpected" }

        assertTrue(attempts.failIfCurrent(
            firstCallback,
            stop = { effects += "stop" },
            markFailed = { effects += "failed" },
            retry = { effects += "retry" },
        ))
        attempts.begin(retryCallback) { effects += "retire-before-begin" }
        assertEquals(listOf("stop", "failed", "retry"), effects)
        assertFalse(attempts.failIfCurrent(
            firstCallback,
            stop = { effects += "stale stop" },
            markFailed = { effects += "stale failure" },
            retry = { effects += "stale retry" },
        ))
        assertEquals(listOf("stop", "failed", "retry"), effects)
    }

    @Test fun sourceTeardownCannotOvertakeAnAdmittedValueMutation() {
        val owner = IdentityOwner<Any>()
        val source = Any()
        owner.replace(source)
        val mutationEntered = CountDownLatch(1)
        val releaseMutation = CountDownLatch(1)
        val teardownEntered = CountDownLatch(1)
        var state = "initial"

        val callback = thread {
            owner.runIfCurrent(source) {
                mutationEntered.countDown()
                releaseMutation.await()
                state = "old value"
            }
        }
        assertTrue(mutationEntered.await(1, TimeUnit.SECONDS))
        val teardown = thread {
            owner.clearIfCurrent(source) {
                teardownEntered.countDown()
                state = "stopped"
            }
        }

        assertFalse(teardownEntered.await(1, TimeUnit.SECONDS))
        releaseMutation.countDown()
        callback.join(1_000)
        teardown.join(1_000)
        assertEquals("stopped", state)
        assertFalse(owner.runIfCurrent(source) { state = "late value" })
        assertEquals("stopped", state)
    }

    @Test fun ergPersistenceInvokesLearnerAndFlushesPendingAfterConvergence() {
        var learnerCalls = 0
        val persistence = ErgBiasPersistence(persistIntervalMs = 60_000L) { _, _ ->
            learnerCalls++
            when (learnerCalls) { 1 -> 7; 2 -> 8; else -> null }
        }

        assertEquals(7, persistence.onPower(rawWatts = 157, nowMs = 10L))
        assertEquals(null, persistence.onPower(rawWatts = 158, nowMs = 1_000L))
        assertEquals(null, persistence.onPower(rawWatts = 158, nowMs = 30_000L))
        assertEquals(8, persistence.onPower(rawWatts = 158, nowMs = 60_010L))
        assertEquals(4, learnerCalls)
    }
}
