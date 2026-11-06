# 桌面端改造方案（Electron，待开工）

> 分支：`feature/desktop-app`（从 `main@ee64b13` 切出）
> 状态：**方案已定稿，未开始改造**
> 原则：后端只加「可配置」开关，不引入桌面耦合；现有 Web 形态继续可用，两条路径共用一份后端。

---

## 1. 目标

把现在「浏览器访问 http://localhost:39217」的形态，变成**双击即用的 Windows 桌面应用**：

- 双击图标 → 独立窗口（不是浏览器标签页）→ 直接可用
- 自带 Java 运行时，用户机器不需要装 JDK / Maven
- 关窗驻留托盘，托盘「退出」才真正结束；不留孤儿进程
- 数据在程序目录 `data\`，整个文件夹可拷走

**不在本次范围**：移动端、原生重写界面、多用户、Linux/macOS 打包、开机自启、自动更新。

---

## 2. 现状（改造起点）

| 项 | 现状 |
|---|---|
| 后端 | Spring Boot 3.4.2，fat jar **107MB**，端口 `39217` |
| 前端 | Vue 3 + Vite，产物打进 jar 的 `static/` |
| 运行时 | 便携 JDK 17（355MB，jlink 可裁到 ~70MB） |
| 数据 | `D:\KnowledgeBase\.kb`（1.4G），目录由 `AppPaths` 向上探测 `.kb` 决定 |
| 启动 | `启动知识库.bat` → `mvn spring-boot:run`（需 JDK+Maven） |
| 本机工具链 | Node 22.22 / npm 10.9 ✅（Electron 可直接用）、WebView2 156 ✅ |

**关键约束**：`AppPaths.detectRoot()` 靠「工作目录向上找 `.kb`」定位数据。打包后工作目录变成安装目录，必须做成**可配置**，否则会新建空库、看起来像数据丢失。

---

## 3. 形态选型（已定：B2 Electron）

| 方案 | 产物 | 体积 | 体验 | 依赖 |
|---|---|---|---|---|
| A. 绿色版（jpackage） | 文件夹 + exe | ~180MB | 仍开浏览器 | 无 |
| **B2 Electron（选定）** | 安装包 exe | **~330MB 安装后 / ~200MB 包体** | 独立窗口、托盘 | 现有 Node 22 |
| B1 Tauri（备选） | 安装包 exe | ~190MB | 同上，内存更低 | 需装 Rust（2GB） |

选 Electron 的理由：**零额外工具链**（Node 已就绪）、生态成熟、托盘/单实例都有现成方案；代价是体积与内存偏高，可接受。

---

## 4. 架构

```
┌──────────────────────────────────────────────────┐
│ Electron 主进程（单实例锁）                        │
│  ├─ 选端口（39300 起探测空闲，写 .port 文件）        │
│  ├─ spawn sidecar：                                │
│  │    resources\jre\bin\javaw.exe -jar kb.jar      │
│  │      -Dkb.home=<程序目录>                        │
│  │      --server.port=<port> --kb.quiet            │
│  ├─ 轮询 /api/health 直到 200（≤20s，超时提示重试）  │
│  ├─ BrowserWindow → http://127.0.0.1:<port>        │
│  ├─ 关窗 → 隐藏到托盘（不退出）                      │
│  ├─ 托盘：打开主界面 / 退出                          │
│  └─ 退出：先礼后兵（HTTP 关闭 → 5s 后 kill 进程树）  │
└──────────────────────────────────────────────────┘
              │ HTTP / SSE（仅回环）
┌─────────────▼────────────────────────────────────┐
│ Spring Boot（现有代码 + 3 个开关）                 │
│  数据目录可配 / 端口可配 / 静默模式                 │
└──────────────────────────────────────────────────┘
```

**前端不重写**：仍是现有 Vue SPA，由 Spring Boot 提供；Electron 只是把它装进窗口。

---

## 5. 改造清单

### 5.1 后端（约 80 行，向后兼容）

| 改动 | 说明 |
|---|---|
| `AppPaths` 支持显式数据目录 | 优先级：`-Dkb.home` → 环境变量 `KB_HOME` → 程序目录的 `kb-home.txt` → 旧上溯探测 → `<程序目录>\data` |
| `KbServerApplication` 单实例 | 端口已有本应用响应（`/api/health`）则不再重复启动 |
| `--kb.quiet` | 桌面壳内启动时不调用 `Desktop.browse` |
| `/api/health` | 返回 `{ok, version, dataDir, port}`，供壳判断「服务就绪」并核对数据位置 |
| 仅监听回环 | `server.address=127.0.0.1`，不暴露到局域网 |
| 优雅退出 | 关闭钩子确保 SQLite（WAL）与 Lucene 正常落盘 |

### 5.2 前端（约 30 行）

| 改动 | 说明 |
|---|---|
| 桌面标识 | 壳注入 `window.__DESKTOP__=true`（preload） |
| 外链 | `target=_blank` / `window.open` 交给 Electron `shell.openExternal` 用系统浏览器打开 |
| 断连提示 | 壳内检测 sidecar 断开 → 顶部条「服务已断开，正在重启…」 |
| 标题栏 | 首版用系统标题栏（零成本），二期可换自绘 |

### 5.3 壳工程（新增目录，不影响现有构建）

```
desktop/
├─ package.json              # electron + electron-builder
├─ main.js                   # 主进程：端口/启动/健康检查/窗口/托盘/退出
├─ preload.js                # 注入 __DESKTOP__、暴露 openExternal
├─ icons/icon.ico            # 从 web 图标生成
├─ resources/                # 打包时注入：kb.jar + jre\（不进 git）
└─ scripts/
   ├─ build-runtime.ps1      # jlink 裁剪 JRE
   └─ build-desktop.ps1      # 一键：web 构建 → jar → jlink → electron-builder
```

**打包配置要点（electron-builder）**

- `extraResources`: `kb.jar` + `jre/` → 安装目录 `resources/`
- `nsis`: 一键安装、`perMachine: false`（装到用户目录，避开 Program Files 权限问题）
- `asar: true`，但 `resources/` 外置（jar 与 jre 不进 asar）
- 安装目录默认 `%LOCALAPPDATA%\Programs\知识库`

### 5.4 数据位置与迁移

- 数据目录：`<安装目录>\data\.kb`（绿色便携，整包可拷走）
- 首次启动：若 `data\.kb` 不存在，检测旧目录（`D:\KnowledgeBase\.kb`）→ 弹窗「迁移现有数据 / 使用全新空库」，**默认复制而非移动**，复制前打 zip 备份
- 迁移后校验：会话数、笔记数、知识库数、索引 chunk 数四项比对
- 允许 `kb-home.txt` 覆盖（把数据指到移动硬盘）

### 5.5 体积预算

| 组成 | 体积 |
|---|---|
| Spring Boot jar | 107MB |
| jlink 精简 JRE | ~70MB |
| Electron 运行时 | ~150MB |
| **安装后合计** | **≈330MB**，NSIS 包体约 200MB |

---

## 6. 阶段计划与验收

| 阶段 | 内容 | 验收标准 | 估时 |
|---|---|---|---|
| **P0** | 后端三个开关 + `/api/health` + 单实例 | `java -Dkb.home=E:\t -jar kb.jar --kb.quiet` → 数据落在 `E:\t\.kb`、不弹浏览器；重复启动只有一个实例 | 0.5d |
| **P1** | Electron 壳 + 生命周期 | 双击 → 窗口出现可问答；关窗进托盘、进程在；托盘退出 → java 进程消失；端口占用自动换端口 | 0.5~1d |
| **P2** | 打包安装包 | 未装 Java 的机器双击安装 → 启动 → 完整问答；体积 ≤350MB | 0.5d |
| **P3** | 托盘与体验 | 托盘最小化/恢复/退出菜单、崩溃自动重启、外链走系统浏览器 | 0.5d |
| **P4** | 数据迁移与回归 | 迁移 1.4G 数据；四项计数一致；备份/索引重建/拖拽/导出在桌面版全通 | 0.5d |

合计约 **2~2.5 个工作日**。

---

## 7. 风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| 首次 `npm i` 要下 Electron 二进制（~100MB） | 网络慢会卡住 | 配国内镜像 `ELECTRON_MIRROR`；失败可离线放缓存 |
| `AppPaths` 定位错乱 | 打开是空库，像丢数据 | P0 先做可配 + `/api/health` 回显真实路径 |
| 端口冲突 | 启动失败 | 39300 起动态探测，写 `.port` 文件供主进程读取 |
| 未签名 exe 被杀软误报 | 用户不敢装 | 自签名 + 提供 SHA256；正式分发再考虑代码签名 |
| SSE 在窗口隐藏时被节流 | 回答卡住 | 窗口恢复时主动重连；后端已有半截入库兜底 |
| 子进程变孤儿 | 后台残留 java | 退出走进程树 kill；启动时清理上次残留 PID |
| 体积偏大 | 下载慢 | jlink 裁剪 + NSIS 压缩；必要时剔除未用模块 |

---

## 8. 决策记录（2026-11-05 确认）

| 决策点 | 选定 | 影响 |
|---|---|---|
| 壳技术 | **B2 Electron** | 用现有 Node 22，无需 Rust；体积 ~330MB |
| 数据位置 | **程序目录 `data\.kb`** | 绿色便携；安装目录不能选 `Program Files` |
| 附加能力 | **仅托盘常驻** | 不做开机自启、不做自动更新（二期再议） |

### 由此确定的两处细节

1. 安装目录默认 `%LOCALAPPDATA%\Programs\知识库`（用户可写），数据在其下 `data\.kb`
2. 点「×」= 隐藏到托盘；托盘菜单「打开主界面 / 退出」；「退出」才终止 sidecar

---

## 9. 开工前置

- [ ] 允许 `npm i` 下载 Electron 及依赖（`desktop/` 下，约 300MB，需联网）
- [ ] 确认 **P0 可以先动**（纯后端三个开关，向后兼容，不影响现有 Web 形态）

---

## 10. 批准后的执行顺序

1. P0 后端开关（一个 commit，Web 形态不受影响）
2. P1 Electron 壳工程（新目录，不影响现有构建）
3. P2 打包脚本 + 安装包
4. P3 托盘体验、P4 迁移与回归
5. 每阶段跑验收，通过后再进下一阶段
