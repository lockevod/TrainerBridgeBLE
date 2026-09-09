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

/** Single owner of "who may drive the trainer". It also owns the per-connection identities and the
 *  in-flight procedure's payload, because every decision needs them together: looking a client up in one
 *  map and admitting it under a different lock let a disconnect land in between; keeping the command bytes
 *  in a side slot let one procedure's response commit another procedure's target. Identity, admission,
 *  payload and termination are all ONE synchronized transition here. */
internal class FtmsControlCoordinator {
    data class Client(val address: String, val generation: Long)
    /** `id` makes every admitted procedure unique: two same-opcode procedures are NOT interchangeable, so a
     *  late transport failure or an expired deadline for the first cannot terminate the second. */
    data class Procedure(val client: Client?, val opcode: Int, val id: Long)
    /** A procedure that just ended, with the exact bytes it carried. Non-null even for a local procedure
     *  (whose `client` is legitimately null) so callers can tell "matched" from "already gone". */
    /** Deliberately NOT a data class: it carries a ByteArray, whose generated equals/hashCode would be
     *  identity-based and quietly wrong for anyone who later compares or keys on one. */
    class Terminated(val procedure: Procedure, val bytes: ByteArray?) {
        val client: Client? get() = procedure.client
    }

    sealed interface Admission {
        data class Admitted(val procedure: Procedure) : Admission
        data class Rejected(val result: Int, val client: Client?) : Admission
    }

    private var owner: Client? = null
    private var pending: Procedure? = null
    private var pendingBytes: ByteArray? = null
    private var invalidSession = false
    private var generations = 0L
    private var procedures = 0L
    private val keys = HashMap<String, Client>()

    @Synchronized fun connected(address: String): Client =
        Client(address, ++generations).also { keys[address] = it }

    @Synchronized fun identity(address: String): Client? = keys[address]

    /** True only while this exact procedure is the admitted one. An arm that lost a race to the main
     *  looper must ask before replacing the live deadline — otherwise it cancels a healthy procedure's
     *  only timer and installs one for a procedure that already ended. */
    @Synchronized fun isPending(procedure: Procedure): Boolean = pending == procedure

    /** True while this client holds control. A terminal result that was produced while the client was
     *  NOT the owner carries no authority to release ownership it may have acquired since. */
    @Synchronized fun owns(client: Client): Boolean = owner == client

    /** Key removal and ownership loss as ONE transition; returns the procedure that died with it, if any. */
    @Synchronized fun disconnected(address: String): Terminated? {
        val client = keys.remove(address) ?: return null
        if (owner == client) owner = null
        val lost = pending?.takeIf { it.client == client } ?: return null
        val terminated = Terminated(lost, pendingBytes)
        pending = null; pendingBytes = null; invalidSession = true
        return terminated
    }

    /** Admit by ADDRESS so the identity lookup and the decision cannot straddle a disconnect.
     *  Returns null only when the address has no live connection — callers must fail closed, never
     *  mint an identity from a write, or a departing client can be resurrected and take ownership. */
    @Synchronized fun admit(address: String, opcode: Int, bytes: ByteArray?): Admission? {
        val client = keys[address] ?: return null
        if (invalidSession || pending != null) return Admission.Rejected(OPERATION_FAILED, client)
        if (if (opcode == REQUEST_CONTROL) owner != null && owner != client else owner != client)
            return Admission.Rejected(CONTROL_NOT_PERMITTED, client)
        return Procedure(client, opcode, ++procedures).let {
            pending = it; pendingBytes = bytes; Admission.Admitted(it)
        }
    }

    @Synchronized fun admitLocal(opcode: Int, bytes: ByteArray?): Procedure? {
        if (invalidSession || owner != null || pending != null) return null
        return Procedure(null, opcode, ++procedures).also { pending = it; pendingBytes = bytes }
    }

    /** Terminates exactly the procedure named — never a newer one that reused the opcode. */
    @Synchronized fun transportFailed(procedure: Procedure): Terminated? {
        if (pending != procedure) return null
        val terminated = Terminated(procedure, pendingBytes)
        pending = null; pendingBytes = null
        return terminated
    }

    /** No FTMS response arrived in time. A late opcode-only response can no longer be correlated to a
     *  request, so the trainer session is quarantined exactly as it is for a controller that vanished
     *  mid-procedure. Returns non-null whenever it MATCHED, including a local procedure with no client —
     *  a null return means "not the pending procedure", and only that may skip the recovery. */
    @Synchronized fun timedOut(procedure: Procedure): Terminated? {
        if (pending != procedure) return null
        val terminated = Terminated(procedure, pendingBytes)
        pending = null; pendingBytes = null; invalidSession = true
        return terminated
    }

    /** Returns the procedure the response terminated together with the bytes it carried, so the caller
     *  commits the target that was actually acknowledged and cancels the right deadline. */
    @Synchronized fun response(opcode: Int, result: Int): Terminated? {
        if (invalidSession) return null
        val procedure = pending?.takeIf { it.opcode == opcode } ?: return null
        val terminated = Terminated(procedure, pendingBytes)
        pending = null; pendingBytes = null
        if (procedure.client != null && opcode == REQUEST_CONTROL && result == SUCCESS) owner = procedure.client
        return terminated
    }

    /** The owner could not be told the outcome; drop its claim so the next requester can arbitrate. */
    @Synchronized fun releaseOwner(client: Client): Boolean {
        if (owner != client) return false
        owner = null
        return true
    }

    @Synchronized fun trainerDropped(): Client? = owner.also {
        owner = null; pending = null; pendingBytes = null
    }

    @Synchronized fun trainerReady() { invalidSession = false }

    /** Full teardown, used by BOTH stop() and a local GATT server rebuild. Closing the server invalidates
     *  every ATT handle and delivers no disconnect callbacks, so keeping any of this would strand ownership
     *  on a generation that can never come back. */
    @Synchronized fun clear() {
        owner = null; pending = null; pendingBytes = null; invalidSession = false; keys.clear()
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
