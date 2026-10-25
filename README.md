# 通用多开器（MultiOpener）

一个免 root 的安卓应用多开 / 隔离工具。基于开源项目 [Shelter](https://gitea.angry.im/PeterCxy/Shelter)（GPL-3.0）二次开发。

利用 Android 官方 **工作资料（Work Profile）** 机制，把同一个应用在"主空间"和"多开空间"各装一份，实现微信、QQ、游戏等应用双开、独立登录、数据隔离。

- 免 root，Android 7.0 及以上
- 无广告、无跟踪 SDK
- 完全开源（GPL-3.0）

## 功能

- 应用双开 / 多开（克隆到工作资料）
- 应用隔离（两个空间数据互不可见）
- 冻结应用（省电、防后台唤醒）
- 文件穿梭（可选，两个空间间传文件）
- 批量冻结 / 快捷方式

## 安装

下载 `通用多开器` 传到手机安装（需允许"未知来源"）。

首次打开按引导操作：激活设备管理员 → 点击通知完成工作资料创建。

## 从源码构建

环境要求：JDK 17+、Android SDK（platforms;android-35、build-tools;35.0.0）、Gradle 8.7。

```bash
# 拉取子模块
git 子模块更新 --初始化

# 构建 release APK
gradle assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`

> Windows 命令行一键构建可参考仓库内 `build.bat`。

## Windows 支持

本项目开发与构建环境即为 **Windows 10/11**，完全支持在 Windows 上开发、构建和测试。

**命令行构建（无需 Android Studio）**

```bat
:: 双击运行仓库内的 build.bat
build.bat
```

`build.bat` 会自动使用本机 JDK 21 + Gradle 8.7 + Android SDK 构建，产物为 `app\build\outputs\apk\release\app-release.apk`。

**在 Windows 上用模拟器测试 APK（不用手机也能试）**

1. 安装任意安卓模拟器（推荐 MuMu 模拟器 12 / 夜神 / 蓝叠，官网免费下载）。
2. 把构建好的 APK 文件拖进模拟器窗口，或点模拟器里的"安装 APK"按钮选择文件。
3. 在模拟器里打开"通用多开器"，按引导完成工作资料初始化即可体验全部功能（模拟器版本建议 Android 9 及以上）。

## 开源许可

**GNU GPL v3.0**，见 [LICENSE](LICENSE)
- SetupWizardLibrary 组件库：Apache-2.0（Google AOSP）
  
