@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.gamepad.sdk

import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Test

class GamepadSdkTest {
    @Test fun closeStopsScanEvenWhileAPluginIsStillCleaningUp() = runTest {
        val commandEntered = CompletableDeferred<Unit>()
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val scanEntered = CompletableDeferred<Unit>()
        val scanStopped = CompletableDeferred<Unit>()
        val target = ConnectionTarget("test://sdk")
        val scanner = object : BleScanner {
            override fun scan() = flow {
                try {
                    emit(Advertisement(target, "test", emptySet(), emptyMap(), emptyMap()))
                    scanEntered.complete(Unit)
                    awaitCancellation()
                } finally { scanStopped.complete(Unit) }
            }
        }
        val transport = object : BleTransport {
            override suspend fun open(target: ConnectionTarget, profile: BleProfile) = object : BleLink {
                private val ended = CompletableDeferred<Throwable?>()
                private val inbound = Channel<GattNotification>(1)
                override val termination = ended
                override val notifications = inbound
                override val limits = LinkLimits(23, emptyMap())
                override suspend fun write(frame: GattWrite) = Unit
                override suspend fun read(endpoint: GattEndpoint) = byteArrayOf()
                override fun close() { ended.complete(null); inbound.close() }
            }
        }
        val factory = object : GamepadAdapterFactory {
            override val protocolId = "test"
            override fun isCandidate(advertisement: Advertisement) = true
            override fun create() = object : GamepadAdapter {
                override val normalSession = SessionSpec(BleProfile(emptySet(), emptyList())) {
                    object : MessageDecoder {
                        override fun feed(notification: GattNotification): List<ProtocolMessage> = emptyList()
                    }
                }
                override val capabilities = GamepadCapabilities(true, false, false)
                override val updater: FirmwareUpdater? = null
                override suspend fun initialize(session: ProtocolSession) = DeviceInfo("test", null, "1", null)
                override suspend fun readInfo(session: ProtocolSession): DeviceInfo {
                    commandEntered.complete(Unit)
                    try { awaitCancellation() } finally {
                        withContext(NonCancellable) { cleanupEntered.complete(Unit); releaseCleanup.await() }
                    }
                }
                override suspend fun setLight(session: ProtocolSession, setting: LightSetting) = Unit
            }
        }
        val sdk = GamepadSdk(transport, scanner, setOf(factory), backgroundScope)
        val controller = sdk.newController()
        var scan: Job? = null
        var command: Job? = null
        try {
            controller.connect(target, "test")
            command = launch { try { controller.readInfo() } catch (_: CancellationException) { } }
            commandEntered.await()
            scan = launch { sdk.discover().collect() }
            scanEntered.await()
            sdk.close()
            cleanupEntered.await()
            // Old code waited for all SDK children, including this uncompleted
            // cleanup, so this signal could never arrive before releaseCleanup.
            withTimeout(1_000) { scanStopped.await() }
            scan.join()
            assertEquals(ConnectionState.Closed, controller.connectionState.value)
        } finally {
            releaseCleanup.complete(Unit)
            command?.join()
            scan?.cancelAndJoin()
            sdk.close()
        }
    }
}
