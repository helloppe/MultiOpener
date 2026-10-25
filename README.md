# 通用多开器（MultiOpener）

一个免 root 的安卓应用多开 / 隔离工具 + Windows 通用多开器。基于开源项目 [Shelter](https://gitea.angry.im/PeterCxy/Shelter)（GPL-3.0）二次开发。

## 安卓版

利用 Android 官方 **工作资料（Work Profile）** 机制，把同一个应用在"主空间"和"多开空间"各装一份，实现微信、QQ、游戏等应用双开、独立登录、数据隔离。

- 免 root，Android 7.0 及以上
- 无广告、无跟踪 SDK
- 完全开源（GPL-3.0）

### 功能

- 应用双开 / 多开（克隆到工作资料）
- 应用隔离（两个空间数据互不可见）
- 一键批量冻结 / 解冻全部应用
- 定时自动冻结 / 解冻（按你设定的时间点）
- 克隆应用自动改名（如"微信（小号）"）
- 深色主题
- 检查更新（自动对比 GitHub 最新版本）

### 安装

下载 `通用多开器` APK 传到手机安装（需允许"未知来源"）。

首次打开按引导操作：激活设备管理员 → 点击通知完成工作资料创建。

## Windows 版（通用多开器 Windows）

同一 Gradle 工程下的 `windows/` 模块，**Java 21 + Swing** 编写，支持在 Windows 上多开任意软件——微信、QQ、Chrome、游戏都能开多个实例。打包为**程序目录 + zip**（不是单文件 exe，方便分发）。

- 自动检测本机常见软件，也可手动添加任意 .exe
- 两种启动方式：直接启动 / 复制目录启动（适合微信等单实例软件）
- 一键冻结 / 解冻全部多开实例（挂起 / 恢复进程）
- 定时自动冻结 / 解冻
- 检查更新（对比 GitHub 最新版本）
- 深色 / 浅色主题切换

构建好的 zip 在 GitHub Release 中下载（解压后运行 `MultiOpener-Windows.exe`）。

## 从源码构建

环境要求：JDK 21、Android SDK（platforms;android-35、build-tools;35.0.0）、Gradle 8.7。

```bash
# 构建安卓 release APK
gradle assembleRelease

# 构建 Windows 版程序目录（app-image）
gradle :windows:packageWinApp

# 构建 Windows 版 zip 发布包
gradle :windows:packageZip
```

产物：

- 安卓：`app/build/outputs/apk/release/app-release.apk`
- Windows：`windows/build/dist/MultiOpener-Windows/`（程序目录）、`windows/build/dist/MultiOpener-Windows-1.0.0.zip`

## 目录结构

```
app/       安卓应用模块（基于 Shelter）
windows/   Windows 多开器模块（Java + Swing，Gradle 构建）
```

## 开源许可

**GNU GPL v3.0**，见 [LICENSE](LICENSE)
- SetupWizardLibrary 组件库：Apache-2.0（Google AOSP）
