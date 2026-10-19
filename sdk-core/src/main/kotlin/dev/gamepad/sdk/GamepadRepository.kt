package dev.gamepad.sdk

import kotlinx.coroutines.flow.StateFlow

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Disconnecting : ConnectionState
    data object Closed : ConnectionState
    data object Connecting : ConnectionState
    data object Initializing : ConnectionState
    data class Ready(val info: DeviceInfo, val capabilities: GamepadCapabilities) : ConnectionState
    data object Upgrading : ConnectionState
    data class Failed(val message: String) : ConnectionState
}

class BusyException : IllegalStateException("Another device operation is active")
class UnsupportedFeatureException(feature: String) :
    UnsupportedOperationException("Unsupported feature: $feature")

/**
 * One instance owns ONE device session and its own child CoroutineScope.
 * No Android Context, UUID, ByteArray, or vendor types reach the UI API.
 * FirmwareSource is supplied by an injected main-safe file/URI data source.
 */
interface GamepadRepository {
    val connectionState: StateFlow<ConnectionState>
    val upgradeState: StateFlow<UpgradeState>

    suspend fun connect(target: ConnectionTarget, protocolId: String)
    suspend fun readInfo(): DeviceInfo
    suspend fun setLight(setting: LightSetting)

    // An imperative operation, not a cold Flow that restarts on collection.
    // Busy or Unsupported fails explicitly; cancellation is propagated.
    suspend fun upgrade(firmware: FirmwareSource): UpgradeResult

    /**
     * Preempts active work: invalidate generation, cancel active operation,
     * fail requests, then release resources. Never queue behind an OTA mutex.
     * Reject new connect/commands/upgrade while disconnect cleanup is active.
     * Once return completes, all owned resources have been released.
     */
    suspend fun disconnect()

    // Permanent, idempotent and non-blocking; cancels owned scope and links.
    // Used by the actual owner (ViewModel/session service), not every Composable.
    fun close()
}
