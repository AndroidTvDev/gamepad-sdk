# Gamepad SDK · Kotlin

面向 Android BLE 手柄的通信与固件升级 SDK 源码工程，版本 `0.1.0`。

公共 BLE 基础设施、协议会话、设备 Repository 已实现。接入新的 BLE 手柄时，新增一个协议适配模块并注册，界面继续调用相同的业务 API。真实厂商的 UUID、鉴权、命令和 OTA 格式需要按厂商文档实现。

## 模块

| 模块 | 内容 | Android 依赖 |
|---|---|---|
| sdk-core | 公共 API、请求会话、可靠收件箱、Repository、SDK 入口、固件文件源 | 无，JVM 可测 |
| sdk-android | 原生 BLE 扫描/GATT、权限检查、配对等待、URI 固件快照 | Android 23+ |
| protocol-demo | 虚构协议、模拟 BLE 设备、示范 OTA、协议测试 | 无 |
| sample-console | 完整可执行模拟演示 | 无 |

开发版本固定为 Kotlin 2.1.21、coroutines 1.10.2、JDK 17、Gradle 8.13、AGP 8.9.2、compileSdk 35。这里固定的是可复现构建版本，不声称这些版本是最新版本。

## 先运行模拟器

安装 JDK 17；Android Studio 打开项目时安装 Android API 35。标准构建：

```bash
./gradlew :sdk-core:test :protocol-demo:test :sample-console:run
./gradlew :sdk-android:assembleRelease
```

Windows 使用 `gradlew.bat`。完整 Gradle 工程配置会加载 Android 插件，因此建议配置 `ANDROID_HOME` 或本机 `local.properties` 中的 `sdk.dir`。

如果机器只有 Java 17 运行时，没有 Gradle/Android SDK，可以运行附带的独立验证工具：

```bash
python3 tools/verify.py
```

首次从 Maven Central 下载固定版本依赖并校验 SHA-256。它会实际编译四个模块的 Kotlin 源码、执行 JVM 测试并运行模拟演示；Android 编译使用 API 35 的兼容类库。它不替代 Android lint、完整 AAR 构建或真机测试。

演示会执行：发现虚构设备 → 连接握手 → 查询版本 → 设置 RGB 灯效 → 在 MTU 23 下传输固件 → 校验 → 查询安装后的版本 → 断开。

`protocol-demo` 仅用于教学和测试，没有真实厂商协议。不要把它注册到真实手柄的生产连接中。

## Android 接入

推荐先以源码模块接入 `sdk-core`、`sdk-android`。App 依赖：

```kotlin
dependencies {
    implementation(project(":sdk-core"))
    implementation(project(":sdk-android"))
}
```

创建 SDK，传入由 ViewModel 或设备服务拥有的 CoroutineScope：

```kotlin
import android.content.Context
import dev.gamepad.sdk.*
import dev.gamepad.sdk.android.*
import kotlinx.coroutines.CoroutineScope

fun createSdk(
    context: Context,
    ownerScope: CoroutineScope,
    adapters: Set<GamepadAdapterFactory>,
): GamepadSdk = GamepadSdk(
    transport = AndroidBleTransport(context.applicationContext),
    scanner = AndroidBleScanner(context.applicationContext),
    adapters = adapters,
    parentScope = ownerScope,
)
```

SDK 不请求权限、不弹出启用蓝牙界面。Activity/Compose 先取得权限并确认蓝牙可用，再开始扫描。可使用：

```kotlin
val requestedPermissions = AndroidBlePermissions.scanPermissions()
val missing = AndroidBlePermissions.missing(context, requestedPermissions)
// missing 非空时，交给宿主的 Activity Result 权限入口申请。
```

Android 12+ 扫描入口检查 SCAN 和 CONNECT；Android 6–11 检查位置权限及位置开关。Manifest 声明扫描不用于定位；若宿主确有定位用途，需要自行调整声明。更详细的 Android 行为见 `sdk-android/README.md`。

扫描结果携带候选协议集合：

```kotlin
sdk.discover().collect { discovered ->
    // 展示设备和候选型号。多个匹配时显式消歧，不自动取第一个。
    showDevice(discovered.advertisement, discovered.protocolIds)
}
```

取消扫描 collect 就停止该次扫描；SDK close 也会停止由 discover 发起的扫描。

每台设备创建一个独立 controller：

```kotlin
val controller = sdk.newController()

controller.connect(selectedDevice.target, selectedProtocolId)
val info = controller.readInfo()
controller.setLight(LightSetting(red = 255, green = 80, blue = 0))

// 观察与操作分开，多次观察不会重复启动升级。
controller.connectionState.collect { state -> /* 更新连接 UI */ }
controller.upgradeState.collect { state -> /* 更新升级 UI */ }
```

这两个无限状态 collect 应在不同的 `launch` 内，或在 UI 用 `collectAsStateWithLifecycle()` 分别收集，不要顺序写在同一个协程中。业务方法在 `viewModelScope.launch` 中调用，并将 Busy、Unsupported、LinkClosed 等错误映射为页面状态；捕获 CancellationException 后要重新抛出。

不同设备使用不同 controller；不要为同一物理设备重复创建并连接多个 controller。SDK 不在所有实例之间实现全局蓝牙地址锁。

## 固件升级

从 App 内文件创建固件源：

```kotlin
val source = FileFirmwareSource.open(file, firmwareMetadata)
val result = controller.upgrade(source)
```

文件在升级期间必须保持不变。从 SAF/content URI 获取稳定磁盘快照：

```kotlin
AndroidUriFirmwareSource.create(context, selectedUri, firmwareMetadata).use { source ->
    val result = controller.upgrade(source)
}
```

`FirmwareMetadata` 包含兼容型号、硬件版本、期望版本与 SHA-256。Repository 在发送升级命令前验证基本兼容性和整包摘要；厂商容器解析、数字签名、鉴权和防降级仍由厂商升级插件实现。SHA-256 指纹本身不代表数字签名验证。

大固件按范围读取；URI 先流式写入 App 缓存快照，不整包加载到内存。源文件的所有者负责在操作完成/取消后释放临时快照。

`Transferring` 按设备 ACK 确认的数据量计算。发送完成后还要经过设备校验、重启和安装结果确认。无法确认安装结果时返回 `NeedsConfirmation`，状态为 `PendingConfirmation`。

## 新增真实手柄：改哪些代码

新增自己的协议目录，按需要实现：

1. `YourAdapterFactory`：候选广播识别、protocolId、创建新 adapter。
2. `YourAdapter`：稳定的 normalSession、能力、初始化握手、版本/灯效等业务。
3. `YourDecoder`：厂商帧边界、长度、校验与响应对象。
4. `YourFirmwareUpdater`：完整厂商 OTA 流程。

简单协议可以合并文件。传给 SDK 的 adapters 集合增加一个 Factory 即完成注册，公共 Repository/ViewModel 不增加品牌分支。

```kotlin
interface GamepadAdapterFactory {
    val protocolId: String
    fun isCandidate(advertisement: Advertisement): Boolean
    fun create(): GamepadAdapter
}
```

Factory 可以由 DI 作为单例持有；create 必须返回独立的 adapter，decoder 也每会话创建。Hilt 可用 `@IntoSet` 注册工厂，在组合根注入集合；SDK 本身不强制依赖 Hilt。

厂商差异包括 UUID、NOTIFY/INDICATE、写入方式、绑定、握手、命令、响应关联、固件分包与升级模式。并不是只替换 encode/decode 两个函数。

不支持的业务通过 capabilities 表达，并明确抛出 UnsupportedFeatureException，不返回假成功。

## 普通请求与窗口 OTA

`ProtocolSession.request(ProtocolRequest(...))` 在发送之前登记 matcher，默认一次一个请求在途。timeoutMillis 包括等待队列、实际写入与等待设备响应。匹配器根据厂商命令类型、事务号、包序号/偏移区分响应。

如果发送可能开始后超时或取消，默认废弃该会话，避免迟到 ACK 进入下一条请求。没有业务层自动重试。远端是否已经执行命令，需按厂商协议判断。

窗口式 OTA 先登记可靠接收入口，再发送窗口：

```kotlin
val mailbox = session.openMailbox(accepts = { it is YourWindowAck }, capacity = 64)
try {
    // 按厂商协议写一个窗口的帧，再按序号/offset处理 ACK。
    session.write(frame)
    val message = mailbox.messages.receive()
} finally {
    mailbox.close()
}
```

`YourWindowAck`、frame 等由厂商协议模块定义。一个会话支持一个可靠 mailbox，不能同时用 request 抢同一批响应。窗口大小、流控、超时、重传和断点恢复由 updater 决定。

`unsolicitedMessages` 是无 replay、容量 64、慢消费者丢旧值的遥测流。关键 ACK 仅使用 request/mailbox；不要把关键控制流程建立在遥测 Flow 上。收到 session.termination 后，不能继续依据收件箱中旧缓存消息报告成功。

## OTA 模式切换和重新连接

Updater 通过 UpgradeContext.findCandidates 发现候选设备，通过 reconnect 切换会话。UUID 相同不代表协议或解码器相同；普通模式与 Bootloader 可以共用服务却使用不同报文。

`adapter.normalSession` 必须是稳定的 SessionSpec 实例。返回普通模式时，reconnect 复用该实例。只有当前仍是这一普通模式会话，Repository 才恢复 Ready；否则释放残留会话并进入 Disconnected。升级结果和连接状态分别表达，Verified 不代表还持有可用连接。

进入 Bootloader 后必须按厂商身份信息/token/鉴权验证目标；不按设备名称选择第一个结果。插件要明确是否支持断点续传与安全中止。当前教学协议不支持这两项。

## 操作与所有权

- 连接、初始化、普通命令、升级相互协调；忙时返回 Busy，不积压过期业务命令。
- 升级独占，内部重连保留独占权。物理断连使会话立即失效；updater 可按协议处理预期断连并重新连接。
- disconnect 可打断升级；清理完成前拒绝新连接。清理超时会永久关闭该 controller，避免非协作插件污染新会话。
- close 幂等且永久；所有者取消 Scope 会关闭会话。长时间后台升级由服务持有 SDK，页面消失只停止观察。
- 调用 `sdk.release(controller)` 可提前关闭并移除某个 controller；`sdk.close()` 释放全部。

SDK 不在 suspend 方法外吞掉 CancellationException。取消本地任务并不等于设备已经回滚；安全中止需要设备协议明确支持。

## 发布到本地 Maven

```bash
./gradlew :sdk-core:publishToMavenLocal :sdk-android:publishToMavenLocal
```

宿主工程添加 mavenLocal 后可引用 `dev.gamepad:sdk-android:0.1.0`。如果手动复制 Android AAR，还必须提供 sdk-core 与协程等依赖；普通 AAR 不会自动包含另一个模块的全部类。

## 验证范围

确切验证结果与限制见 `docs/VALIDATION.md`。本项目实现了真实 Android GATT 源码；JVM 模拟测试不等于 BLE 真机已经通过。落地设备仍需验证配对、权限、MTU、设备身份、Bootloader 重连、实际 OTA 文件以及断电/断连恢复。

范围为 BLE GATT 厂商通信/升级。标准手柄按键继续由 Android KeyEvent/MotionEvent 处理；经典蓝牙 SPP、USB 和新的产品业务需要各自的适配能力，不能只替换 BLE UUID。
