# VSP 解析 · 视频 / 音频 / 图片一键解析

把「在线解析 → 下载 → 本地观看 / 处理」串成一条链的工具，含 **Android app**、**Web 前端**、**Web 后端** 三部分。

- 当前版本：**v1.0.0**（versionCode 9）
- 安装包：[GitHub Releases](https://github.com/tang536/VSP-/releases/latest) ｜ [Gitee Releases](https://gitee.com/tang-rongqiang/VSP/releases/latest)
- 平台：Android 7.0+（minSdk 24 / targetSdk 36，arm64-v8a）

## 功能

### 解析

- **B 站**：走 `playurl` 的合流接口（`fnval=1`），直接拿带音轨的 720P mp4，附备用线路。
  不用 yt-dlp 的 DASH 是因为它把画面和音轨拆成两个文件，只播画面流就是「有画面没声音」
- **抖音**：解析分享页 `_ROUTER_DATA` 里的 `play_addr`，`playwm` 自动换成 `play` 去水印；
  支持图集与原声；`v.douyin.com` 短链先跟随跳转再识别作品
- **通用**：yt-dlp 兜底（桌面 UA + 按站点补 Referer），失败再走 HTML 兜底：
  抓页面 → 反转义 `\/`、`\u002F`、`&amp;` → 正则提取 `m3u8 / mp4 / m4v / webm / flv` → 跟随一层 iframe → 解析 JSON-LD `contentUrl`
- 相对地址连查询串一起拼回，避免丢签名参数导致 403
- 直链用 `url:` 前缀编码进 `formatId`，预览与下载共用同一条地址

### 下载

- 下载队列显示**已下载 / 总大小**与**实时速度**，可暂停 / 继续 / 取消
- 批量下载、下载文件管理、一键打开下载目录
- 音视频分离的流下载完自动合并：`MediaExtractor + MediaMuxer`，等价 `ffmpeg -c copy`，
  不解码不转码（官方 ffmpeg-kit 已停止分发，故用系统能力，不引入第三方二进制）
- 视频一键提取配乐

### 观看（对齐系统相册 / 系统播放器）

- 点视频 / 音频 / 图片直接全屏观看，纯黑底 + 细线白图标
- 进度条是系统播放器那种底部横排：当前时间 / 滑杆 / 总时长
- 控制行：播放暂停、倍速（0.5x ~ 2x）、音量（点图标才展开滑杆）、**横竖屏切换**
- 顶部返回键 + 文件名 + 分辨率·体积副标题，**按状态栏与刘海的实际高度避让**，
  播放时随控制条一起自动隐藏，点画面唤出
- 图片：单击在「干净看图」与「带标题 + 缩略图条」之间切换，3.5 秒自动收起；
  底部缩略图条可切换同批图片，操作只有分享 / 信息 / 删除
- 图片支持双指缩放、双击放大还原、放大后单指拖动，大图按 2400px 采样解码
- 头像：点击全屏观看（原图不压缩），**长按**弹出更换 / 查看 / 恢复默认
- 支持 `content://` 源（MediaStore / SAF 选中的文件都能播）

### 本地媒体

- MediaStore 扫描手机内全部视频 / 音频 / 图片，扫描页带进度与已扫计数
- 扫描完全手动触发：换目录、改过滤只更新范围，不会自动开扫
- 过滤可分别开关三类，并可设置「每次扫描只保留本次结果」
- 自定义扫描走 SAF 目录树，可指定任意文件夹
- 本地视频一行三个网格，卡片带时长角标
- 扫描结果与解析结果本地持久化，退出重开、覆盖升级后自动恢复

### 其它

- 检查更新：**GitHub + Gitee 双源**同时查、取版本更高的那个；已是最新只在顶部滑出轻提示
- 更新下载走自建前台服务：通知栏有进度条 + 已下载/总大小 + 实时速度 + 取消；
  应用内弹窗同步显示；下载期间不允许重复检查与重复下载
- 深浅主题、封面缩略图（内存 → 磁盘三级缓存）、历史记录、存储管理

## 项目结构

```
VSP解析/
├── apk/                             Android 工程（Gradle + Java + Chaquopy）
│   ├── app/src/main/java/com/vsp/app/
│   │   ├── MainActivity.java         单 Activity，多页面 View 切换 + 全屏观看层
│   │   ├── UpdateService.java        更新下载前台服务（进度 / 大小 / 速度 / 取消）
│   │   └── MediaUtil.java            MediaExtractor + MediaMuxer 封装
│   ├── app/src/main/python/
│   │   ├── vsp.py                    解析核心（B 站合流 / 抖音 / yt-dlp / HTML 兜底）
│   │   └── server.py                 内置 Web 服务的 /api/parse
│   ├── app/src/main/res/             布局 / 图标 / 主题
│   ├── dist/latest.json              Gitee 兜底更新索引
│   └── 签名与打包说明.txt             证书、打包、双平台发布步骤
├── web/
│   ├── front/                       Vite + React + TypeScript
│   └── back/                        ASP.NET Core（net10.0）
└── .gitignore
```

## 从源码构建

### Android

```bash
cd apk
export ANDROID_HOME="$HOME/AppData/Local/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

# Chaquopy 只认 PATH 上的 Python，必须是 3.12
export PATH="/path/to/python3.12:$PATH"

gradle :app:assembleRelease
```

- Chaquopy 需要本机 **Python 3.12** 来安装 requirements（yt-dlp）。把它放到 `PATH` 最前面，
  否则会报 `Couldn't find Python 3.12`。注意 `chaquopy.buildPython` 属性在本机实测不生效
- 签名需要自备 `apk/app/vsp-release.keystore` 与 `apk/app/keystore.properties`（**不入库**），
  格式与发布流程见 `apk/签名与打包说明.txt`

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

## 更新源

| 源 | 地址 | 用途 |
|---|---|---|
| GitHub | https://github.com/tang536/VSP- | 主仓库、源码与 Release |
| Gitee | https://gitee.com/tang-rongqiang/VSP | 国内镜像、更新源、下载更快 |

app 内检查更新同时查两边，取版本更高的那个；Gitee 侧没有 Release 时会读
`dist/latest.json` 兜底。发版时两个仓库都要同步 Release 与附件。

## 版本

| 版本 | 主要内容 |
|---|---|
| **v1.0.0** | 首发：B 站合流解析修复无声、播放器不再误调 PlaybackParams、按站点补 Referer、观看层避开状态栏与刘海、底部横向进度条、横竖屏切换、标题自动隐藏、头像长按菜单、下载队列显示大小与速度 |

## 说明

本项目仅用于学习与个人使用。解析与下载请遵守目标站点的服务条款与当地法律法规，不要用于侵权用途。
