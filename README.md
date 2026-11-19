# EasyClash Android

An Android client that pairs **Clash Meta for Android (CMFA)** with **EasyTier** mesh networking,
running EasyTier in **No-TUN mode** and chaining its SOCKS5 bridge into mihomo's outbound stack.

> 本项目是 [Clash Meta for Android](https://github.com/MetaCubeX/ClashMetaForAndroid) 的个人自用分支，
> 在保留原有代理内核与界面的基础上，集成了 EasyTier 组网能力。非上游官方发布，与 MetaCubeX 无隶属关系。

## 这个分支做了什么

CMFA 默认只使用 mihomo 内核。本项目额外让手机加入一个 EasyTier 私有网络，并把
「远端 SOCKS5 出口」通过 EasyTier 隧道接进 mihomo，形成：

```
应用流量 → mihomo TUN（唯一 VPN 服务）→ 规则 / 策略组
        → 远端 SOCKS5 出站（dialer-proxy: ET-LOCAL）
        → 127.0.0.1:11080（EasyTier 本地 SOCKS5）
        → EasyTier Overlay → 远端出口
```

关键点：Android 同一时间只允许一个 `VpnService` 生效，因此 **EasyTier 以 No-TUN 方式运行**
（不创建 VPN 接口、不调用 `setTunFd`），只提供本地 SOCKS5 入口，由 mihomo 的
`dialer-proxy` 把到远端出口的连接送进隧道。

## 主要特性

- **EasyTier No-TUN 集成**：进程内运行 EasyTier v2.6.4（官方 JNI），本地 SOCKS5 仅监听 `127.0.0.1`
- **mihomo 代理链**：自动注入 `ET-LOCAL`、两个远端 SOCKS5 出站（`dialer-proxy: ET-LOCAL`）与出口选择组
- **自动适配已有配置**：配置里凡是 `server` 落在 EasyTier 网段的代理，自动补 `dialer-proxy`，
  同一份配置在桌面端（EasyTier 有系统路由）与手机端（No-TUN）都能用
- **避免 TUN 自环**：本应用被排除在自身 VPN 之外，并注入最高优先级 `UID,<本应用>,DIRECT` 规则，
  防止 EasyTier 底层连接被自己的 TUN 捕获
- **分层诊断**：TCP 监听 → SOCKS5 协商 → 隧道 CONNECT → 远端认证 → 远端 CONNECT → 出口公网 IP，
  每一阶段独立报错，不把"网络不可达"当作唯一结论
- **状态机与退避**：启动/等待/检查/已连接/降级/重连，指数退避上限 32s；仅在实例真正消失时重启
- **敏感配置加密**：network-secret 与 SOCKS5 密码经 Android Keystore（AES-GCM）加密存储，
  运行配置只在内存中注入，不落盘、不写日志

## 环境要求

- Android 7.0+（推荐），无 Root
- EasyTier 原生库当前提供 `arm64-v8a`（真机）与 `x86_64`（模拟器）；其他 ABI 需自行构建
- 需要可用的 EasyTier 网络（network name / network-secret + 至少一个 peer 入口）

## 构建

1. 初始化子模块

   ```bash
   git submodule update --init --recursive
   ```

2. 准备工具链

   - **JDK 21**（Gradle 8.10.2 需要）
   - **Android SDK**（compileSdk 35）
   - **NDK 29.0.14206865**、**CMake 3.22.1**
   - **Go 1.20+**（编译 mihomo 内核）

3. 在项目根目录创建 `local.properties`

   ```properties
   sdk.dir=/path/to/android-sdk

   # 独立包名，可与原版 CMFA 共存
   custom.application.id=com.example.easyclash
   remove.suffix=true
   ```

4. 构建 Debug 变体

   ```bash
   ./gradlew app:assembleAlphaDebug
   ```

   产物：`app/build/outputs/apk/alpha/debug/`

5. 发布构建（可选）：在 `signing.properties` 中配置 keystore 后执行

   ```bash
   ./gradlew app:assembleAlphaRelease
   ```

### 原生库（EasyTier）

仓库已内置预编译的 `libeasytier_android_jni.so`（`core/src/main/jniLibs/<abi>/`），
**构建 APK 不需要 Rust 工具链**。仅当升级 EasyTier 版本或新增 ABI 时才需要重新生成：

```bash
# 需要 Rust 1.95（EasyTier 工具链要求）+ cargo-ndk + protoc + NDK
EASYTIER_DIR=/path/to/EasyTier ./script/build-easytier-jni.sh            # arm64-v8a
EASYTIER_DIR=/path/to/EasyTier ./script/build-easytier-jni.sh x86_64     # 追加 x86_64
```

说明：上游通用的「JNI 库 + FFI 库」两个 `.so` 方案在 Android 上不可用
（`System.loadLibrary` 使用 `RTLD_LOCAL`，先加载的库符号对后加载的库不可见）。
本项目的构建脚本会把 FFI 实现静态合并进 JNI 库，只产出一个自包含的 `.so`。
云端构建见 `.github/workflows/build-easytier-jni.yml`。

## 应用内配置

设置 → **EasyTier 网络**：

| 字段 | 说明 |
| --- | --- |
| 启用 EasyTier | 开关；修改后需重启 VPN 服务 |
| 网络名称 / 网络密钥 | 由 EasyTier 网络提供；密钥经 Keystore 加密保存 |
| 主机名 / 虚拟 IPv4 | 本机在 overlay 中的身份，IP 需与现有网段一致 |
| Peer URI | 完整 URI（如 `tcp://host:11010`），可多条 |
| 本地 SOCKS5 端口 | 默认 `11080`，仅绑定 `127.0.0.1` |
| 远端出口 | 主机、两个 SOCKS5 端口及各自的用户名/密码 |

设置 → EasyTier → **查看诊断日志**：按 `NATIVE / CONFIG / RUNTIME / SOCKS5 / MIHOMO` 分类，
可一键复制（导出内容自动脱敏）。

## 代码结构

| 位置 | 职责 |
| --- | --- |
| `core/src/main/java/com/easytier/jni/` | 官方 JNI 绑定（包名与导出符号绑定，勿改） |
| `core/.../core/easytier/` | 原生桥接、状态 JSON 容错解析 |
| `core/src/main/golang/native/config/easytier_patch.go` | mihomo 配置合并注入（代理/策略组/规则/dialer-proxy 适配） |
| `service/.../service/easytier/` | 配置与加密存储、分层健康检查、状态机、诊断 |
| `service/.../clash/module/EasyTierModule.kt` | 服务编排（启动顺序、状态广播、诊断查询） |
| `app/.../EasyTierSettingsActivity.kt` | 设置页 |
| `design/.../EasyTierSettingsDesign.kt` | 设置页 UI（preference DSL） |
| `script/build-easytier-jni.sh` | 原生库构建（含合并补丁与符号校验） |

## 测试

```bash
./gradlew :service:testAlphaDebugUnitTest
```

覆盖：TOML 生成与顺序、SOCKS5 协议分层（含伪服务器）、状态 JSON 解析（真实抓包数据）、
注入契约、诊断脱敏。

## 外部控制

包名与 action 前缀均跟随 `applicationId`（默认由 `custom.application.id` 决定）：

```bash
adb shell am start -a <applicationId>.action.START_CLASH \
  -n <applicationId>/com.github.kr328.clash.ExternalControlActivity
```

`START_CLASH` / `STOP_CLASH` / `TOGGLE_CLASH` 均可用；导入配置使用
`clash://install-config?url=<encoded url>`。

## 已知限制

- EasyTier 目前仅提供 arm64-v8a / x86_64 原生库
- 远端 SOCKS5 的 UDP 转发未做专项验证（TCP 已覆盖）
- 未实现自动故障切换（当前为手动选择出口组）

## 许可证与致谢

本项目沿用上游 **GPL-3.0** 许可，详见 [LICENSE](LICENSE) 与 [NOTICE](NOTICE)。

- [Clash Meta for Android](https://github.com/MetaCubeX/ClashMetaForAndroid) — 应用骨架与 UI（GPL-3.0）
- [mihomo](https://github.com/MetaCubeX/mihomo) — 代理内核（GPL-3.0）
- [EasyTier](https://github.com/EasyTier/EasyTier) — 组网内核与 Android JNI（Apache-2.0）

本项目为个人自用与学习用途，请自行确认使用场景符合所在地法律法规。
