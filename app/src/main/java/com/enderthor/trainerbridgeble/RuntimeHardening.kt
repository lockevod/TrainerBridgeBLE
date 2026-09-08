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

internal class FtmsControlCoordinator {
    data class Client(val address: String, val generation: Long)
    data class Procedure(val client: Client?, val opcode: Int)

    sealed interface Admission {
        data class Admitted(val procedure: Procedure) : Admission
        data class Rejected(val result: Int) : Admission
    }

    private var owner: Client? = null
    private var pending: Procedure? = null
    private var invalidSession = false

    @Synchronized fun admit(client: Client, opcode: Int): Admission {
        if (invalidSession || pending != null) return Admission.Rejected(OPERATION_FAILED)
        if (if (opcode == REQUEST_CONTROL) owner != null && owner != client else owner != client)
            return Admission.Rejected(CONTROL_NOT_PERMITTED)
        return Procedure(client, opcode).let { pending = it; Admission.Admitted(it) }
    }

    @Synchronized fun admitLocal(opcode: Int): Boolean {
        if (invalidSession || owner != null || pending != null) return false
        pending = Procedure(null, opcode)
        return true
    }

    @Synchronized fun transportFailed(client: Client?, opcode: Int): Client? {
        if (pending != Procedure(client, opcode)) return null
        pending = null
        return client
    }

    @Synchronized fun response(opcode: Int, result: Int): Client? {
        if (invalidSession) return null
        val procedure = pending?.takeIf { it.opcode == opcode } ?: return null
        pending = null
        if (procedure.client != null && opcode == REQUEST_CONTROL && result == SUCCESS) owner = procedure.client
        return procedure.client
    }

    @Synchronized fun disconnect(client: Client): Boolean {
        val lostPending = pending?.client == client
        if (owner == client) owner = null
        if (lostPending) { pending = null; invalidSession = true }
        return lostPending
    }

    @Synchronized fun trainerDropped(): Client? = owner.also {
        owner = null; pending = null
    }

    @Synchronized fun trainerReady() { invalidSession = false }

    @Synchronized fun clear() { owner = null; pending = null; invalidSession = false }

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
