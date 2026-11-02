# 两翼知识库 RAG 应用 · 实现计划

- 日期：2026-10-28
- 依据：`docs/superpowers/specs/2026-10-28-kb-rag-app-design.md`（v2，已批准）
- 界面依据：`.dev/mock/app-prototype.html`

## Global Constraints

- **不写单元测试**：验证口径 = `mvn compile` 通过 + 应用能起 + 浏览器/接口联调
- **不主动 commit / push**：由用户决定提交时机
- **语料只读**：绝不修改、移动、删除 `新炬/` 下任何文件；索引程序只读该目录
- **全链路 UTF-8**：Maven `project.build.sourceEncoding=UTF-8`，所有 Java/前端文件 UTF-8
- **JDK**：编译与运行用 `E:\tool\jdk-17.0.15`（Maven toolchains 指定），不改全局 `JAVA_HOME`
- **API Key 只进 `.kb/app.json`**（该目录不进任何构建产物）
- 每个任务完成即验证，不通过不进下一个任务
- 后端端口 8080，前端开发端口 5173

## Task Structure

### Task 1: 后端工程脚手架

**文件**：
- `server/pom.xml`：parent `spring-boot-starter-parent:3.4.2`；依赖 `spring-boot-starter-web`、`spring-boot-starter-webflux`、`lucene-core`、`lucene-analysis-common`、`tika-core`、`tika-parsers-standard`（`2.9.2`）、`sqlite-jdbc`（`3.46.1.3`）、`spring-ai-starter-mcp-server-webmvc`（`1.0.0`，BOM `spring-ai-bom`）、`jackson`（随 boot）；`maven-compiler-plugin` release 17 + `maven-toolchains-plugin`（jdk 17）；`sourceEncoding=UTF-8`
- `server/src/main/java/kbapp/KbServerApplication.java`：`@SpringBootApplication` + `@EnableScheduling`（暂只用 main）
- `server/src/main/resources/application.yml`：`server.port=8080`、`spring.servlet.multipart` 关闭、jackson 非空忽略、编码 UTF-8
- `server/.mvn/toolchains.xml` 不用——在用户 `~/.m2/toolchains.xml` 注册 jdk-17.0.15（若不存在则创建，含 Windows 路径写法）

**验证**：`cd server && mvn -q compile` 成功；`mvn spring-boot:run` 能起，`curl http://localhost:8080/actuator/health` 无要求（不加 actuator），以启动日志 "Started KbServerApplication" 为准，Ctrl-C 退出。

### Task 2: 配置层（.kb/app.json 读写）

**文件**：
- `kbapp/config/AppConfig.java`：record/POJ O——`sourceRoot`、`providers[]`（name/baseUrl/apiKey/chatModel/embeddingModel/embeddingDim）、`activeChat`、`activeEmbedding`、`rag{topK=8,candidate=20,rrfK=60,maxPerDoc=3}`、`index{chunkChars=500,chunkOverlapChars=50,minChunkChars=20}`、`guard{embedThreshold=200}`；默认值即设计文档所列
- `kbapp/config/ConfigService.java`：启动时 `.kb/app.json` 不存在则写默认文件；`get()`（缓存，volatile 引用替换）、`update(JsonNode)`（合并保存 + 刷新缓存）；路径解析基于工作目录 `E:\KnowledgeBase`（`Paths.get(".kb").toAbsolutePath().normalize()`）
- `kbapp/web/SettingsController.java`：`GET /api/settings` 返回配置（api_key 脱敏为 `sk-****末4位`）、`PUT /api/settings` 合并保存

**验证**：启动后 `curl http://localhost:8080/api/settings` 返回含 `sourceRoot` 与两个空 providers 的 JSON；`curl -X PUT` 改 `rag.topK=10` 再 GET 确认落盘到 `.kb/app.json`。

### Task 3: 文件扫描器（含过滤规则）

**文件**：
- `kbapp/ingest/FileScanner.java`：`List<Path> scan(Path root)`——按设计文档 5.1 的扩展名白名单、排除目录、排除文件名模式（前缀 `~$`/后缀匹配）实现；返回相对路径列表（排序稳定）
- `kbapp/ingest/ScanFilter.java`：常量与匹配逻辑独立成类（白名单 Set、排除目录 Set、`~$*`/`*.tmp`/`*.bak`/`Thumbs.db`/`.DS_Store`/`*.swp` 前后缀判断）

**验证**：临时 main 或启动后经 Task 10 的 `/api/index/status` 前置接口调用——本任务先以 `FileScanner` 上加临时 `public static void main` 跑 `新炬/两翼` 打印计数（应>1000 文件且无 `~$` 开头、无 node_modules），验证后删掉 main。

### Task 4: Tika 解析与标题感知分块

**文件**：
- `kbapp/ingest/TikaExtractor.java`：`Extracted extract(Path file)`——Tika `AutoDetectParser` + `BodyContentHandler(-1)` + Metadata；对 `.docx`（`XWPFDocument` 直接读段落样式判断标题，与 Tika 结果合并）、`.md`（行首 `#` 计数）、`.html`（正则 h1-h6）、`.pptx`（`XMLSlideShow` 每页文本，页码即标题）、`.xlsx`（`XSSFWorkbook` 每 sheet 一段，sheet 名即标题）走专门分支产出**带标题层级的块流**；其余类型整文单流
- `kbapp/ingest/Chunker.java`：`List<Chunk> chunk(Extracted e, IndexConfig cfg)`——标题感知滑动窗口：目标 500 字、重叠 50、短块(<20)向后合并；`Chunk{seq, headingPath, text}`
- `kbapp/ingest/Chunk.java`：不可变 record

**验证**：临时 main 对 3 个真实文件各跑一遍（一个 docx《华为云账号授权审批流程…》、一个 xlsx《副本铁塔平台资源申请-网络类》、一个 md），打印块数与首尾块 headingPath，人工确认标题归属合理；验证后删 main。

### Task 5: Lucene 索引与增量构建（无向量）

**文件**：
- `kbapp/ingest/IndexSchema.java`：字段常量与方法——`docPath`(StringField+Stored)、`docTitle`(TextField+Stored boost)、`ext/kind`(Stored)、`folder`(StringField+Stored)、`seq`(Stored IntPoint)、`heading`(Stored)、`body`(TextField, SmartChineseAnalyzer)、`headingText`(TextField)、`chunkHash`(Stored)、`fileHash`(Stored)、`sig`(Stored)、`vector`(KnnFloatVectorField, 维度取配置)——本任务先不写 vector
- `kbapp/ingest/IndexBuilder.java`：`void rebuild(BiConsumer<String,Object> progress)`（后台单线程执行器跑）——扫描 → 与索引内 `docPath→(sig,fileHash)`（用 ` docPath:*` 查询收集）对照 → 未变跳过 / 变更删旧块重写 / 删除清理；`chunk_hash` 相同的块整条复用（读到旧 Document 字段重写）；`Directory=FSDirectory.open(.kb/lucene)`；Analyzer=`SmartChineseAnalyzer`
- `kbapp/ingest/IndexStatusService.java`：文档数/块数/上次构建时间/进行中标志/进度（AtomicReference），`GET /api/index/status`、`POST /api/index/rebuild`（挂到 `kbapp/web/IndexController.java`）
- `kbapp/web/IndexController.java`（新建）

**验证**：启动 → `POST /api/index/rebuild` → 轮询 `/api/index/status` 至完成，文档数与 `FileScanner` 计数一致、块数>0；立即再 rebuild 一次，进度显示"跳过=全部"，耗时秒级（增量生效）；手工改一个 md 后再 rebuild 显示"更新 1"。（此时仅 BM25，无向量。）

### Task 6: Embedding 客户端与向量补齐（含成本护栏）

**文件**：
- `kbapp/llm/EmbeddingClient.java`：接口 `float[] embed(String text)` / `List<float[]> embedAll(List<String>)`
- `kbapp/llm/OpenAiCompatEmbedding.java`：WebClient `POST {baseUrl}/embeddings`，body `{model, input:[...]}`，批量每批 16 条；解析 `data[].embedding`
- `IndexBuilder` 集成：`vector` 字段在写入时补——`chunk_hash` 命中复用则重写旧向量；新增/变更块先收集，块数>`guard.embedThreshold` 时置状态 `awaiting_confirm` 并停在该步，`POST /api/index/confirm-embed` 后继续；embedding 失败 → 该块无 vector 字段（检索时自动仅 BM25 路）且计数 `vectorFailed`
- `IndexController` 增加确认端点；`IndexStatusService` 增加待确认状态与估算（块数、模型名）

**验证**：在 `.kb/app.json` 配一个真实 embedding 厂商（如智谱 embedding-3，用户现场提供 key）→ 删掉 `.kb/lucene` 后 rebuild → 完成后 status 显示 `vectorCovered≈100%`；二次 rebuild 不再调 API（计数 0）；把阈值临时改成 5 跑一次验证 `awaiting_confirm` 弹出逻辑。

### Task 7: 混合检索服务（BM25 + KNN + RRF + 高亮）

**文件**：
- `kbapp/retrieve/HybridSearchService.java`：`SearchResult search(String query, int topK, String folder, int maxPerDoc, boolean withVector)`——`IndexSearcher` 两路：`body/headingText` BM25 top candidate；`KnnFloatVectorQuery`（查询向量来自 EmbeddingClient，withVector=false 或无配置时跳过）top candidate；RRF(k=60) 融合排序；每文档去重(maxPerDoc)；folder 非空加 `FolderFilter`；`QueryScorer`+`SimpleHTMLFormatter` 生成 `<mark>` 片段
- `kbapp/retrieve/SearchHit.java`：record（docPath、docTitle、ext、folder、seq、heading、score、rrf 分、kw 命中、vec 命中、snippetHtml）
- `kbapp/web/SearchController.java`：`GET /api/search?q=&topK=&folder=&maxPerDoc=&vector=`
- 分析器 A/B：`AnalyzerFactory` 支持 `smart|bigram` 两实现（bigram=`NGramTokenizer(2,2)`+小写），配置 `rag.analyzer` 切换，默认 smart

**验证**：启动后对真实库跑 6 个中文查询（如 `华为云 账号 授权 审批 流程`、`网络类 资源 申请 带宽`、`comp_system`），curl 返回 RRF 分与命中来源合理；`vector=false` 与 `true` 对比召回差异记录进计划执行备注；两种 analyzer 各跑一遍同一组查询。

### Task 8: 会话库（SQLite chat.db）

**文件**：
- `kbapp/chat/ChatStore.java`：启动建表（设计文档 11 节 DDL，`.kb/chat.db`）；`createSession()/listSessions()/getMessages()/appendMessage()/touchSession()`；`sources` 列存 Jackson 序列化的 `List<SourceRef>`；`jdbc:sqlite:` 单连接 + `synchronized` 写（单用户足够）
- `kbapp/chat/SourceRef.java`：record（docPath、seq、title、score）

**验证**：临时 curl/IDE REST——create → append 两条 → list 返回倒序分组字段正确；重启进程数据仍在；`.kb/chat.db` 用 DB 浏览器可打开。

### Task 9: LLM 客户端与 RAG 编排

**文件**：
- `kbapp/llm/OpenAiCompatClient.java`：`Flux<String> streamChat(List<ChatMessage> msgs, ProviderCfg p)`——WebClient SSE 逐块解析 `choices[0].delta.content`；非流式 `String chat(...)` 供测试连通（max_tokens=1）
- `kbapp/llm/ChatMessage.java`：record(role, content)
- `kbapp/llm/RagContextBuilder.java`：把 SearchHit 列表拼成带 `[n] 标题 · 路径 · heading · 块号` 的上下文 + 系统提示（引用序号、不编造、检索不到明说、中文回答）
- `kbapp/llm/ChatService.java`：`Flux<AskEvent> ask(question, folder, sessionId, modelName)`——检索（Task 7）→ 组装（含最近 3 轮历史）→ 流式转发，结束时拼 `AskEvent.done(fullText, sources)`；会话落库（Task 8）；新会话标题取问题前 20 字
- `kbapp/web/AskController.java`：`POST /api/ask`（`text/event-stream`；事件类型 `delta`/`done`/`error`）
- `kbapp/llm/AskEvent.java`：sealed/record 集合

**验证**：配置真实 DeepSeek key → curl -N POST /api/ask 收到增量 delta、done 里 sources 含真实 docPath 与分数；追问一轮确认历史生效；换 `active_chat` 厂商配置再问一次生效。

### Task 10: 文档列表与预览接口

**文件**：
- `kbapp/web/DocController.java`：
  - `GET /api/docs?folder=&ext=&page=&size=`——Lucene 按 docPath 聚合（collector 取每文档首块+计数），返回 `{path,title,ext,folder,chunkCount,snippet}`
  - `GET /api/docs/search?q=`——复用 HybridSearchService 返回文档卡片列表
  - `GET /api/doc/raw?path=`——`FileSystemResource` 流式返回原文件（Content-Type 按扩展名；路径必须 normalize 后仍位于 sourceRoot 内，防目录穿越）
  - `GET /api/doc/text?path=&seq=`——从 Lucene 取该文档全部块文本或单块（PPT 按页文本预览同源）
- `kbapp/ingest/DocReader.java`：从 IndexSearcher 读指定 docPath 全部块的辅助类

**验证**：`/api/docs` 返回分页列表且 ext 筛选生效；`/api/doc/raw` 下载 docx 与原文件字节一致（fc 比对）；`/api/doc/text?seq=3` 返回第 3 块文本与 heading；构造 `..%2F` 路径请求被 400 拒绝。

### Task 11: MCP 服务端

**文件**：
- `kbapp/mcp/KbSearchTools.java`：`@Tool kb_search(query, topK, folder, maxPerDoc)` → 片段列表 JSON；`@Tool kb_open(docPath, seq)` → 单块/整篇文本（Task 10 的 DocReader 复用）
- `application.yml` 增加 spring-ai mcp server 配置：`spring.ai.mcp.server.name=kb`、`stdio=false`（webmvc SSE）、端点 `/mcp`
- 工具开关：配置 `mcp.enabled`，false 时 `@ConditionalOnProperty` 不装配

**验证**：启动后 `claude mcp add --transport sse kb http://localhost:8080/mcp`，在 Claude Code 里问"帮我检索知识库：网络类资源申请环节"，确认调用 kb_search 返回真实片段；kb_open 取一个块确认返回原文。

### Task 12: 前端脚手架与设计 token

**文件**：
- `web/`：`npm create vite@latest . -- --template vue`（Vue3+JS，不用 TS 降复杂度）；依赖 `vue-router@4`；`vite.config.js` 加 `server.proxy['/api']='http://localhost:8080'`
- `src/styles/tokens.css`：从原型照搬 `:root` 变量（22px 圆角、阴影、`#5A6BFF`、mint/amber/rose、径向渐变背景、字体栈）+ 全局 reset
- `src/App.vue`：顶栏横向导航（logo + 智能问答/文档库/设置/检索测试 + 右侧索引状态灯，均按原型样式）+ `<router-view>`
- `src/router/index.js`：四路由；`src/api/index.js`：fetch 封装（JSON + SSE 工具函数）

**验证**：`npm run dev` 起，四页空壳可切换，视觉与原型外壳一致（顶栏、圆角、背景渐变）；`/api/settings` 经代理可达。

### Task 13: 智能问答页

**文件**：
- `src/views/AskView.vue`：三栏（会话列表 / 聊天流）——会话列表（新会话、今天/昨天/更早分组）；聊天流（q/ans 气泡、流式光标动画、引用角标 `.cite`、来源卡片 `.srcrow`、回答头部"基于 N 个片段"+`⤓ 导出 MD`）；范围 chips（选项来自 `/api/docs` 的 folder 聚合去重 + 全部）；底部 askbar；右上厂商/模型胶囊（点击下拉切换 activeChat，调 PUT /api/settings）
- `src/components/AskComposer.vue`、`src/components/MessageBubble.vue`（SSE 消费：POST fetch + ReadableStream 解析 delta/done）
- 导出 MD：前端把当前问答对拼 Markdown（含来源列表）触发下载

**验证**：联调——提问流式出字、引用角标与来源卡片渲染、导出的 md 内容完整；切换会话历史回放正确；范围选择后 sources 全部落在该 folder。

### Task 14: 文档库页 + 预览抽屉

**文件**：
- `src/views/LibraryView.vue`：类型 chips（ext 聚合）+ 搜索框（/api/docs/search）+ 文档卡片列表（类型徽标色映射、路径、分块数、摘要）
- `src/components/PreviewDrawer.vue`：右侧滑出抽屉（原型样式）——头部文件信息、标签页（预览/命中片段）、底部按钮；按扩展名动态选渲染器：`pdf`→`pdfjs-dist`；`docx`→`mammoth`（browser build）；`xlsx/xls`→`xlsx`(SheetJS，多 sheet 标签)；`md/txt/sql/java/py/...`→`highlight.js`；`html`→沙箱 iframe（srcdoc）；`pptx`→`/api/doc/text` 按页文本；其余→提示不支持 + "打开原文件"
- 命中定位：`/api/search` 结果带 seq，抽屉"命中片段"标签列出该文档命中块并可滚动定位

**验证**：联调——对 pdf/docx/xlsx/md/html/pptx 各点开一次预览确认渲染；搜索命中点卡片 → 抽屉"命中片段"显示带 mark 的块。

### Task 15: 设置页 + 检索测试页

**文件**：
- `src/views/SettingsView.vue`：四卡片按原型——LLM 厂商选择（可点选高亮 + 新增/删除厂商表单）、当前厂商配置（base_url/key/model/embedding，测试连通按钮）、索引与数据源（路径输入+浏览[浏览器无法选目录则手输+校验存在]、统计、重建索引进度条[SSE/轮询 /api/index/status]、awaiting_confirm 时显示估算与确认按钮）、MCP 服务（状态、工具开关、复制接入命令）
- `src/views/RetrieveTestView.vue`：输入框 + 测试按钮 → /api/search(vector=true) 块列表（RRF 分、kw/vec 徽标、路径/块号/heading）；顶部参数面板（candidate/rrf_k/max_per_doc/analyzer，改动 PUT /api/settings 后即测）

**验证**：联调——改 source_root 到 `新炬/两翼` 的子目录 rebuild 生效；测试连通对错误 key 返回失败提示；检索测试页对比 analyzer=smart|bigram 结果差异可见。

### Task 16: 交付形态（静态托管）与整体联调

**文件**：
- `web/vite.config.js`：`build.outDir: '../server/src/main/resources/static'`
- 后端加 SPA fallback：`WebMvcConfigurer` 把非 `/api`、非 `/mcp` 的 GET 转发 `index.html`
- `docs/` 更新：README 简版（启动步骤：mvn spring-boot:run 即整体可用 / 开发期双进程）

**验证**：`npm run build` → 只起 Spring Boot（8080）→ 浏览器 8080 完整走通：提问（流式+引用+预览定位）、文档库、设置重建索引、检索测试、`claude mcp add` 检索——全部通过后本计划完成。

## Self-Review

- 任务粒度：17 个任务，每个可独立编译/运行验证；Task 5→6、7→9、10→14 有依赖顺序，1/2/3/4/8/12 相对独立可提前
- 占位符检查：无 TBD/TODO；版本号、路径、字段、接口全部实写
- 与规格一致性：字段表、DDL、API 路径、RRF 参数、护栏逻辑均引自设计文档 v2 对应章节
- 风险前置：分析器 A/B（Task 7）、成本护栏（Task 6）都在依赖它们的任务之前
- 无测试任务（按用户约束）；无 commit/push 步骤
- 遗留：`.kb` 若用户未删干净，`lucene/` 子目录由程序自建，不冲突

## Execution Handoff

- 执行方式：本会话内按任务顺序推进（subagent-driven-development 因其默认按任务提交，与"不主动 commit"约束冲突，不采用；如需并行用 fork 子代理但保持不提交）
- 每 2-3 个任务向用户展示一次可感知进展（如索引建成、首次问答成功）
- 计划执行中的设计偏离记录回本文件末尾"执行备注"
