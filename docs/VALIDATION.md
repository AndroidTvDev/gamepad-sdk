# 验证记录

日期：2026-10-18。

本次交付是包含实现、示例与测试的 SDK 源码工程。

## 已执行

```bash
python3 tools/verify.py --cache ../tooling/gamepad-verify
```

使用 Kotlin 2.1.21 编译器、Java 17 运行时、coroutines 1.10.2：

| 验证项 | 结果 |
|---|---|
| sdk-core：10 个主源码文件 | 编译通过 |
| protocol-demo：4 个主源码文件 | 编译通过 |
| sdk-android：4 个真实 Android 实现文件 | 对 API 35 类库编译通过 |
| sample-console：1 个主源码文件 | 编译通过并运行成功 |
| ProtocolSessionTest | 15 / 15 通过 |
| RepositoryTest | 15 / 15 通过 |
| GamepadSdkTest | 1 / 1 通过 |
| DemoEndToEndTest | 8 / 8 通过 |
| 合计 | 39 / 39 测试通过，0 失败、0 跳过 |

报告原件位于本目录 `test-results/`。`verification.log` 保留编译、JUnit 与模拟演示输出。

## 已覆盖的行为

- write 返回前立即收到 ACK，不丢响应。
- 请求串行化、包含排队的截止时间、排队取消不影响另一请求。
- 发包后的超时/取消关闭会话，防止迟到 ACK 污染后续请求。
- NACK、解析失败、空闲断连、父 Scope 取消、幂等关闭。
- 窗口 ACK 的可靠 mailbox、溢出明确失败、请求与 mailbox 不抢响应。
- Repository 的 Busy、Unsupported、初始化前不可 Ready。
- disconnect 打断升级、清理期间拒绝新连接。
- OTA 内部重连保留独占权，旧会话不能覆盖新状态。
- 相同 GATT Profile 不自动视为相同普通模式会话。
- 未确认安装结果不报告 Success。
- SDK close 即使遇到仍在清理的插件，也能停止独立扫描。
- MTU 23 下的分帧通信、CRC、固件偏移 ACK、SHA/型号预检。
- 固件在预检后改变时设备校验失败，不返回 Verified。
- 本地关闭先发布 termination，再结束通知流。

模拟完整过程使用 73 字节教学固件，在 MTU 23 下按协议开销切包；版本从
`1.0.0-demo` 变为 `2.0.0-demo`，查询确认后返回 `Verified`，最后无残留模拟连接。

## 尚未执行

当前环境只有 Java 运行时，没有完整 JDK/Android SDK。Gradle Wrapper 官方文件已提供，
但本环境没有完成 Gradle 依赖解析、Android lint 或 `assembleRelease`。

Android 验证采用 Robolectric Android 15 / API 35 的 android-all 作为编译类路径；这是
对真实 Android 实现代码的 Kotlin 编译检查，不是运行 Android 蓝牙框架。

未进行真实手柄、不同手机厂商蓝牙栈、系统配对界面、Bootloader 无线重连或真实厂商
固件的真机测试。教学模拟器不具备这些验证能力。

生产接入时，请在具备 Android SDK 的环境执行 README 中的 Gradle 命令，再依据真实
协议完成设备验收。设备协议中的数字签名、鉴权、防降级与恢复流程由厂商插件提供。
