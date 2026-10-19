package dev.gamepad.sdk

data class GamepadCapabilities(
    val readInfo: Boolean,
    val light: Boolean,
    val firmwareUpdate: Boolean,
    val resumeUpdate: Boolean = false,
    // Cancellation of local work and safe device-side abort are different.
    val safeAbortUpdate: Boolean = false,
)

data class DeviceInfo(
    val model: String,
    val hardwareRevision: String?,
    val firmwareVersion: String?,
    val serialNumber: String?,
)

data class LightSetting(val red: Int, val green: Int, val blue: Int) {
    init {
        require(red in 0..255 && green in 0..255 && blue in 0..255)
    }
}

/** A stateless factory may be a DI singleton. The created adapter must not be. */
interface GamepadAdapterFactory {
    val protocolId: String
    fun isCandidate(advertisement: Advertisement): Boolean
    fun create(): GamepadAdapter
}

/** Fresh per device connection; contains all vendor-specific behavior. */
interface GamepadAdapter {
    // A stable instance for this adapter. Reuse this exact SessionSpec when an
    // updater returns to application mode; equal UUIDs do not imply equal codecs.
    val normalSession: SessionSpec
    val capabilities: GamepadCapabilities
    val updater: FirmwareUpdater?

    // Perform vendor authentication/handshake and verify the device identity.
    // Repository must not publish Ready until this completes successfully.
    suspend fun initialize(session: ProtocolSession): DeviceInfo
    suspend fun readInfo(session: ProtocolSession): DeviceInfo
    suspend fun setLight(session: ProtocolSession, setting: LightSetting)
}

class AdapterRegistry(factories: Set<GamepadAdapterFactory>) {
    init {
        require(factories.map { it.protocolId }.distinct().size == factories.size) {
            "Duplicate protocolId"
        }
    }

    private val byId = factories.associateBy { it.protocolId }

    fun candidates(advertisement: Advertisement): Set<String> =
        byId.values.filter { it.isCandidate(advertisement) }
            .mapTo(mutableSetOf()) { it.protocolId }

    // Do not silently choose the first name/advertisement match.
    fun create(protocolId: String): GamepadAdapter =
        requireNotNull(byId[protocolId]) { "Unknown protocol: $protocolId" }.create()
}
