<p align="center">
  <img src="docs/images/banner.svg" alt="IdeaSerial" width="720"/>
</p>

# IdeaSerial · 灵思串口

[![Release](https://img.shields.io/github/v/release/ideastudios/IdeaSerial?display_name=tag)](https://github.com/ideastudios/IdeaSerial/releases)
[![Build](https://github.com/ideastudios/IdeaSerial/actions/workflows/build.yml/badge.svg)](https://github.com/ideastudios/IdeaSerial/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
![Platform](https://img.shields.io/badge/platform-Android%205.0%2B-3DDC84)

[中文](README.md) | [English](README_EN.md)

> Android 多串口调试工具：板载串口（root）与 USB OTG 串口（免 root）双模式，4 路串口同屏收发，TX/RX 着色日志。

## ✨ 功能特性

| 功能 | 说明 |
|---|---|
| 🔌 双驱动串口 | 板载 `/dev/ttyS*`（root）+ USB OTG（CH340/CH341、CP210x、FTDI、PL2303、CDC/ACM，免 root） |
| 🗂 4 路串口标签页 | COM A~D 各占一页，后台持续收数，未读帧数徽标提示 |
| ⚙️ 全参数可配 | 波特率 50~4000000、数据位 5-8、停止位 1/2、校验（无/奇/偶/标/空）、流控（RTS/CTS、XON/XOFF） |
| 🎨 TX/RX 着色日志 | 发送青色、接收默认色，毫秒级时间戳，长按复制/分享 |
| ⚡ 快捷命令库 | 自定义命名命令，点按发送、长按编辑，支持 Hex 与自动换行 |
| 🔁 自动发送 | 逐口独立开关与间隔（ms），内容随输入实时生效 |
| 📈 字节计数 | 每路串口 RX/TX 字节数实时统计 |
| 📄 发送文件 | 任意文件分块发送（块大小 + 间隔可配），进度可取消 |
| 💾 日志导出 | 当前页日志一键保存为 txt |
| ⚡ 高波特率优化 | 阻塞读 + 批量刷新，921600+ 不丢帧 |
| 🌓 深色模式 | Material 3 DayNight，Android 13+ 支持单色主题图标 |

## 📥 下载安装

前往 [Releases](https://github.com/ideastudios/IdeaSerial/releases) 下载 `IdeaSerial-vX.Y.Z.apk`（附 SHA256 校验文件），侧载安装。

也可以在 [Actions](https://github.com/ideastudios/IdeaSerial/actions) 页面下载每次提交的 CI 构建。

## 🖼 界面预览

> 真机截图待补充（欢迎 PR）。

## 🛠 从源码构建

环境要求：Android Studio Chipmunk+（AGP 7.1）、JDK 17、NDK 与 CMake 3.18.1（SDK Manager 安装）、MinSdk 28 / TargetSdk 34。

```bash
git clone https://github.com/ideastudios/IdeaSerial.git
cd IdeaSerial
./gradlew assembleDebug        # Debug 构建
./gradlew testDebugUnitTest    # 单元测试
```

签名版 Release 构建：在项目根目录创建 `keystore.properties`（已被 .gitignore 排除，勿提交）：

```properties
storeFile=../your-keystore.jks
storePassword=xxx
keyAlias=your-alias
keyPassword=xxx
```

## 🔐 权限说明

- **USB 串口**：免 root。插入 USB 转串口设备后系统会弹出授权，允许即可；部分手机需开启 OTG。
- **板载串口**：需要 root。应用会尝试通过 `su` 对设备节点 `chmod 666`；未 root 的设备请改用 USB 模式。

## ❓ FAQ

**Q：打开串口提示"没有串口读/写权限"？**
板载串口需要 root 权限，请确认设备已 root 并在 superuser 弹窗中放行；否则请使用 USB 串口模式。

**Q：为什么桌面图标/名称没变化？**
覆盖安装不会刷新桌面图标缓存，请先卸载旧版再安装。

**Q：配置存在哪里？会不会丢？**
配置以 JSON 形式存于应用私有目录，升级自动保留；v0.1.0 起弃用了旧的 Java 序列化格式。

## 🙏 致谢

- [android-serialport-api](https://code.google.com/archive/p/android-serialport-api/)（Cedric Priscal，Apache License 2.0）——串口 JNI 基础
- [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android)——USB OTG 串口驱动
- benjaminwan 的 ComAssistant——界面交互原型参考

## 🗺 路线图

见 [docs/ROADMAP.md](docs/ROADMAP.md)。

## 📄 License

[MIT](LICENSE) © IdeaSerial Contributors
