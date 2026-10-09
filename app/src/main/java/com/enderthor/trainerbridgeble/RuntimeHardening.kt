package com.enderthor.trainerbridgeble

internal class TrainerWriteTicket(
    val sequence: Long,
    private val onComplete: (Boolean) -> Unit,
) {
    private val completed = java.util.concurrent.atomic.AtomicBoolean(false)
    fun complete(success: Boolean): Boolean {
        if (!completed.compareAndSet(false, true)) return false
        onComplete(success)
        return true
    }
}

internal fun encodeTargetResistance(target: Int): ByteArray {
    val value = target.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
    return byteArrayOf(0x04, (value and 0xFF).toByte(), ((value ushr 8) and 0xFF).toByte())
}

internal class FtmsBootstrapReadiness(
    val controllable: Boolean,
) {
    var featureRead = false
    var controlPointSubscribed = false
    var indoorBikeSubscribed = false
    var cyclingPowerSubscribed = false

    val ready: Boolean get() =
        (!controllable || featureRead && controlPointSubscribed) &&
            (indoorBikeSubscribed || cyclingPowerSubscribed)

    val missingRequirements: List<String> get() = buildList {
        if (controllable && !featureRead) add("FTMS Feature read")
        if (controllable && !controlPointSubscribed) add("FTMS Control Point subscription")
        if (!indoorBikeSubscribed && !cyclingPowerSubscribed) add("Indoor Bike or Cycling Power subscription")
    }
}

/** Single owner of "who may drive the trainer", and the single FTMS procedure in flight.
 *
 *  ONE procedure is outstanding at a time — that is what makes an opcode-only FTMS response attributable,
 *  and everything downstream (ERG bias learning, the quarantine, the trainer recycle) depends on it. But a
 *  real controller does not wait: Bestcycling writes Set Target Power in bursts as tight as 62 ms, never
 *  subscribes to 0x2AD9, and so cannot know a procedure is outstanding. Refusing those writes silently
 *  discarded them — measured on a real ride, it asked for 78 W, was refused, and pedalled on against the
 *  last accepted target of 0 W.
 *
 *  So the owner's extra commands are HELD, not refused and not sent concurrently: the newest replaces any
 *  held one (an ERG target is worthless the moment a newer one arrives) and it is dispatched as soon as the
 *  in-flight procedure ends. Nothing is lost, and every response still belongs to exactly one request.
 *
 *  Ownership has two stages. A client that never subscribes cannot wait for its Request Control response
 *  before bursting, so it becomes a CLAIMANT immediately — enough to be served, not enough to be trusted.
 *  Only the trainer's SUCCESS promotes it to owner; a refusal, a transport failure, a timeout, a disconnect
 *  or a trainer drop revokes it. Without that split, a client the trainer refused could hold control — and
 *  lock out the rider's own buttons — for the rest of the session. */
internal class FtmsControlCoordinator {
    data class Client(val address: String, val generation: Long)
    /** `id` makes every admitted procedure unique: two same-opcode procedures are NOT interchangeable. */
    data class Procedure(val client: Client?, val opcode: Int, val id: Long)
    /** A procedure that just ended, with the exact bytes it carried. Non-null even for a local procedure
     *  (whose `client` is legitimately null) so callers can tell "matched" from "already gone". */
    class Terminated(val procedure: Procedure, val bytes: ByteArray?) {
        val client: Client? get() = procedure.client
    }
    /** The owner's newest command, waiting for the in-flight one to finish. Latest wins. */
    class Held(val client: Client, val opcode: Int, val bytes: ByteArray)

    sealed interface Admission {
        data class Admitted(val procedure: Procedure) : Admission
        /** Accepted but not sent yet; it replaced any previously held command. Never dropped. */
        data object Held : Admission
        data class Rejected(val result: Int, val client: Client?) : Admission
    }

    private var owner: Client? = null        // confirmed by the trainer
    private var claimant: Client? = null     // asked, not yet confirmed
    private var pending: Procedure? = null
    private var pendingBytes: ByteArray? = null
    /** Held commands keyed by opcode: latest-wins WITHIN an opcode (a superseded ERG target is worthless)
     *  but never ACROSS one — a Start/Resume or Reset is not replaceable by a later target, and the real
     *  app interleaves them: Request Control followed 90 ms later by Start/Resume. Insertion order is
     *  preserved, so they drain in the order the app sent them. */
    private val held = LinkedHashMap<Int, Held>()
    private var invalidSession = false
    private var generations = 0L
    private var procedures = 0L
    private val keys = HashMap<String, Client>()

    /** Whoever may drive right now: the confirmed owner, or the claimant still awaiting its answer. */
    private val controller: Client? get() = owner ?: claimant

    @Synchronized fun connected(address: String): Client =
        Client(address, ++generations).also { keys[address] = it }

    @Synchronized fun identity(address: String): Client? = keys[address]

    @Synchronized fun owns(client: Client): Boolean = owner == client

    @Synchronized fun isPending(procedure: Procedure): Boolean = pending == procedure

    /** Key removal, ownership loss and any held command as ONE transition. */
    @Synchronized fun disconnected(address: String): Terminated? {
        val client = keys.remove(address) ?: return null
        if (owner == client) owner = null
        if (claimant == client) claimant = null
        held.values.removeAll { it.client == client }
        val lost = pending?.takeIf { it.client == client } ?: return null
        val terminated = Terminated(lost, pendingBytes)
        pending = null; pendingBytes = null; invalidSession = true
        return terminated
    }

    /** Admit by ADDRESS so the identity lookup and the decision cannot straddle a disconnect.
     *  Returns null only when the address has no live connection — callers must fail closed, never mint an
     *  identity from a write, or a departing client can be resurrected and take ownership. */
    @Synchronized fun admit(address: String, opcode: Int, bytes: ByteArray?): Admission? {
        val client = keys[address] ?: return null
        return admitLocked(client, opcode, bytes)
    }

    private fun admitLocked(client: Client, opcode: Int, bytes: ByteArray?, fromQueue: Boolean = false): Admission? {
        if (invalidSession) return Admission.Rejected(OPERATION_FAILED, client)
        val holder = controller
        if (opcode == REQUEST_CONTROL) {
            if (holder != null && holder != client) return Admission.Rejected(CONTROL_NOT_PERMITTED, client)
        } else if (holder != client) return Admission.Rejected(CONTROL_NOT_PERMITTED, client)

        val inFlight = pending
        // A fresh write must not overtake the queue: response() and the drain after it are two steps, and a
        // target landing between them ran first and was then rolled back by the older one still held.
        if (inFlight != null || (!fromQueue && held.isNotEmpty())) {
            // The rider's own button is a DIFFERENT controller; an app does not queue behind it.
            if (inFlight != null && inFlight.client == null) return Admission.Rejected(OPERATION_FAILED, client)
            // The owner's own newer command of the SAME opcode supersedes the held one and waits its turn.
            if (bytes == null) return Admission.Rejected(OPERATION_FAILED, client)
            // Two would-be controllers racing for an open slot: the first one queued keeps it. Replacing it
            // would leave the first never answered.
            if (held[opcode]?.client.let { it != null && it != client })
                return Admission.Rejected(CONTROL_NOT_PERMITTED, client)
            // Re-inserted at the END: a replaced key keeps its old slot in a LinkedHashMap, so Start, Stop,
            // Start drained as Start, Stop and left the trainer stopped while the app thought it was running.
            held.remove(opcode)
            held[opcode] = Held(client, opcode, bytes)
            return Admission.Held
        }
        if (opcode == REQUEST_CONTROL) claimant = client
        return Procedure(client, opcode, ++procedures).let {
            pending = it; pendingBytes = bytes; Admission.Admitted(it)
        }
    }

    @Synchronized fun admitLocal(opcode: Int, bytes: ByteArray?): Procedure? {
        if (invalidSession || controller != null || pending != null) return null
        return Procedure(null, opcode, ++procedures).also { pending = it; pendingBytes = bytes }
    }

    /** Take the next held command AND admit it in ONE transition. Splitting the two let a newer command
     *  be admitted in between, after which the older one was pushed back into the queue and eventually
     *  sent AFTER the newer — latest-wins violated and the resistance rolling backwards.
     *  @return the command and its admission, or null if nothing is held or something else got in first
     *  (in which case that procedure's own termination will drain the queue). */
    @Synchronized fun promoteHeld(): Pair<Held, Admission>? {
        if (pending != null) return null
        val key = held.keys.firstOrNull() ?: return null
        val next = held.remove(key) ?: return null
        return next to (admitLocked(next.client, next.opcode, next.bytes, fromQueue = true)
            ?: Admission.Rejected(OPERATION_FAILED, next.client))
    }

    /** Everything still waiting, so a caller tearing the session down can answer each one. */
    @Synchronized fun drainHeld(): List<Held> = held.values.toList().also { held.clear() }

    /** Terminates exactly the procedure named — never a newer one that reused the opcode. */
    @Synchronized fun transportFailed(procedure: Procedure): Terminated? {
        if (pending != procedure) return null
        val terminated = Terminated(procedure, pendingBytes)
        pending = null; pendingBytes = null
        if (procedure.opcode == REQUEST_CONTROL && claimant == procedure.client) claimant = null
        return terminated
    }

    /** No FTMS response arrived in time. A late opcode-only response can no longer be correlated to a
     *  request, so the trainer session is quarantined exactly as it is for a controller that vanished
     *  mid-procedure. Non-null whenever it MATCHED, including a local procedure with no client. */
    @Synchronized fun timedOut(procedure: Procedure): Terminated? {
        if (pending != procedure) return null
        val terminated = Terminated(procedure, pendingBytes)
        pending = null; pendingBytes = null; invalidSession = true
        if (procedure.opcode == REQUEST_CONTROL && claimant == procedure.client) claimant = null
        return terminated
    }

    /** Returns the procedure the response terminated together with the bytes it carried, so the caller
     *  commits the target the trainer actually acknowledged and cancels the right deadline. */
    @Synchronized fun response(opcode: Int, result: Int): Terminated? {
        if (invalidSession) return null
        val procedure = pending?.takeIf { it.opcode == opcode } ?: return null
        val terminated = Terminated(procedure, pendingBytes)
        pending = null; pendingBytes = null
        if (opcode == REQUEST_CONTROL && procedure.client != null && claimant == procedure.client) {
            // Promote on success, revoke on refusal — the claim is only ever as good as the trainer's word.
            if (result == SUCCESS) owner = procedure.client
            claimant = null
        }
        return terminated
    }

    /** The owner could not be told the outcome; drop its claim so the next requester can arbitrate. */
    @Synchronized fun releaseOwner(client: Client): Boolean {
        if (owner != client) return false
        owner = null
        return true
    }

    @Synchronized fun trainerDropped(): Client? = controller.also {
        owner = null; claimant = null; pending = null; pendingBytes = null; held.clear()
    }

    @Synchronized fun trainerReady() { invalidSession = false }

    /** Full teardown, used by BOTH stop() and a local GATT server rebuild. Closing the server invalidates
     *  every ATT handle and delivers no disconnect callbacks, so keeping any of this would strand ownership
     *  on a generation that can never come back. */
    @Synchronized fun clear() {
        owner = null; claimant = null; pending = null; pendingBytes = null; held.clear()
        invalidSession = false; keys.clear()
    }

    companion object {
        const val SUCCESS = 0x01
        const val OPERATION_FAILED = 0x04
        const val CONTROL_NOT_PERMITTED = 0x05
        private const val REQUEST_CONTROL = 0x00
    }
}

internal class IdentityOwner<T : Any> {
    @Volatile private var value: T? = null

    val current: T? get() = value

    @Synchronized fun replace(next: T?): T? = value.also { value = next }

    @Synchronized fun clear(): T? = value.also { value = null }

    @Synchronized fun clearIfCurrent(candidate: T, action: () -> Unit): Boolean {
        if (value !== candidate) return false
        value = null
        action()
        return true
    }

    @Synchronized fun runIfCurrent(candidate: T, action: () -> Unit): Boolean {
        if (value !== candidate) return false
        action()
        return true
    }
}

internal class GattSessionCoordinator<T : Any>(
    private val resetRuntime: () -> Unit,
    private val disconnect: (T) -> Unit,
    private val close: (T) -> Unit,
    private val reconnect: () -> Unit,
) {
    private val owner = IdentityOwner<T>()

    val current: T? get() = owner.current
    fun replace(next: T?) = owner.replace(next)
    fun clear() = owner.clear()
    fun clearIfCurrent(candidate: T, action: () -> Unit) = owner.clearIfCurrent(candidate, action)
    fun runIfCurrent(candidate: T, action: () -> Unit) = owner.runIfCurrent(candidate, action)

    fun retireIfCurrent(candidate: T, onRetiring: () -> Unit = {}): Boolean {
        if (!owner.clearIfCurrent(candidate) { onRetiring(); resetRuntime() }) return false
        disconnect(candidate)
        close(candidate)
        reconnect()
        return true
    }

    /**
     * A timed-out op MUST retire the handle, not just advance the queue. Unsticking in place cannot cancel
     * the operation Android still has, so its late callback is indistinguishable from the callback for the
     * op dispatched in its place: that one completes the wrong op, cancels the wrong watchdog, and pumps a
     * third while the second is still on the controller. Deferring the retirement to an Nth CONSECUTIVE
     * timeout does not work either — the same late callback resets any such counter, so the escalation
     * never fires precisely when the handle is wedged. A reconnect is the cheaper failure.
     */
    fun timeoutIfCurrent(candidate: T, stillPending: () -> Boolean, onRetiring: () -> Unit = {}): Boolean {
        var timedOut = false
        owner.runIfCurrent(candidate) {
            if (stillPending()) timedOut = owner.clearIfCurrent(candidate) { onRetiring(); resetRuntime() }
        }
        if (!timedOut) return false
        disconnect(candidate)
        close(candidate)
        reconnect()
        return true
    }
}

internal class AdvertisingAttemptCoordinator<T : Any> {
    private val owner = IdentityOwner<T>()

    val current: T? get() = owner.current
    fun runIfCurrent(candidate: T, action: () -> Unit) = owner.runIfCurrent(candidate, action)
    fun clearIfCurrent(candidate: T, action: () -> Unit) = owner.clearIfCurrent(candidate, action)
    fun clear() = owner.clear()

    @Synchronized fun begin(next: T, retire: (T) -> Unit) {
        owner.clear()?.let(retire)
        owner.replace(next)
    }

    fun retireIfCurrent(candidate: T, retire: (T) -> Unit, after: () -> Unit): Boolean {
        if (!owner.clearIfCurrent(candidate) {}) return false
        retire(candidate)
        after()
        return true
    }

    fun failIfCurrent(
        candidate: T,
        stop: (T) -> Unit,
        markFailed: () -> Unit,
        retry: () -> Unit,
    ): Boolean = retireIfCurrent(candidate, stop) { markFailed(); retry() }
}

internal class ErgBiasPersistence(
    private val persistIntervalMs: Long,
    private val learner: (rawWatts: Int, nowMs: Long) -> Int?,
) {
    private var pending: Int? = null
    private var lastPersistMs: Long? = null

    fun reset() { pending = null; lastPersistMs = null }

    fun onPower(rawWatts: Int, nowMs: Long): Int? {
        val newWholeWatt = learner(rawWatts, nowMs)
        if (newWholeWatt != null) pending = newWholeWatt
        val value = pending ?: return null
        if (lastPersistMs?.let { nowMs - it < persistIntervalMs } == true) return null
        pending = null
        lastPersistMs = nowMs
        return value
    }

    fun drain(): Int? = pending.also { pending = null }
}
