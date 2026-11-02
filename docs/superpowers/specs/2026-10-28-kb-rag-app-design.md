# 两翼知识库 RAG 应用 · 设计文档（v2）

- 日期：2026-10-28（v2 修订；v1 为 Python 混合方案，已废弃）
- 状态：待评审
- 原型：`.dev/mock/app-prototype.html`（界面定稿依据）
- 版本背景：v1 复用旧 Python 检索层（kb/ 包）；经确认其为历史对话遗留，已删除，改为**纯 Java 从零设计**，文档解析、切块、索引、检索、向量全部 Java 实现

## 1. 背景与目标

把本地文件夹（`新炬/两翼` 下的两翼研运平台业务资料：需求规格、上线文档、SQL、接口说明等）建成一个本地知识库软件：

- 网页界面进行知识库问答（RAG）、文档浏览与预览
- 接入多家国内 LLM 厂商（OpenAI 兼容协议），可切换
- 混合检索：Lucene BM25 关键词 + HNSW 向量（KNN），RRF 融合
- 对外提供 MCP 服务，供 Claude Code / Claude 桌面版等客户端检索本库

**使用者**：仅本人，单机单用户，无鉴权。
**前置项**：遗留文件（`kb/`、`.kb/`、`.dev/` 旧脚本）已由用户于 2026-10-28 自行删除；语料 `新炬/两翼` 保留不动。

## 2. 已确认的决策记录

| # | 决策点 | 结论 |
|---|--------|------|
| 1 | 产品形态 | 本地 Web 应用（浏览器访问 localhost），不做桌面打包 |
| 2 | LLM 角色 | RAG 知识库问答（检索 → LLM 生成带出处回答） |
| 3 | LLM 厂商 | 国内厂商为主，统一 OpenAI 兼容协议（DeepSeek/通义/Kimi/智谱等），加新厂商只改配置 |
| 4 | 检索方案 | 混合检索：Lucene BM25 + KNN 向量双路召回，RRF 融合 |
| 5 | 使用者 | 只自己用，无登录鉴权 |
| 6 | 技术栈 | 后端 Spring Boot + 前端 Vue，**全 Java 单栈**（无 Python） |
| 7 | 检索引擎 | Apache Lucene（BM25 + HNSW KNN 一体，内置中文分析器与高亮），不用 ES/FTS5 |
| 8 | 文档解析 | Apache Tika（封装 POI/PDFBox，自动类型探测，一个依赖覆盖 docx/doc/xlsx/xls/pptx/pdf/md/html/txt） |
| 9 | JDK | 17.0.15（`E:\tool\jdk-17.0.15`，Microsoft OpenJDK），Maven toolchains 指定，不动全局 JDK 1.8；Spring Boot 3.4.x；Maven 3.6.3 |
| 10 | 文档预览 | 第一版：PDF(PDF.js)/Word(mammoth)/Excel(SheetJS)/纯文本代码(md+highlight.js)/HTML(iframe)；PPT 降级为按页文本预览；LibreOffice 转换器进后续可选 |
| 11 | MCP 工具 | 只暴露检索工具 `kb_search`、`kb_open`，不做服务端 RAG 问答 |
| 12 | 增量与成本 | 索引构建免费（本地）；embedding 按 chunk 内容 hash 对账复用，只对新增/变更分块调 API |
| 13 | 数据源配置 | 放在设置页（路径输入 + 浏览），顶栏只显示同步状态 |
| 14 | 市面调研补充 | 第一版包含：会话历史+多轮追问、提问范围选择(folder 过滤)、检索测试工具页、对话导出 Markdown |

## 3. 总体架构

```
浏览器 (Vue 3 + Vite, localhost:5173)
        │ HTTP / SSE
        ▼
Spring Boot 单进程 (localhost:8080)
  ├── 索引层   Tika 解析 → 分块 → Lucene 索引（BM25 字段 + KNN 向量字段）
  ├── 检索层   Lucene：BM25 召回 ∥ HNSW KNN 召回 → RRF 融合 → 高亮
  ├── LLM 层   OpenAiCompatClient（多厂商聊天） + EmbeddingClient（可切换）
  ├── 会话层   .kb/chat.db（SQLite，会话与消息）
  └── MCP 层   spring-ai-starter-mcp-server-webmvc，SSE /mcp
```

单进程交付：前端构建产物由 Spring Boot 静态托管；开发期 Vite 代理 `/api`。

## 4. 目录与数据布局

```
E:\KnowledgeBase\
├── 新炬/两翼/                  # 语料（用户数据，只读）
├── .dev/mock/                  # 界面原型
├── docs/superpowers/specs/     # 本设计文档
├── .kb/                        # 应用数据目录（全部 Java 生成）
│   ├── lucene/                 # Lucene 索引目录
│   ├── chat.db                 # 会话库
│   └── app.json                # 应用配置（LLM/检索参数；数据源路径写此处）
├── server/                     # Spring Boot（Maven 单模块）
└── web/                        # Vue 3 + Vite
```

配置文件只有一个 `.kb/app.json`（v1 中"Python config.json"随 Python 一起废弃）：

```json
{
  "source_root": "E:\\KnowledgeBase\\新炬\\两翼",
  "providers": [
    {"name":"DeepSeek","base_url":"https://api.deepseek.com/v1","api_key":"sk-xxx","chat_model":"deepseek-chat"},
    {"name":"智谱","base_url":"https://open.bigmodel.cn/api/paas/v4","api_key":"xxx","chat_model":"glm-4-air","embedding_model":"embedding-3","embedding_dim":1024}
  ],
  "active_chat": "DeepSeek", "active_embedding": "智谱",
  "rag": {"top_k": 8, "candidate": 20, "rrf_k": 60, "max_per_doc": 3},
  "index": {"chunk_chars": 500, "chunk_overlap_chars": 50, "min_chunk_chars": 20}
}
```

API Key 仅存本机，不上传、不进 git。

## 5. 索引层设计（Tika + Lucene，全新实现）

### 5.1 扫描与过滤（继承旧实现验证过的经验值）

- 扩展名白名单：`.docx .doc .pdf .xlsx .xls .pptx .ppt .md .markdown .txt .sql .yml .yaml .java .py .ts .sh .bat .html .htm .csv .log .properties .ini .conf`
- 排除目录：`.kb .git .svn .idea .vscode node_modules .venv $RECYCLE.BIN System Volume Information`
- 排除文件名：`~$*` `*.tmp` `*.bak` `Thumbs.db` `.DS_Store` `*.swp`；Axure 导出的 `data.js` 类前端资源噪声不进白名单即天然排除

### 5.2 解析与分块

- **Tika 解析**出纯文本；Office 文档保留"标题层级"信息：docx/pptx 按段落样式/页推导标题，md 按 `#` 层级，html 按 h1-h6；xlsx 按工作表切分，每个 sheet 一块组
- **分块策略**：标题感知滑动窗口——块目标 `chunk_chars`(500)，相邻块重叠 50 字符，短块(<`min_chunk_chars`)向后合并；每块记录所属标题路径（如 `3.2 网络类资源申请流程`）
- 每块字段：

  | 字段 | 类型 | 用途 |
  |---|---|---|
  | doc_path | Stored | 文档相对路径（唯一键） |
  | doc_title | Stored + text | 展示与标题加权 |
  | ext/kind | Stored | 筛选徽标 |
  | folder | Stored + filter | 范围过滤 |
  | seq | Stored | 块序号 |
  | heading | Stored | 所属标题路径 |
  | body | text（BM25，SmartChineseAnalyzer） | 关键词检索主字段 |
  | heading_text | text（加权提升） | 标题匹配加分 |
  | vector | KNN vector (dim 按模型) | HNSW 语义检索 |
  | chunk_hash | Stored | 块文本 sha1，embedding 对账 |
  | file_hash | Stored | 所属文件内容 sha1，增量判断 |
  | mtime_size | Stored | 文件 sig，增量判断 |

### 5.3 增量构建（后台线程，界面触发）

1. 扫描文件，与 Lucene 中 `doc_path → (sig, file_hash)` 对照
2. 未变文件跳过；新增/变更文件：删除该 doc 旧块 → 重新解析分块
3. 每块算 `chunk_hash`：与旧块（删除前已读出）比对，相同 → 向量复用（旧块字段原样重写）；不同 → 调 embedding API 补齐
4. 源文件已删除 → 删除对应全部块
5. 全程进度回调（新增/更新/跳过/删除/向量补齐计数）推送前端；构建中检索不受影响（Lucene 多 reader）
6. 成本护栏：需要新调 embedding 的块数 > 阈值（默认 200）时，界面弹出估算（块数 × 单价）确认后执行

### 5.4 中文分析器

主选 **SmartChineseAnalyzer**（Lucene 内置中文分词）；若实测效果不佳，降级方案为 CJK bigram 自定义分析器（实现规划时用检索测试页做 A/B 对比后锁定）。

## 6. 检索层

1. **双路召回**：BM25（body + heading_text 加权）top 20 ∥ KNN 向量 top 20
2. **RRF 融合**（k=60）取 top K（默认 8），`max_per_doc` 每文档去重
3. **folder 过滤**：查询时按范围 chips/MCP 参数下推为 Lucene filter
4. **高亮**：Lucene highlighter 生成命中片段（前端 `mark` 高亮）
5. 检索测试页：展示每块 RRF 得分、两路各自命中标记、路径/块号/heading，调参（candidate、rrf_k、max_per_doc、分析器）即改 `app.json` 即测

## 7. Embedding

- `EmbeddingClient` 接口，默认实现 OpenAI 兼容 `/v1/embeddings`（智谱 embedding-3 / 通义 text-embedding 等均适用）；`base_url/api_key/model/dim` 来自 `app.json`
- 换 embedding 模型 = 向量维度或 model 标识变化 → 全量向量失效，重建时对全部块重新 embedding（成本护栏会先弹出）
- embedding API 不可用：向量路自动跳过，仅 BM25 召回，界面提示（索引构建同理，无向量块只进关键词路）

## 8. RAG 问答流程

1. 用户提问（可带范围 folder、会话 id、指定厂商）
2. 双路召回 → RRF → top 8 片段
3. 上下文拼接：每块带 `[序号] 文档标题 · 路径 · heading · 块序号`，系统提示要求引用序号标注、不编造、检索不到就明说
4. LLM **流式**输出（SSE 透传），结束后落 chat.db
5. 前端：回答内嵌引用角标 → 点击开预览抽屉并定位命中块；来源卡片横排

多轮：当前会话最近 3 轮并入 LLM 消息历史；检索 query 用当前问题（query rewrite 进后续可选）。

## 9. LLM 客户端

- `OpenAiCompatClient`：`POST {base_url}/chat/completions`，`stream:true`，WebClient 解析 SSE；超时/重试可配
- 设置页：厂商增删改、测试连通（1-token 请求）、切换当前聊天/嵌入厂商

## 10. MCP 服务

- 依赖 `spring-ai-starter-mcp-server-webmvc`，随主服务启动，SSE 端点 `http://localhost:8080/sse`（Spring AI 1.0 默认；消息端点 /mcp/message）
- 工具（纯检索，无 LLM 调用）：
  - `kb_search(query, top_k?, folder?, max_per_doc?)` → 片段（标题、路径、块号、得分、高亮摘录）
  - `kb_open(doc_path, seq?)` → 单块或整篇提取文本（与预览 /text 同源）
- 设置页：服务状态、工具开关、一键复制接入命令（`claude mcp add --transport sse kb http://localhost:8080/mcp`）

## 11. 会话层

`.kb/chat.db`（SQLite via sqlite-jdbc；选 SQLite 而非 H2 仅为可用 DB 工具直接查看）：

```sql
CREATE TABLE session(id TEXT PRIMARY KEY, title TEXT, created_at TEXT, updated_at TEXT);
CREATE TABLE message(
  id TEXT PRIMARY KEY, session_id TEXT NOT NULL,
  role TEXT NOT NULL,            -- user / assistant
  content TEXT NOT NULL,
  sources TEXT,                  -- JSON: [{doc_path, seq, title, score}]
  model TEXT, created_at TEXT
);
```

问答页左侧会话列表按 updated_at 倒序分组（今天/昨天/更早）；新会话、切换会话。

## 12. 前端设计（Vue 3）

- **技术**：Vite + Vue 3 `<script setup>` + vue-router；不用组件库，沿用原型设计 token：22px 大圆角、柔和大阴影、主色 `#5A6BFF`、径向渐变浅色背景、**顶栏横向导航**
- **页面**（与原型一一对应）：
  1. **智能问答**（默认）：左会话列表 + 聊天流（引用角标、来源卡片、⤓导出 MD）+ 检索范围 chips + 输入框；右上厂商/模型胶囊
  2. **文档库**：类型 chips + 搜索框 + 文档卡片列表（类型徽标/路径/分块数/摘要）
  3. **设置**：LLM 厂商卡片、当前厂商配置、索引与数据源（路径+浏览、统计、重建索引进度）、MCP 状态
  4. **检索测试**：问题输入 → 召回块列表（RRF 分、双路命中标签、路径块号）
- **预览抽屉**（全局组件）：按扩展名选渲染器——PDF.js / mammoth(docx) / SheetJS(xlsx·多 sheet 标签) / highlight.js(文本代码) / iframe(html) / PPT 按页文本；预览与命中定位复用检索接口的块号

## 13. 后端 REST API 草案

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/ask | RAG 问答，SSE 流式；question/folder/session_id/model |
| GET | /api/sessions · /api/sessions/{id} | 会话列表 / 消息 |
| GET | /api/search | 纯检索（文档库搜索、检索测试共用） |
| GET | /api/docs | 文档列表（分页、folder/ext 筛选，来自 Lucene doc_path 聚合） |
| GET | /api/doc/**?seq= | 预览：raw 原文件流 / text 提取文本（Tika 已存） |
| GET/PUT | /api/settings | app.json 读写（providers、source_root、rag、index 参数） |
| POST | /api/index/rebuild | 触发增量构建（后台线程），SSE/轮询进度 |
| GET | /api/index/status | 索引统计（文档数/块数/向量覆盖/上次构建） |
| GET | /api/llm/test | 厂商连通性测试 |
| (SSE) | /mcp | MCP 服务端点 |

## 14. 工程结构与构建

```
server/  （Maven 单模块，artifactId: kb-server）
├── pom.xml：spring-boot-starter-web、lucene-core/lucene-analysis-common/
│            lucene-backward-codecs(按需)、tika-core/tika-parsers-standard、
│            sqlite-jdbc、spring-ai-starter-mcp-server-webmvc、webflux
└── src/main/java/kbapp/
    ├── ingest/   Scanner, TikaParser, Chunker, IndexBuilder(增量+成本护栏)
    ├── retrieve/ HybridSearchService(BM25+KNN+RRF), Highlighter, FolderFilter
    ├── llm/      OpenAiCompatClient, EmbeddingClient, ChatService, RagContextBuilder
    ├── mcp/      KbSearchTools
    ├── chat/     SessionRepository(SQLite)
    └── web/      AskController, SearchController, DocController, SettingsController, IndexController

web/  （Vite + Vue3）src/{views/Ask,Library,Settings,RetrieveTest, components/PreviewDrawer…, styles/tokens.css, api/}
```

- `mvn -q spring-boot:run`（toolchains → jdk-17.0.15）；`npm run dev` 代理 /api
- 编码统一 UTF-8（`project.build.sourceEncoding`），规避 Windows GBK

## 15. 明确不做（YAGNI）与后续可选

**第一版不做**：登录鉴权、多用户、桌面打包分发、团队部署、query rewrite、删除会话 UI、组件库、ES/向量数据库。

**后续可选**（调研自市面产品，暂缓）：LibreOffice 转换器（.doc/.ppt 原样渲染）、本地 embedding 模型（Ollama bge-m3）、文件监听自动增量（WatchService）、网页/YouTube 剪藏入库、OCR 扫描件、WebDAV 备份、全局快捷键快速搜索、智能体工作流编排、多知识库栈。

## 16. 风险与开放问题

1. **中文分析器选型**：SmartChineseAnalyzer 与 CJK bigram 用检索测试页 A/B 后锁定（实现规划第 1 步）
2. **Tika 解析 Office 表格/标题保真度**：xlsx 多 sheet 与 docx 标题层级是检索质量关键，实现时用两翼真实文档验收
3. **embedding 全量重建成本**：换模型或首次构建约 3.6 万块量级，护栏阈值与估算展示必须先做
4. **Lucene 版本**：用 Lucene 9.x LTS 线（与 Spring AI/Boot 3.4 兼容性实现时锁定），KNN 字段默认 HNSW
5. **MCP starter 版本**：Spring AI 1.0.x GA，实现规划时锁定
6. **GBK 环境**：全链路 UTF-8；subprocess 不存在（无外部进程），风险仅剩 Maven 控制台输出
