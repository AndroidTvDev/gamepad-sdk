package dev.gamepad.sdk

data class FirmwareMetadata(
    val compatibleModels: Set<String>,
    val compatibleHardwareRevisions: Set<String>,
    val expectedVersion: String,
    val sha256: String,
)

interface FirmwareSource {
    val size: Long
    val metadata: FirmwareMetadata

    // Implementation is main-safe. Reads the exact requested range or throws.
    // A URI-backed implementation may use seekable FD or an app-owned temp file.
    suspend fun read(offset: Long, length: Int): ByteArray
}

sealed interface UpgradeResult {
    // Only after post-install version readback or equivalent device proof.
    data class Verified(val installedVersion: String) : UpgradeResult
    data class NeedsConfirmation(val reason: String) : UpgradeResult
}

sealed interface UpgradeState {
    data object Idle : UpgradeState
    data object Checking : UpgradeState
    data object EnteringBootloader : UpgradeState
    data object Reconnecting : UpgradeState
    data class Transferring(val confirmedBytes: Long, val totalBytes: Long) : UpgradeState
    data object Validating : UpgradeState
    data object Rebooting : UpgradeState
    data object Confirming : UpgradeState
    data class Success(val installedVersion: String) : UpgradeState
    data class PendingConfirmation(val reason: String) : UpgradeState
    data class Failed(val message: String) : UpgradeState
    data object Cancelled : UpgradeState
}

interface UpgradeContext {
    val originalDevice: DeviceInfo
    val currentSession: ProtocolSession

    /**
     * Candidate discovery only. Plugin must then verify device identity through
     * the vendor's identity/token/handshake mechanism before writing firmware.
     * Multiple candidates must be resolved explicitly, never by first().
     */
    suspend fun findCandidates(
        timeoutMillis: Long,
        predicate: (Advertisement) -> Boolean,
    ): List<Advertisement>

    /**
     * Replaces owned session, closes old one and updates connection generation.
     * Retains upgrade exclusivity; does not call public Repository.connect().
     * A cancellation/close invalidates both active and opening links.
     */
    suspend fun reconnect(target: ConnectionTarget, spec: SessionSpec): ProtocolSession
}

interface FirmwareUpdater {
    /**
     * Vendor-owned state machine: preflight, authentication, mode switch,
     * identity verification, packet sizing, ACKs, retry/resume, finalize,
     * reboot and confirmation. No universal OTA chunk size or retry count.
     * Report transfer progress from device-confirmed bytes, not write callbacks.
     * State callback is non-blocking. Repository owns terminal state publication.
     */
    suspend fun upgrade(
        context: UpgradeContext,
        firmware: FirmwareSource,
        report: (UpgradeState) -> Unit,
    ): UpgradeResult
}
