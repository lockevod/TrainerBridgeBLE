package com.enderthor.trainerbridgeble

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    private fun admitted(c: FtmsControlCoordinator.Admission?) =
        (c as FtmsControlCoordinator.Admission.Admitted).procedure

    private fun rejected(result: Int, client: FtmsControlCoordinator.Client?) =
        FtmsControlCoordinator.Admission.Rejected(result, client)

    private val erg = byteArrayOf(0x05, 0xF0.toByte(), 0x00)     // Set Target Power 240 W
    private val res = byteArrayOf(0x04, 0x24, 0x00)              // Set Target Resistance 36

    @Test fun localProcedureBlocksExternalAndDrainsWithoutClientNotification() {
        val coordinator = FtmsControlCoordinator()
        val a = coordinator.connected("A")

        assertNotNull(coordinator.admitLocal(0x04, res))
        assertEquals(rejected(FtmsControlCoordinator.OPERATION_FAILED, a), coordinator.admit("A", 0x00, null))
        assertNull(coordinator.response(0x05, FtmsControlCoordinator.SUCCESS))
        assertEquals(rejected(FtmsControlCoordinator.OPERATION_FAILED, a), coordinator.admit("A", 0x00, null))
        assertNull(coordinator.response(0x04, FtmsControlCoordinator.SUCCESS)?.client)
        assertNotNull(admitted(coordinator.admit("A", 0x00, null)))
        coordinator.response(0x00, FtmsControlCoordinator.SUCCESS)
        assertNull(coordinator.admitLocal(0x04, res))
    }

    /** The C1 fix, and the bug the first attempt at it introduced: a LOCAL procedure has no client, so a
     *  `Client?` return could not distinguish "timed out" from "not the pending one". Skipping recovery on
     *  that null left the session quarantined with nothing able to lift it for the rest of the ride. */
    @Test fun aTimedOutLocalProcedureStillReportsItsTerminationSoRecoveryRuns() {
        val coordinator = FtmsControlCoordinator()
        val local = coordinator.admitLocal(0x04, res)!!
        assertNull(local.client)

        val terminated = coordinator.timedOut(local)
        assertNotNull(terminated)                       // matched, even with no client to notify
        assertNull(terminated!!.client)
        assertEquals(local, terminated.procedure)
    }

    @Test fun anUnansweredProcedureIsReleasedByItsDeadlineAndQuarantinesTheLink() {
        val coordinator = FtmsControlCoordinator()
        coordinator.connected("A")
        coordinator.admit("A", 0x00, null)
        coordinator.response(0x00, FtmsControlCoordinator.SUCCESS)          // A owns control
        val stuck = admitted(coordinator.admit("A", 0x04, res))             // trainer never answers

        assertEquals(FtmsControlCoordinator.OPERATION_FAILED,
            (coordinator.admit("A", 0x04, res) as FtmsControlCoordinator.Admission.Rejected).result)
        assertEquals("A", coordinator.timedOut(stuck)?.client?.address)
        // quarantined until the trainer link is recycled — not silently reopened
        assertEquals(FtmsControlCoordinator.OPERATION_FAILED,
            (coordinator.admit("A", 0x04, res) as FtmsControlCoordinator.Admission.Rejected).result)
        coordinator.trainerReady()
        assertNotNull(admitted(coordinator.admit("A", 0x04, res)))
    }

    @Test fun anExpiredDeadlineCannotTerminateTheProcedureThatReplacedIt() {
        val coordinator = FtmsControlCoordinator()
        coordinator.connected("A")
        coordinator.admit("A", 0x00, null)
        coordinator.response(0x00, FtmsControlCoordinator.SUCCESS)
        val first = admitted(coordinator.admit("A", 0x04, res))
        coordinator.response(0x04, FtmsControlCoordinator.SUCCESS)
        val second = admitted(coordinator.admit("A", 0x04, res))

        assertNull(coordinator.timedOut(first))         // stale: must not quarantine or cancel
        assertNull(coordinator.transportFailed(first))  // ...and must not disarm the newer deadline
        assertEquals("A", coordinator.transportFailed(second)?.client?.address)
    }

    /** The payload must ride WITH the procedure. As a side slot, one procedure's response committed
     *  another's target — teaching ERG bias a number the machine was never holding. */
    @Test fun aResponseCommitsTheBytesOfTheProcedureItTerminated() {
        val coordinator = FtmsControlCoordinator()
        coordinator.connected("A")
        coordinator.admit("A", 0x00, null)
        coordinator.response(0x00, FtmsControlCoordinator.SUCCESS)

        val ergProcedure = admitted(coordinator.admit("A", 0x05, erg))
        assertArrayEquals(erg, coordinator.transportFailed(ergProcedure)?.bytes)   // failed: bytes came back
        // ...and the slot is empty afterwards, so a later local success cannot commit them
        val local = coordinator.admitLocal(0x04, res)
        assertNull(local)                                        // A still owns control
        coordinator.trainerDropped()
        val afterDrop = coordinator.admitLocal(0x04, res)!!
        assertArrayEquals(res, coordinator.response(0x04, FtmsControlCoordinator.SUCCESS)?.bytes)
        assertEquals(0x04, afterDrop.opcode)
    }

    /** The local button carries 0x04, which is ErgBias's signal to retire an armed ERG target. Routing it
     *  through the mirror without a payload silently stopped that signal from ever arriving. */
    @Test fun aLocalProcedureCarriesItsOwnBytesToTheCommitPoint() {
        val coordinator = FtmsControlCoordinator()
        coordinator.admitLocal(0x04, res)
        val terminated = coordinator.response(0x04, FtmsControlCoordinator.SUCCESS)
        assertNotNull(terminated)
        assertArrayEquals(res, terminated!!.bytes)
        assertNull(terminated.client)
    }

    /** The C2 fix. The identity lookup and the admission are one step, so a disconnect cannot land between
     *  them and let a client that is already gone be promoted to owner — locking out its own reconnect. */
    @Test fun admissionAfterDisconnectIsRefusedRatherThanPromotingAGhost() {
        val coordinator = FtmsControlCoordinator()
        val first = coordinator.connected("A")
        coordinator.disconnected("A")

        assertNull(coordinator.admit("A", 0x00, null))     // fail closed: no live identity for that address
        val second = coordinator.connected("A")
        assertTrue(first != second)                        // reconnect gets its own generation
        assertEquals(second, admitted(coordinator.admit("A", 0x00, null)).client)
        assertEquals(second, coordinator.response(0x00, FtmsControlCoordinator.SUCCESS)?.client)
    }

    @Test fun disconnectReportsWhenPendingProcedureWasLost() {
        val coordinator = FtmsControlCoordinator()
        coordinator.connected("A")
        val b = coordinator.connected("B")
        val lost = admitted(coordinator.admit("A", 0x00, null))

        assertEquals(lost, coordinator.disconnected("A")?.procedure)
        assertEquals(rejected(FtmsControlCoordinator.OPERATION_FAILED, b), coordinator.admit("B", 0x00, null))
        coordinator.trainerReady()
        assertNotNull(admitted(coordinator.admit("B", 0x00, null)))
    }

    @Test fun disconnectWithoutAPendingProcedureRemovesTheIdentityAndReportsNoLoss() {
        val coordinator = FtmsControlCoordinator()
        coordinator.connected("A")
        assertNull(coordinator.disconnected("A"))
        assertNull(coordinator.identity("A"))              // the key really is gone, not merely unreported
        assertNull(coordinator.disconnected("A"))          // idempotent: no second recycle
    }

    @Test fun firstSuccessfulRequestControlOwnsFtms() {
        val coordinator = FtmsControlCoordinator()
        val a = coordinator.connected("A")
        assertEquals(a, admitted(coordinator.admit("A", 0x00, null)).client)
        assertEquals(a, coordinator.response(0x00, FtmsControlCoordinator.SUCCESS)?.client)
        assertNotNull(admitted(coordinator.admit("A", 0x05, erg)))
    }

    @Test fun secondClientCannotControlOrStealOwnership() {
        val coordinator = FtmsControlCoordinator()
        coordinator.connected("A"); val b = coordinator.connected("B")
        coordinator.admit("A", 0x00, null)
        coordinator.response(0x00, FtmsControlCoordinator.SUCCESS)

        assertEquals(rejected(FtmsControlCoordinator.CONTROL_NOT_PERMITTED, b), coordinator.admit("B", 0x05, erg))
        assertEquals(rejected(FtmsControlCoordinator.CONTROL_NOT_PERMITTED, b), coordinator.admit("B", 0x00, null))
    }

    /** A terminal indication that never reached its origin leaves that controller waiting forever;
     *  dropping the claim is what lets anyone else — including its own reconnect — arbitrate again. */
    @Test fun releasingAnUndeliverableOwnerReopensArbitration() {
        val coordinator = FtmsControlCoordinator()
        val a = coordinator.connected("A")
        val b = coordinator.connected("B")
        coordinator.admit("A", 0x00, null)
        coordinator.response(0x00, FtmsControlCoordinator.SUCCESS)

        assertEquals(rejected(FtmsControlCoordinator.CONTROL_NOT_PERMITTED, b), coordinator.admit("B", 0x00, null))
        assertTrue(coordinator.releaseOwner(a))
        assertFalse(coordinator.releaseOwner(a))     // only the current owner, only once
        assertNotNull(admitted(coordinator.admit("B", 0x00, null)))
    }

    @Test fun onlyOneProcedureCanBePending() {
        val coordinator = FtmsControlCoordinator()
        val a = coordinator.connected("A")
        val first = admitted(coordinator.admit("A", 0x00, null))
        assertEquals(rejected(FtmsControlCoordinator.OPERATION_FAILED, a), coordinator.admit("A", 0x00, null))
        coordinator.transportFailed(first)
        assertNotNull(admitted(coordinator.admit("A", 0x00, null)))
    }

    @Test fun responseRoutesOnlyToMatchingOrigin() {
        val coordinator = FtmsControlCoordinator()
        val a = coordinator.connected("A")
        coordinator.admit("A", 0x00, null)
        assertNull(coordinator.response(0x05, FtmsControlCoordinator.SUCCESS))
        assertEquals(a, coordinator.response(0x00, FtmsControlCoordinator.SUCCESS)?.client)
        assertNotNull(admitted(coordinator.admit("A", 0x05, erg)))
    }

    @Test fun failedRequestControlDoesNotAcquireOwnership() {
        val coordinator = FtmsControlCoordinator()
        coordinator.connected("A"); val b = coordinator.connected("B")
        coordinator.admit("A", 0x00, null)
        assertEquals("A", coordinator.response(0x00, FtmsControlCoordinator.OPERATION_FAILED)?.client?.address)
        assertEquals(b, admitted(coordinator.admit("B", 0x00, null)).client)
    }

    @Test fun ownerDisconnectAndTrainerDropClearOwnership() {
        val coordinator = FtmsControlCoordinator()
        coordinator.connected("A")
        coordinator.admit("A", 0x00, null)
        coordinator.response(0x00, FtmsControlCoordinator.SUCCESS)

        coordinator.admit("A", 0x05, erg)
        coordinator.disconnected("A")
        coordinator.trainerDropped()
        coordinator.trainerReady()
        val b = coordinator.connected("B")
        coordinator.admit("B", 0x00, null)
        coordinator.response(0x00, FtmsControlCoordinator.SUCCESS)
        coordinator.admit("B", 0x05, erg)
        assertEquals(b, coordinator.trainerDropped())
        coordinator.connected("A")
        assertNotNull(admitted(coordinator.admit("A", 0x00, null)))
    }

    /** A local GATT server rebuild closes the server, and Android raises no disconnect callbacks for the
     *  connections it kills. Keeping ownership across that stranded ERG on a dead generation. */
    @Test fun teardownDropsOwnershipAndEveryConnectionIdentity() {
        val coordinator = FtmsControlCoordinator()
        val a = coordinator.connected("A")
        coordinator.admit("A", 0x00, null)
        coordinator.response(0x00, FtmsControlCoordinator.SUCCESS)

        coordinator.clear()
        assertNull(coordinator.identity("A"))
        assertNull(coordinator.admit("A", 0x00, null))     // the old handle is gone, not merely stale
        val reconnected = coordinator.connected("A")
        assertTrue(a != reconnected)
        assertEquals(reconnected, admitted(coordinator.admit("A", 0x00, null)).client)
        assertEquals(reconnected, coordinator.response(0x00, FtmsControlCoordinator.SUCCESS)?.client)
    }

    /** The guard that stops a late arm from stripping a healthy procedure of its only deadline. The local
     *  button path crosses two main-loop hops, so its arm routinely lands after a newer procedure's. */
    @Test fun onlyTheLiveProcedureIsStillPending() {
        val coordinator = FtmsControlCoordinator()
        coordinator.connected("A")
        val local = coordinator.admitLocal(0x04, res)!!
        assertTrue(coordinator.isPending(local))

        coordinator.response(0x04, FtmsControlCoordinator.SUCCESS)
        assertFalse(coordinator.isPending(local))          // ended: a stale arm must bail here

        val next = admitted(coordinator.admit("A", 0x00, null))
        assertTrue(coordinator.isPending(next))
        assertFalse(coordinator.isPending(local))          // ...and must not adopt the newer one either
    }

    /** A rejection issued while the client did NOT own control must carry no authority to release
     *  ownership that same client legitimately acquires afterwards. */
    @Test fun ownershipAuthorityIsScopedToTheClientThatActuallyOwns() {
        val coordinator = FtmsControlCoordinator()
        val a = coordinator.connected("A")
        val b = coordinator.connected("B")
        coordinator.admit("A", 0x00, null)
        coordinator.response(0x00, FtmsControlCoordinator.SUCCESS)

        assertTrue(coordinator.owns(a))
        assertFalse(coordinator.owns(b))                   // B's rejection may not release anything
        coordinator.disconnected("A")
        coordinator.admit("B", 0x00, null)
        coordinator.response(0x00, FtmsControlCoordinator.SUCCESS)
        assertTrue(coordinator.owns(b))                    // B now owns it for real
        assertFalse(coordinator.owns(a))
    }

    /** The single highest-value line in the coordinator: an app that disconnects must not keep ERG
     *  hostage. Nothing else covers it — trainerDropped() masks it in the broader ownership test. */
    @Test fun aDepartedOwnerDoesNotKeepControlHostage() {
        val coordinator = FtmsControlCoordinator()
        val a = coordinator.connected("A")
        val b = coordinator.connected("B")
        coordinator.admit("A", 0x00, null)
        coordinator.response(0x00, FtmsControlCoordinator.SUCCESS)
        assertTrue(coordinator.owns(a))

        assertNull(coordinator.disconnected("A"))          // nothing was pending, so no recycle
        assertFalse(coordinator.owns(a))                   // ...but ownership is gone with the connection
        assertEquals(b, admitted(coordinator.admit("B", 0x00, null)).client)
        assertEquals(b, coordinator.response(0x00, FtmsControlCoordinator.SUCCESS)?.client)
    }

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
        // the high byte and the sign: 36/16 alone pass for byteArrayOf(0x04, v.toByte(), 0)
        assertArrayEquals(byteArrayOf(0x04, 0x2C, 0x01), encodeTargetResistance(300))
        assertArrayEquals(byteArrayOf(0x04, 0xFF.toByte(), 0xFF.toByte()), encodeTargetResistance(-1))
        assertArrayEquals(byteArrayOf(0x04, 0x00, 0x80.toByte()), encodeTargetResistance(Int.MIN_VALUE))
        assertArrayEquals(byteArrayOf(0x04, 0xFF.toByte(), 0x7F), encodeTargetResistance(Int.MAX_VALUE))
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
    @Test fun controllableFtmsRequiresFeatureControlPointAndOnePowerStream() {
        val readiness = FtmsBootstrapReadiness(controllable = true)

        assertFalse(readiness.ready)
        assertEquals(
            listOf(
                "FTMS Feature read",
                "FTMS Control Point subscription",
                "Indoor Bike or Cycling Power subscription",
            ),
            readiness.missingRequirements,
        )
        readiness.featureRead = true
        assertFalse(readiness.ready)
        assertEquals(
            listOf("FTMS Control Point subscription", "Indoor Bike or Cycling Power subscription"),
            readiness.missingRequirements,
        )
        readiness.controlPointSubscribed = true
        assertFalse(readiness.ready)
        assertEquals(listOf("Indoor Bike or Cycling Power subscription"), readiness.missingRequirements)
        readiness.indoorBikeSubscribed = true
        assertTrue(readiness.ready)
        assertTrue(readiness.missingRequirements.isEmpty())
    }

    @Test fun eitherIndoorBikeOrCyclingPowerSubscriptionSatisfiesPower() {
        val indoorBike = FtmsBootstrapReadiness(controllable = true).apply {
            featureRead = true
            controlPointSubscribed = true
            indoorBikeSubscribed = true
        }
        val cyclingPower = FtmsBootstrapReadiness(controllable = true).apply {
            featureRead = true
            controlPointSubscribed = true
            cyclingPowerSubscribed = true
        }

        assertTrue(indoorBike.ready)
        assertTrue(cyclingPower.ready)
    }

    @Test fun readOnlyProfileDoesNotRequireControlPoint() {
        val readiness = FtmsBootstrapReadiness(controllable = false).apply {
            indoorBikeSubscribed = true
        }

        assertTrue(readiness.ready)
    }

    @Test fun staleServiceAddCallbackCannotCompleteReplacementAttempt() {
        val attempts = IdentityOwner<Any>()
        val staleService = Any()
        val replacementService = Any()
        var completed: Any? = null
        attempts.replace(staleService)
        attempts.replace(replacementService)

        assertFalse(attempts.clearIfCurrent(staleService) { completed = staleService })
        assertNull(completed)
        assertTrue(attempts.clearIfCurrent(replacementService) { completed = replacementService })
        assertEquals(replacementService, completed)
    }

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
