# VSP 解析 · 视频 / 音频 / 图片一键解析

一个把「在线解析 → 下载 → 本地播放 / 处理」串成一条链的工具，含 **Android app**、**Web 前端**、**Web 后端** 三部分。

- 最新版本：**v1.0.0**（versionCode 9）
- 安装包下载：[Releases](https://github.com/tang536/VSP-/releases/latest)
- 平台：Android 7.0+（minSdk 24 / targetSdk 36，arm64-v8a）

## 功能

### 解析与下载
- 基于 yt-dlp 解析主流站点，另外内置 HTML 兜底解析：抓页面 → 反转义 `\/`、`\u002F`、`&amp;` → 正则提取 `m3u8 / mp4 / m4v / webm / flv` → 跟随一层 iframe → 解析 JSON-LD `contentUrl`
- 相对地址会连查询串一起拼回，避免丢失签名参数导致 403
- 直链用 `url:` 前缀编码进 `formatId`，预览与下载共用同一条地址
- 支持批量下载、下载队列、暂停 / 继续、下载文件管理与一键打开下载目录

### 观看（对齐系统相册 / 系统播放器）
- 点视频 / 音频 / 图片直接全屏观看，纯黑底 + 细线白图标
- 播放器：底部横向进度条（当前时间 / 滑杆 / 总时长）、「播放 / 倍速 / 音量 / 横竖屏切换」一行控制
- 顶部细线返回 + 文件名 + 分辨率·体积副标题，按状态栏与刘海避让，视频播放时随控制条自动隐藏
- 图片：底部缩略图条（同批图片可切换）+ 分享 / 信息 / 删除 / 更多
- 音量滑杆默认收起，点音量图标才展开；倍速 0.5x ~ 2x
- 图片支持双指缩放、双击放大 / 还原、放大后单指拖动
- 支持 `content://` 源（MediaStore / SAF 选中的文件都能播）

### 本地媒体
- MediaStore 扫描手机内全部视频 + 音频 + 图片，扫描页带进度与已扫计数
- 扫描完全手动触发：切换扫描目录 / 修改过滤只更新范围，不会自动开扫
- 扫描过滤可分别开关三类，并可设置「每次扫描只保留本次结果」
- 自定义扫描走 SAF 目录树，可指定任意文件夹
- 本地视频一行三个网格，卡片带时长角标，可一键「提取配乐」
- 扫描结果与解析结果本地持久化，退出重开、覆盖升级后自动恢复

### 音视频处理
- 音视频无损合并、视频提取配乐：`MediaExtractor + MediaMuxer`，效果等价 `ffmpeg -c copy`
  （官方 ffmpeg-kit 已停止分发，故改用系统能力，不引入第三方二进制）

### 其他
- 检查更新：**GitHub + Gitee 双源**，同时查、取版本更高的那个；已是最新时只在顶部滑出轻提示
- 下载走自建前台服务：通知栏显示进度条 + 已下载/总大小 + **实时速度**，可直接取消；应用内弹窗同步
- 下载期间禁止重复检查与重复下载，退出重进可接续；完成后校验体积再拉起系统安装器
- 更新源：https://github.com/tang536/VSP- ｜ https://gitee.com/tang-rongqiang/VSP
- 深浅主题、封面缩略图、历史记录、方形头像与大图查看

## 项目结构

```
VSP解析/
├── apk/                            Android 工程（Gradle + Java + Chaquopy）
│   ├── app/src/main/java/com/vsp/app/
│   │   ├── MainActivity.java        单 Activity，多页面 View 切换
│   │   └── MediaUtil.java           MediaExtractor + MediaMuxer 封装
│   ├── app/src/main/python/
│   │   ├── vsp.py                   解析核心（yt-dlp + HTML 兜底）
│   │   └── server.py                内置 Web 服务的 /api/parse
│   ├── app/src/main/res/            布局 / 图标 / 主题
│   └── 签名与打包说明.txt            证书、打包命令、发布到 Release 的完整步骤
├── web/
│   ├── front/                      Vite + React + TypeScript
│   └── back/                       ASP.NET Core（net10.0）
└── .gitignore
```

## 从源码构建

### Android
```bash
cd apk
export ANDROID_HOME="$HOME/AppData/Local/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
./gradlew :app:assembleRelease
```
Chaquopy 需要本机 Python 3.12，并把它放到 `PATH` 前面（或用 `-Pchaquopy.buildPython=<python.exe>` 指定），否则会报 `Could not find Python 3.12`。

签名需要自备 `apk/app/vsp-release.keystore` 与 `apk/app/keystore.properties`（**不入库**），格式见 `apk/签名与打包说明.txt`。

### Web 前端
```bash
cd web/front
npm install
npm run dev      # 开发
npm run build    # 产出 dist/
```

### Web 后端
```bash
cd web/back
dotnet run
```

## 版本

| 版本 | 主要内容 |
|---|---|
| v1.6 | 修复 B 站/抖音解析（桌面 UA + 站点 Referer + 抖音分享页解析）、播放器与图片查看器改系统相册风格、Gitee 更新源、通知栏下载速度与大小、头像原图保存与全屏观看、修复缓存始终为 0 |
| v1.5 | 全新启动图标（自适应）、点条目全屏观看 + backspace 退出、图片缩放、音量图标展开、扫描手动化与只保留新结果、扫描/解析结果持久化、更新下载进度与取消 |
| v1.4.1 | 检查更新真正接入 GitHub Releases、下载完整性校验、弹窗信息补全 |
| v1.4 | 播放器控制条、全屏播放、解析兜底、扫描页、存储三行布局、音视频合并 |
| v1.3 | 方形头像与大图查看、指定扫描区域、播放控制浮层 |
| v1.2 及更早 | 封面缩略图、历史弹窗、深浅主题、内置播放器、批量下载 |

## 说明

本项目仅用于学习与个人使用。请遵守目标站点的服务条款与当地法律法规，不要用于侵权用途。
