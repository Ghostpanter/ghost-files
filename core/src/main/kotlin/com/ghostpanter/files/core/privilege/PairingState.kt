package com.ghostpanter.files.core.privilege

/** Phases of the Android 11+ wireless-debug self-pairing flow. */
enum class PairPhase {
    IDLE,
    /** Notification shown; waiting for the six-digit code. */
    WAITING_CODE,
    /** SPAKE2 pairing in progress. */
    PAIRING,
    /** Looking up / connecting to the wireless-debug port. */
    CONNECTING,
    /** app_process PrivilegeServer being started. */
    STARTING_SERVER,
    /** Binder received; elevated access ready. */
    READY,
    FAILED,
}

data class PairState(
    val phase: PairPhase = PairPhase.IDLE,
    val detail: String = "",
    /** Last known wireless-debug connect port (0 = unknown). */
    val connectPort: Int = 0,
    /** Tail of /data/local/tmp/ghost-priv.log captured on failure (may be empty). */
    val log: String = "",
)

sealed interface PairEvent {
    data object StartWaitCode : PairEvent
    data class CodeEntered(val code: String) : PairEvent
    data object PairSucceeded : PairEvent
    data class ConnectPort(val port: Int) : PairEvent
    data object Connected : PairEvent
    data object ServerStarted : PairEvent
    data object BinderReady : PairEvent
    data class Fail(val reason: String, val log: String = "") : PairEvent
    data object Reset : PairEvent
    data object Stop : PairEvent
}

/** Pure reducer for the pairing / start state machine. */
fun reducePair(state: PairState, event: PairEvent): PairState = when (event) {
    PairEvent.StartWaitCode -> PairState(PairPhase.WAITING_CODE, "waiting for pairing code")
    is PairEvent.CodeEntered -> when {
        !event.code.matches(Regex("\\d{6}")) ->
            PairState(PairPhase.FAILED, "pairing code must be exactly six digits", state.connectPort)
        state.phase != PairPhase.WAITING_CODE && state.phase != PairPhase.FAILED && state.phase != PairPhase.IDLE ->
            state // ignore late codes outside the wait window unless restarting
        else -> PairState(PairPhase.PAIRING, "pairing…", state.connectPort)
    }
    PairEvent.PairSucceeded ->
        if (state.phase == PairPhase.PAIRING) PairState(PairPhase.CONNECTING, "discovering connect port", state.connectPort)
        else state
    is PairEvent.ConnectPort ->
        if (state.phase == PairPhase.CONNECTING || state.phase == PairPhase.PAIRING)
            PairState(PairPhase.CONNECTING, "port ${event.port}", event.port)
        else state.copy(connectPort = event.port)
    PairEvent.Connected ->
        if (state.phase == PairPhase.CONNECTING) PairState(PairPhase.STARTING_SERVER, "starting server", state.connectPort)
        else state
    PairEvent.ServerStarted ->
        if (state.phase == PairPhase.STARTING_SERVER) PairState(PairPhase.STARTING_SERVER, "awaiting binder", state.connectPort)
        else state
    PairEvent.BinderReady -> PairState(PairPhase.READY, "ready", state.connectPort)
    is PairEvent.Fail -> PairState(PairPhase.FAILED, event.reason, state.connectPort, event.log)
    PairEvent.Reset, PairEvent.Stop -> PairState(PairPhase.IDLE, "", if (event == PairEvent.Stop) 0 else state.connectPort)
}

/** Server-side guard: only the owning app uid (or root) may call into PrivilegeServer. */
fun acceptCallingUid(callingUid: Int, expectedAppUid: Int): Boolean =
    callingUid == expectedAppUid || callingUid == 0

/** Binder provider should only accept a binder from shell (2000) or root (0). */
fun acceptBinderSenderUid(callingUid: Int): Boolean =
    callingUid == 2000 || callingUid == 0
