# MelodyCodecTweaker

面向 OPPO / OnePlus “无线耳机”App 的 LSPosed 模块，用来在 Melody 设备详情页和 OneSpace 快捷面板中注入蓝牙音质控制项。

模块不会修改系统文件，也不替换 Melody 页面；它只在运行时 Hook `com.oplus.melody`、`com.android.bluetooth` 和 `com.oplus.wirelesssettings`，读取系统蓝牙栈当前协商能力，并尽量用宿主自己的 COUI / Preference 组件渲染 UI。

## 主要功能

- 在 `DetailMainActivity` 主面板中注入“蓝牙音质”区域。
- 在 `OneSpaceDetailActivity` 快捷面板中注入同一套音质控制。
- 显示当前协议：`SBC`、`AAC`、`LDAC`、`LHDC`、`LC3` 等。
- 支持播放质量切换：例如 LHDC 的“连接优先 / 均衡 / 音质优先”，LDAC 的 330 / 660 / 990 kbps。
- 支持采样率切换：根据耳机和当前协议动态显示 44.1 / 48 / 96 / 192 kHz 等可选项。
- 播放质量和采样率会做联动修正，避免切到当前组合不支持的档位。
- DetailMain 支持“记住此耳机的选择”，按耳机 MAC 保存并在下次连接时自动应用。
- 支持 LE Audio 开关：开启后进入 LC3，隐藏播放质量和采样率；关闭后回到经典蓝牙音频并刷新 A2DP 状态。
- 耳机未连接或当前功能不可用时，注入项会跟随官方控件表现为不可用状态。
- 提供模块总开关 Activity，关闭后下次启动 Melody 时不再注入。

## 使用要求

- Android 12 及以上。
- 支持 libxposed API 101 的框架，例如新版 LSPosed。
- OPPO / OnePlus / ColorOS 系设备上的“无线耳机”App：`com.oplus.melody`。
- 建议启用全部三个 LSPosed 作用域：
  - `com.oplus.melody`
  - `com.android.bluetooth`
  - `com.oplus.wirelesssettings`

`com.oplus.melody` 负责注入界面，`com.android.bluetooth` 负责更稳定地读写 A2DP 编解码状态，`com.oplus.wirelesssettings` 负责调用系统侧 LE Audio 开关能力。少开作用域可能仍能部分工作，但实时切换和 LE Audio 会明显更容易失效。

## 安装建议

1. 安装模块 APK。
2. 在 LSPosed 中启用模块。
3. 勾选上面三个作用域。
4. 强制停止“无线耳机”、蓝牙相关进程，或直接重启手机。
5. 打开“无线耳机”App，进入耳机主面板或 OneSpace 面板查看注入项。

如果只想临时停用模块，可以打开桌面图标“蓝牙音质（Melody）”，关闭模块总开关。关闭后需要重启 Melody 进程才会完全恢复原始页面。

## 关于 Melody 版本

这是一个 Hook Melody 内部页面结构的模块，不是基于公开 SDK。虽然当前实现已经尽量做了通用兼容：

- 优先 Hook Manifest 中较稳定的 Activity。
- 不绑定单个 R8 混淆类名，运行时扫描 PreferenceFragment 和 PreferenceScreen。
- 通过页面 key、Preference 结构和方法签名兜底查找注入点。
- COUI 弹窗优先使用宿主已有 builder，失败后再降级。
- 对不支持 Hi-Res、LE Audio 或特定协议能力的耳机做隐藏 / 禁用处理。

但如果 Melody 更新后彻底改掉页面架构、Preference key、资源样式或包名，模块仍可能部分失效甚至完全失效。

因此发布版本建议用户：

- 非必要不更新 Melody APK。
- 尽量固定在已验证可用的 Melody 版本。
- 更新 Melody 前先保留旧版 APK，方便回退。
- 更新后如果模块失效，请提供新版 Melody APK、logcat、手机型号、系统版本、耳机型号和截图。

当前代码主要围绕近期 Melody 16.7.x 结构做过适配，同时保留对旧版 Preference 页面结构的探测逻辑。未知版本可以尝试，但不承诺每次 Melody 更新都零适配。

## LE Audio 说明

LE Audio 开关只会在模块判断当前耳机支持时显示。判断来源包括 Melody 自己的 `le-device-info`、系统蓝牙 UUID 信息，以及系统侧桥接回传状态，避免“手机支持 LE Audio，但当前耳机不支持”时误显示。

开启流程：

1. 用户在 Melody 页面点击 LE Audio。
2. 模块在 Melody 当前 Activity 中弹出确认对话框。
3. 用户确认后，发送请求到系统侧作用域。
4. `com.oplus.wirelesssettings` 或蓝牙侧桥接调用 `LeAudioProfile.setEnabled(device, true)`。
5. 蓝牙栈完成切换后回传状态，页面显示 `蓝牙音质: LC3`。
6. 播放质量和采样率行隐藏，因为 LC3 不走经典 A2DP 的 LDAC / LHDC 档位。

关闭 LE Audio 时，耳机通常会短暂断开并重新连回经典蓝牙音频。模块会先恢复经典音质行，再延迟读取 A2DP 状态，避免页面需要重新进入才刷新的问题。

## 播放质量与采样率

模块不会硬编码所有耳机档位，而是优先从系统蓝牙栈读取当前耳机的可选能力：

- 当前协议来自 `BluetoothA2dp.getCodecStatus()`。
- 可选播放质量来自 `codecSpecific1` 能力。
- 可选采样率来自 `sampleRate` bitmask。
- 写入优先走 `setCodecConfigPreference()`，并等待系统广播确认。

写入路径按顺序降级：

1. Melody 进程内直接调用隐藏 A2DP API。
2. 通过 `com.android.bluetooth` 中注册的 AIDL bridge 写入。
3. 对 LDAC / 采样率尝试写入开发者选项 `Settings.Global`。
4. 最后可尝试 root shell fallback。

LHDC 实时切换更依赖厂商蓝牙栈，模块会先做一次 LHDC priming，再写入目标质量 / 采样率组合。若蓝牙栈拒绝当前组合，模块会尽量自动选择兼容采样率，例如从“均衡 / 48 kHz”切到“音质优先”时先提升到可用采样率。

## 已知边界

- 模块只控制当前已经协商出来的 A2DP / LE Audio 能力，不负责强制把耳机切到另一个完全不支持的协议。
- 不支持 LE Audio 的手机或耳机不会显示 LE Audio 开关。
- 不支持 Hi-Res 或没有对应 Melody 页面项的耳机，会走备用注入点；如果页面结构完全不同，仍可能无法显示。
- 如果蓝牙栈、WirelessSettings 或 Melody 被系统冻结，状态回读可能延迟几秒。
- Melody 大版本更新后建议先看模块日志，不建议直接认定是耳机问题。

## 日志与反馈

调试时建议抓取：

```bash
adb logcat -s MelodyCodecLsp:V
```

反馈问题时请尽量包含：

- 手机型号与系统版本。
- Melody APK 版本号和 APK 文件。
- 耳机型号。
- 当前连接协议，例如 AAC / LDAC / LHDC / LC3。
- 出问题页面截图。
- 完整 `logcat`，尤其是包含 `MelodyCodecLsp` 的部分。

常见关键词：

- `scope.host.start`：Melody 作用域是否加载。
- `onespace.injected` / `detailmain_fallback.injected`：注入是否成功。
- `le.melody.state.recv`：LE Audio 状态是否回传。
- `write.path`：播放质量 / 采样率实际走了哪条写入路径。
- `toast_apply_failed` 或 `切换未生效`：蓝牙栈拒绝了本次写入或回读未确认。

## 构建

本仓库使用 Android Gradle Plugin，目标 Java 17，release 版本暂时关闭 R8 以保留清晰的 Hook 排查路径。

本地构建：

```bash
gradle wrapper --gradle-version 8.10.2
./gradlew :app:assembleRelease
```

输出位置：

```text
app/build/outputs/apk/release/
```

GitHub Actions 会在 `main` / `master` 推送和手动触发时自动构建，并使用仓库 secrets 签名后上传 APK artifact。

## 项目结构

```text
app/src/main/
├── AndroidManifest.xml
├── resources/META-INF/xposed/
│   ├── java_init.list
│   ├── module.prop
│   └── scope.list
├── aidl/xyz/melodylsp/codec/bridge/
└── java/xyz/melodylsp/codec/
    ├── MelodyCodecLspEntry.java
    ├── bt/                 # A2DP 隐藏 API 反射
    ├── bridge/             # AIDL Parcelable 类型
    ├── host/               # Melody 页面注入与 UI 控制
    ├── leaudio/            # LE Audio 状态、IPC 与系统侧桥接
    ├── storage/            # 按耳机保存记忆项
    ├── system/             # com.android.bluetooth 侧 bridge
    ├── ui/                 # 模块总开关 Activity
    └── util/               # 日志
```

## 许可证

本项目使用 Apache-2.0 License。
