# Android BLE transport

`AndroidBleTransport` implements the pure Kotlin `BleTransport` contract with the
Android framework GATT API. It does not impose a gamepad packet format or OTA
state machine. Use `AndroidBleScanner` for discovery; pass its `ConnectionTarget`
to the repository together with an explicitly selected adapter protocol ID.

```kotlin
val transport = AndroidBleTransport(applicationContext)
val scanner = AndroidBleScanner(applicationContext)
// Pass transport/scanner to the SDK repository; the repository owns returned links.
```

## Host responsibilities

- minSdk 23, compileSdk 35, targetSdk 35. An application can choose its own target.
- Request the permissions returned by `AndroidBlePermissions.scanPermissions()`
  and `connectPermissions()` in an Activity before calling the SDK. The SDK checks
  permissions and Bluetooth state; it never opens permission/enable-Bluetooth UI.
- Android 6–11 scans also require the location service switch. Android 12+
  uses Nearby Devices permissions. This library declares `neverForLocation` for
  scan because it never derives location; Android may filter some beacon results.
- The adapter must declare every endpoint it will read/write/subscribe to. UUID
  duplicates are resolved with zero-based service/characteristic occurrence
  selectors. These selectors are not native GATT attribute handles.
- `requireBond = true` invokes public `createBond()` if needed and waits for the
  matching device broadcast. The system owns pairing UI. Cancellation unregisters
  the SDK receiver; Android has no public API to cancel an already-started bond.
- Keep the process alive for a long update using an app-owned foreground service
  where required. This library neither owns a service nor promises background
  execution after the host is stopped or killed.

## Guarantees and operation policy

`open()` returns only after connection, discovery, endpoint/property validation,
MTU negotiation and successful notification/indication CCCD callbacks. Early
notifications are copied into the bounded ingress channel. Each GATT link has
one operation mutex shared by discovery, MTU, CCCD, reads and writes; a slot is
released only when the matching platform callback arrives. The Android 13+ value
APIs are used alongside legacy callback overrides for Android 6–12.

The link exposes the actual MTU and per-endpoint write ceiling
`min(mtu - 3, 512, configuredCharacteristicLimit)`. MTU preference is a request,
not a guarantee (Android 14+ can request 517). A synchronously rejected or
unsuccessful MTU request retains the last known MTU; a missing callback times out.
Vendor packet headers, encryption, alignment and stricter device limits must be
accounted for by the protocol plugin. `characteristicWriteLimits` can supply
vendor-documented ceilings, which cannot be queried through Android's public API.

Reads and writes validate the characteristic property at use time. No frame
splitting, sleeps, automatic retries, reconnects or reflection-based cache refresh
are performed. A write completion is a GATT completion, not an application/OTA ACK.
Both write modes wait for `onCharacteristicWrite`; if a device/stack never emits
it, the operation fails by timeout and the link closes rather than guessing.

An operation timeout/cancellation after submission closes the link before the
next operation can start. Cancellation while still queued does not disrupt the
other owner's operation. Disconnect, notification overflow and Service Changed
terminate the link, fail pending work and complete `termination` even while idle.
Close is idempotent, invalidates GATT identity first and never waits for a
disconnect callback. Late callbacks from released GATT instances are ignored.

Each scan collection owns a distinct callback; cancellation stops only that
scan. Results contain copies of service/manufacturer bytes. Failures, adapter
shutdown and scan-buffer overflow terminate the flow explicitly. Narrow filters
or deduplicate downstream if the UI does not need every advertisement.

## Firmware selected through SAF

```kotlin
val source = AndroidUriFirmwareSource.create(context, uri, metadata)
try {
    repository.upgrade(source)
} finally {
    source.close()
}
```

This handles seekable and non-seekable URIs by making a bounded, disk-backed
temporary snapshot with main-safe I/O and exact-range random reads. It does not
load a complete firmware image into RAM. Creation cancellation/failure deletes
the partial file. The owner must close the returned source. The sample default
disk ceiling is 256 MiB; set a smaller product-specific ceiling if appropriate.
Firmware authenticity and device compatibility remain the updater's preflight
responsibility. Closing a source while an update is reading is caller misuse.

## Device acceptance checks

Compile-time checks cannot validate a physical Bluetooth stack. Before shipping,
exercise the actual gamepad on the supported Android/OEM matrix: pairing denial,
permission revocation, Bluetooth toggle, radio loss, MTU 23 and larger values,
CCCD failure, duplicate UUIDs, oversized packets, no-response writes, cancellation
during every opening stage, update-mode identity, and process/service lifecycle.

References: Android's official [GATT API](https://developer.android.com/reference/android/bluetooth/BluetoothGatt),
[callback API](https://developer.android.com/reference/android/bluetooth/BluetoothGattCallback),
and [Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions).
