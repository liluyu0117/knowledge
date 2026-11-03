# 待办：笔记接入知识库（方案 A2）

> 状态：**待实施**（2026-11-03 调研并记录，暂不动代码）
> 目标：让「笔记」可被问答 / 检索 / MCP 使用，同时不污染业务知识库

## 一、现状（调研结论）

笔记与知识库在**存储、索引、检索、接口**四个层面完全隔离，无任何关联字段。

| 层面 | 笔记 | 知识库 |
|---|---|---|
| 存储 | `.kb/notes/{id}.md`（散文件）+ `.kb/notes.json` | `.kb/kbdata/{kbId}/` 目录副本 |
| 元数据 | 仅 `id/title/type/createdAt/updatedAt` | Lucene 索引 + `build/{kbId}.json` + `docmeta-{kbId}.json` |
| 索引 | 无（`NotesStore` 未引入 Lucene/Embedding） | 每库独立 Lucene 索引（倒排 + KNN 向量） |
| 检索 | 无（列表仅按时间排序） | `GET /api/search?kbId=…`，按索引目录物理隔离 |

关键代码位置：

- 笔记落盘：`server/src/main/java/kbapp/notes/NotesStore.java:41`（`notesDir()`）、`:74`（`noteFile`）、`:31`（`Note` record）
- 笔记接口：`server/src/main/java/kbapp/notes/NotesController.java:26-78`（仅 5 个 CRUD 端点）
- 知识库目录：`server/src/main/java/kbapp/kb/KbRegistry.java:155-157`（`docsRoot`）、`:159`（`indexDir`）
- 索引扫描：`server/src/main/java/kbapp/ingest/IndexBuilder.java:313-320`（`scanner.scan(docsRoot)`）
- 扩展名白名单：`server/src/main/java/kbapp/ingest/ScanFilter.java:12-16`（已含 `.md .markdown .txt .docx`）
- 标题来源：`IndexBuilder.java:678`（`stripExt(fileName)`）
- 检索隔离：`server/src/main/java/kbapp/retrieve/HybridSearchService.java:106-108`
- 自动增量：`server/src/main/java/kbapp/ingest/AutoRebuildJob.java:31-48`（当前 `autoMinutes=10`）
- 备份清单：`server/src/main/java/kbapp/sync/SyncService.java:138-147`（**只列了 `notes.json`，漏了 `notes/` 正文目录**）

## 二、方案 A2 设计

**一句话**：笔记正文落盘到 `.kb/kbdata/notes/`，并把 `notes` 注册为内置知识库「我的笔记」。
检索/问答/MCP **一行不用改**，因为它们只按 `kbId` 选索引目录。

### 数据模型

```java
record Note(String id, String title, String type,
            String createdAt, String updatedAt,
            String kbId)   // 新增，默认 "notes"
```

`notes.json` 向后兼容：读取时缺 `kbId` 的旧记录自动补 `"notes"`。

### 落盘规则

| 项 | 现状 | A2 之后 |
|---|---|---|
| 目录 | `.kb/notes/` | `.kb/kbdata/notes/` |
| 文件名 | `{12位id}.md` | `{标题}.md`（标题即检索结果标题） |
| 重名 | 注册表里加 ` (2)` | 文件名同样加 ` (2)` |
| 非法字符 | 前端校验 | 后端 sanitize `\/:*?"<>|` → `_` |

### 内置知识库

- `kbs.json` 增加一条固定项：`{id:"notes", name:"我的笔记", fixed:true}`
- 前端知识库管理页：`fixed` 的库不显示删除按钮，标注「内置」
- 智能体编辑对话框的知识库下拉里可直接选它

## 三、任务清单

### 阶段 1：笔记进入索引（核心，先做这个）

- [ ] **1.1** `NotesStore` 落盘路径改为 `.kb/kbdata/notes/`，文件名改为按标题（含 sanitize + 重名序号）
  - 要点：`noteFile()` 返回值语义从 `{id}.{type}` 改为 `{title}.{type}`；`update()` 时若标题变了要 `Files.move` 旧文件
  - 验收：新建/改名/删除笔记后，目录里文件名与标题一致；无残留旧文件
- [ ] **1.2** `Note` 增加 `kbId`，`notes.json` 读取时兼容旧数据（缺字段补 `"notes"`）
  - 验收：用当前 `.kb/notes.json` 直接启动不报错，接口返回带 `kbId:"notes"`
- [ ] **1.3** `KbRegistry` 启动时确保内置库 `notes` 存在（`fixed:true`，不可删除）
  - 要点：`delete` 接口对 `fixed` 库返回 400；`kbs` 列表标注 `fixed`
  - 验收：`GET /api/kb/s` 能看到「我的笔记」；尝试删除被拒
- [ ] **1.4** 数据迁移：启动时若检测到 `.kb/notes/*.md` 且新目录为空，按 `notes.json` 批量搬迁
  - 要点：幂等、失败不留半成品（先复制再原子移动）、迁移日志写到 `.kb/migrate-notes.log`
  - 验收：老数据零丢失；重复启动不重复搬
- [ ] **1.5** 笔记保存后触发「我的笔记」单库增量（30 秒防抖），而非等 10 分钟定时任务
  - 验收：新建笔记后 ≤40 秒在问答里能检索到
- [ ] **1.6** 修复备份漏项：`SyncService` 备份清单加入 `notes/`（迁移完成后该目录已在 `kbdata/` 内，需确认不会被重复打包）
  - 验收：备份包内含笔记正文

### 阶段 2：跨库检索（体验提升）

- [ ] **2.1** 检索结果标注来源类型：`kind` 增加 `note`，前端来源卡片显示 📝 图标
- [ ] **2.2** 智能体增加「同时检索我的笔记」开关（默认开），问答时查「业务库 + notes」两个索引做 RRF 融合
  - 要点：`HybridSearchService` 抽出「按 kbId 检索一次」的方法，外层做多库合并
  - 验收：同一问题能同时命中文档与笔记，且来源可区分

### 阶段 3：双向关联（可选）

- [ ] **3.1** 笔记正文支持 `@doc:相对路径#块号` 语法，渲染成可点击链接 → 打开预览抽屉
- [ ] **3.2** 文档预览侧显示「引用此文档的笔记」列表

## 四、风险与注意

| 风险 | 说明 | 应对 |
|---|---|---|
| 数据迁移丢笔记 | 老笔记在 `.kb/notes/`，新位置在 `kbdata/notes/` | 先复制校验再删源；迁移日志留痕；保留 `.kb/notes.bak` 一次 |
| 标题做文件名冲突 | 中文/符号/超长标题、重名 | sanitize + 序号（复用 `NotesStore` 现有唯一化逻辑，`NotesStore.java:126-137`） |
| 构建变慢 | 笔记数量大（数千篇）会拖慢 `notes` 库构建 | 单库增量 + chunk hash 复用向量（`IndexBuilder.java:697-704` 已有） |
| 语义混淆 | 用户可能困惑「笔记怎么在知识库里」 | 库名「我的笔记」+ 前端标注「内置」，且业务库里不会出现笔记 |
| 与坚果云同步冲突 | `.kb/kbdata/notes/` 会被整包同步 | 正常，笔记本就应随 `.kb` 同步 |

## 五、回滚

1. `notes` 库从 `kbs.json` 移除，`delete` 放开
2. 启动时检测 `kbdata/notes/` → 反向搬回 `.kb/notes/{id}.{type}`
3. 或直接恢复迁移前的 `.kb` 备份

## 六、参考：为什么不用其他方案

- **方案 B（`IndexBuilder` 加第二个扫描根）**：每个业务库都会包含全部笔记，跨库污染；且要同时改预览 `DocController.java:279-289`、文件树 `KbController.java:158-160`、删除清理三处路径假设
- **方案 C（笔记独立索引 + 检索时融合）**：改动最大（新索引生命周期 + RRF 融合 + 来源标识），适合阶段 2 再做
- **方案 A3（手动推送按钮）**：改动最小，但笔记需手动挑选，不满足「随手记完就能问到」
