package dev.gamepad.sdk.sample

import dev.gamepad.sdk.*
import dev.gamepad.sdk.demo.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Runs entirely on a JVM against a fictional device; never connects to Bluetooth hardware. */
fun main() = runBlocking {
    val transport = DemoBleTransport()
    val sdk = GamepadSdk(transport, DemoBleScanner(listOf(transport.device)), setOf(DemoAdapterFactory()), this)
    val controller = sdk.newController()
    val progress = launch {
        controller.upgradeState.collect { state ->
            println("Upgrade: $state")
        }
    }
    try {
        val discovered = sdk.discover().first()
        println("Discovered fictional device: ${discovered.advertisement.name}")
        controller.connect(discovered.advertisement.target, DemoAdapterFactory.PROTOCOL_ID)
        println("Before: ${controller.readInfo()}")
        controller.setLight(LightSetting(255, 64, 0))
        println("Light: ${transport.device.light}")
        val image = ByteArray(73) { (it * 7).toByte() }
        val firmware = DemoFirmwareSource.create(image, expectedVersion = "2.0.0-demo")
        println("Result: ${controller.upgrade(firmware)}")
        println("After: ${controller.readInfo()}")
        check(transport.device.installedImage.contentEquals(image))
        controller.disconnect()
        check(transport.activeLinkCount == 0)
        println("Simulated communication and OTA completed successfully.")
    } finally {
        progress.cancelAndJoin()
        sdk.close()
    }
}
