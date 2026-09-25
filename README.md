# 车机桌面 · CarDesktop 🐯

横屏车机桌面：左侧竖排控制钮 + 速度 / 天气 / 音乐三张卡 + 右侧地图分屏。
界面是一份单文件 HTML，安卓侧只做了一层很薄的 WebView 壳。

```
┌──────────────────────────────────────────────────────────────┐
│ 14:58  2026年9月25日 星期五   …        已连接·酷我音乐  ▲  分屏比例 设置 自动 │
├──┬────────────────────────┬──────────────────────────────────┤
│⊕ │        0     km/h      │                                  │
│⊖ │  [导航]                │        地图区（可切高德）          │
│🔍│ ────────────────────── │   ┌──────────────┐               │
│⊙ │ 32℃  57%  33m  西北29   │   │ ↰ 70米进入    │               │
│🎧│ ────────────────────── │   │   永富街      │               │
│⚙ │ 2002年的第一场雪 - 刀郎  │   │ 2.2km·6min 15:03到│            │
│  │ 词：刀郎  曲 刀郎        │   └──────────────┘               │
│  │      歌词滚动…          │                                  │
│  │ ◀ ▶ ■ ▶      [本地]     │                      200m ── 29 │
└──┴────────────────────────┴──────────────────────────────────┘
```

## 目录

| 路径 | 说明 |
|---|---|
| `index.html` | **整个界面**，唯一源文件（CSS/JS 全内联，零外部依赖） |
| `app/` | Android 壳：WebView 容器、JS 桥、开机自启 |
| `.github/workflows/` | 云端编译：push 出 debug 包，打 tag 出签名 release 包 |
| `preview-1024x600.png` | 界面效果图 |

## 装到车机上

1. 下载 Releases 里的 `CarDesktop-release.apk`
2. 拷到车机安装（U 盘或 `adb install`）
3. 打开即是桌面。想让它当**主屏**：车机「设置 → 默认应用 → 主屏幕应用」选「车机桌面」

> 若设成主屏后发现界面有问题：设置面板右下角有「系统设置」按钮，
> 能进系统设置把主屏改回原桌面 —— 这是故意留的逃生通道。

## 关于「32 位」

本工程**没有一行 native 代码，也没有带 `.so` 的依赖**，所以打出来的是
**全架构通用包**，32 位（armeabi-v7a）车机原生可直接安装，不需要额外做 ABI 适配。

CI 里有一道自检会打印 `native-code` 字段：为空即代表全架构通吃。
如果哪天引入了带 `.so` 的库（地图 SDK、音视频解码等），必须在
`app/build.gradle.kts` 的 `defaultConfig` 里显式加上：

```kotlin
ndk { abiFilters += "armeabi-v7a" }   // 32 位车机
```

否则 APK 会缺 32 位 so —— 装得上，一调用就 `UnsatisfiedLinkError`。

## 改界面

改仓库根目录的 `index.html` 即可，构建时会自动复制进 `app/src/main/assets/`
（`.gitignore` 已排除该生成物，杜绝两份文件不同步）。

- **不重新打包也能看效果**：双击 `index.html` 用浏览器打开（会自动退化成
  `localStorage` 存配置、无原生桥），改一行刷新一下就行
- **要真机生效**：重新编译 APK 装上

兼容性：按 Android 4.4+ 系统 WebView（Chrome 33 内核）标准写 ——
不用 CSS 变量 / `flex gap` / `grid` / `clamp` / ES6 语法，字号统一走 `vh`，
1024×600、1280×720 等分辨率自动等比铺满。你的车机是 Android 8，余量充足。

## 与安卓侧通信（`window.CarBridge`）

网页里所有原生能力都走这个对象，浏览器里不存在时会自动降级为页面提示，不会报错。

| 方法 | 作用 |
|---|---|
| `getPref(key)` / `setPref(key, value)` | 配置持久化（SharedPreferences） |
| `media("prev"\|"play"\|"stop"\|"next")` | 发系统媒体键 → 控制酷我等播放器 |
| `setVolume(±1)` | 调媒体音量 |
| `openApp("包名")` | 拉起导航 / 音乐 / 系统设置 |
| `toast(msg)` | 原生 Toast |

配置存在 `SharedPreferences("cardesk")` 的 `cardesk_cfg` 键里（JSON），
`BootReceiver` 也读同一份来决定开机是否自启 —— 界面开关和真实行为不会脱节。

## 待接入的真实数据

| 位置 | 现状 | 接法 |
|---|---|---|
| 天气 | 假数据（32℃ / 57% / 33m / 西北29） | 搜 `[WEATHER]`，用 XHR 访风和天气 |
| 车速 | 点数字演示巡航 | 安卓侧 GPS 每秒调 `window.onGpsSpeed(kmh)` |
| 地图 | 内置 Canvas 示意地图 | 设置面板填高德 key（搜 `[AMAP]`） |
| 音乐信息 | 静态歌名歌词 | 需要读第三方播放器的 MediaSession 元数据 |

## 高德地图 key 注意事项

设置面板 → 地图源选「高德 JS」，填入 **[Web端(JS API)]** 类型的 key。
两类常见失败：

- **`INVALID_USER_DOMAIN`**：key 设了域名白名单。WebView 的页面 origin 是
  `https://cardesk.local/`（壳里写死的假域名），要么把它加进白名单，要么在控制台取消限制。
- 加载失败会自动回退到内置示意地图，不会白屏。

## 构建

推 `main` 触发 debug 编译，打 `v*` tag 触发签名 release 编译并自动发 Release。
签名密钥走仓库 Secrets（`KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`），
keystore 本身永不入库。

本地无 Android SDK 也能改界面，但**要验证真编译必须走 CI** —— 静态检查替代不了真实编译。
