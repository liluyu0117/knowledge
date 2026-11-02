package kbapp.ingest;

import kbapp.config.AppConfig;
import kbapp.config.ConfigService;
import kbapp.llm.EmbeddingClient;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 增量索引构建（设计文档 5.3）。
 *
 * <p>构建分四段，前端按阶段展示：scanning（扫描）→ parsing（解析分块，无需新向量的文件直接写入）
 * → awaiting_confirm（成本护栏，可确认 / 跳过向量化 / 超时自动放弃）→ embedding（向量化并写入）。
 *
 * <p>sig(size:mtime) 判文件变化 → chunk_hash 对账复用旧向量 → 需要新 embedding 的块超过阈值时暂停待确认，
 * 并给出准确的块数与字符数（解析结果暂存内存，不重复解析文件）。
 */
@Service
public class IndexBuilder {

    private static final Logger log = LoggerFactory.getLogger(IndexBuilder.class);

    /** 等待用户确认向量化的最长时间：超时自动放弃，避免占死全局构建槽。 */
    private static final long CONFIRM_TIMEOUT_MINUTES = 10;
    /** 每处理多少个文件提交一次（断点续传，中断后已向量化的块不重复花钱）。 */
    private static final int COMMIT_EVERY = 20;

    public static final String P_IDLE = "idle";
    public static final String P_SCANNING = "scanning";
    public static final String P_PARSING = "parsing";
    public static final String P_AWAITING = "awaiting_confirm";
    public static final String P_EMBEDDING = "embedding";
    public static final String P_DONE = "done";
    public static final String P_CANCELLED = "cancelled";
    public static final String P_ERROR = "error";

    public record Status(String phase, String kbId, String kbName,
                         int filesTotal, int filesDone,
                         int added, int updated, int removed,
                         int embedded, int needEmbed, int vectorFailed,
                         Integer vectorDim, String awaitingModel, Integer awaitingCount, Long awaitingChars,
                         String error, long startedAt, long updatedAt, int errors, String currentFile) {}

    /** 每个知识库最近一次构建的结果，持久化到 .kb/build/{kbId}.json。 */
    public record BuildInfo(String kbId, String kbName, String phase, long builtAt, long durationMs,
                            int files, int indexed, int chunks,
                            int added, int updated, int skipped, int removed,
                            int embedded, int vectorFailed, int errors, Integer vectorDim,
                            List<Map<String, String>> failed, List<String> empty,
                            Map<String, String> emptySigs) {}

    public record OldChunk(int seq, String chunkHash, float[] vector) {}

    /** 解析完成的块（向量可能为空，等待向量化阶段补齐）。 */
    private record PendingChunk(int seq, String heading, String text, String hash, float[] vec) {}

    /** 解析完成、等待向量化的文件。 */
    private record PendingFile(String rel, String title, String ext, String kind, String folder,
                               String fileHash, String sig, boolean isNew, List<PendingChunk> chunks) {
        int missingVectors() {
            int n = 0;
            for (PendingChunk c : chunks) if (c.vec() == null) n++;
            return n;
        }
        long missingChars() {
            long n = 0;
            for (PendingChunk c : chunks) if (c.vec() == null) n += c.text().length();
            return n;
        }
    }

    /** 可变构建进度，快照后发布为不可变 Status。 */
    private static final class Prog {
        String phase = P_IDLE;
        String kbId = "";
        String kbName = "";
        int filesTotal, filesDone, added, updated, removed, embedded, needEmbed, vectorFailed, errors;
        Integer vectorDim;
        String awaitingModel;
        Integer awaitingCount;
        Long awaitingChars;
        String error;
        long startedAt, updatedAt;
        String currentFile = "";

        Status snapshot() {
            return new Status(phase, kbId, kbName, filesTotal, filesDone, added, updated, removed,
                    embedded, needEmbed, vectorFailed, vectorDim, awaitingModel, awaitingCount,
                    awaitingChars, error, startedAt, updatedAt, errors, currentFile);
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper M =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final ConfigService config;
    private final FileScanner scanner;
    private final TikaExtractor extractor;
    private final Chunker chunker;
    private final ObjectProvider<EmbeddingClient> embeddingProvider;
    private final kbapp.retrieve.SearcherService searcherService;
    private final kbapp.kb.KbRegistry registry;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "index-builder");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<Status> status = new AtomicReference<>(new Prog().snapshot());
    private final Prog prog = new Prog();
    private final Map<String, BuildInfo> lastBuilds = new ConcurrentHashMap<>();

    private volatile CountDownLatch confirmLatch;
    private volatile boolean cancelled = false;
    private volatile boolean skipVectorThisRun = false;

    public IndexBuilder(ConfigService config, FileScanner scanner, TikaExtractor extractor,
                        Chunker chunker, ObjectProvider<EmbeddingClient> embeddingProvider,
                        kbapp.retrieve.SearcherService searcherService,
                        kbapp.kb.KbRegistry registry) {
        this.config = config;
        this.scanner = scanner;
        this.extractor = extractor;
        this.chunker = chunker;
        this.embeddingProvider = embeddingProvider;
        this.searcherService = searcherService;
        this.registry = registry;
        loadBuildInfos();
    }

    public Status status() { return status.get(); }
    public boolean isRunning() { return running.get(); }

    /** 全局最近一次构建时间（多库取最大）。 */
    public long lastBuildAt() {
        long t = 0;
        for (BuildInfo b : lastBuilds.values()) t = Math.max(t, b.builtAt());
        return t;
    }

    /** 指定知识库最近一次构建时间（0 = 从未构建）。 */
    public long lastBuildAt(String kbId) {
        BuildInfo b = lastBuilds.get(kbId == null || kbId.isBlank() ? "default" : kbId);
        return b == null ? 0 : b.builtAt();
    }

    public BuildInfo lastBuild(String kbId) {
        return lastBuilds.get(kbId == null || kbId.isBlank() ? "default" : kbId);
    }

    public Map<String, BuildInfo> lastBuilds() { return lastBuilds; }

    /** 全局失败文件总数（顶栏提示用）。 */
    public int failedCount() {
        int n = 0;
        for (BuildInfo b : lastBuilds.values()) n += b.failed() == null ? 0 : b.failed().size();
        return n;
    }

    /** 确认向量化；withVector=false 表示本次跳过向量化（仅建关键词索引）。 */
    public void confirmEmbed(boolean withVector) {
        skipVectorThisRun = !withVector;
        CountDownLatch l = confirmLatch;
        if (l != null) l.countDown();
    }

    /** 取消当前构建（含卡在等待确认的任务）。已写入的文件保留，不丢索引。 */
    public boolean cancel() {
        if (!running.get()) return false;
        cancelled = true;
        CountDownLatch l = confirmLatch;
        if (l != null) l.countDown();
        return true;
    }

    public boolean start(String kbId) {
        if (!running.compareAndSet(false, true)) return false;
        cancelled = false;
        skipVectorThisRun = false;
        AppConfig cfg = config.get();
        kbapp.kb.KbRegistry.KbEntry kb = registry.byId(kbId);
        if (kb == null || registry.docsRoot(kb) == null) {
            running.set(false);
            String msg = kb == null ? "知识库不存在: " + kbId : "未配置数据源目录";
            synchronized (prog) {
                prog.phase = P_ERROR;
                prog.error = msg;
                prog.kbId = kb == null ? kbId : kb.id();
                prog.kbName = kb == null ? "" : kb.name();
                prog.startedAt = System.currentTimeMillis();
                prog.updatedAt = prog.startedAt;
                status.set(prog.snapshot());
            }
            return false;
        }
        worker.submit(() -> {
            try {
                runBuild(cfg, kb);
            } catch (Throwable e) {
                // 单线程构建不容有失：任何 Throwable（含 Error）都要落到状态里
                log.error("索引构建失败", e);
                synchronized (prog) {
                    prog.phase = P_ERROR;
                    prog.error = e.getMessage() == null ? e.toString() : e.getMessage();
                    prog.updatedAt = System.currentTimeMillis();
                    prog.currentFile = "";
                    status.set(prog.snapshot());
                }
            } finally {
                running.set(false);
                confirmLatch = null;
            }
        });
        return true;
    }

    private void resetProg(kbapp.kb.KbRegistry.KbEntry kb, long started, String phase) {
        synchronized (prog) {
            prog.phase = phase;
            prog.kbId = kb.id();
            prog.kbName = kb.name();
            prog.filesTotal = 0;
            prog.filesDone = 0;
            prog.added = 0;
            prog.updated = 0;
            prog.removed = 0;
            prog.embedded = 0;
            prog.needEmbed = 0;
            prog.vectorFailed = 0;
            prog.errors = 0;
            prog.vectorDim = null;
            prog.awaitingModel = null;
            prog.awaitingCount = null;
            prog.awaitingChars = null;
            prog.error = null;
            prog.startedAt = started;
            prog.updatedAt = System.currentTimeMillis();
            prog.currentFile = "";
            status.set(prog.snapshot());
        }
    }

    /** 更新并发布进度：写端串行改字段，读端只读不可变快照。 */
    private void update(java.util.function.Consumer<Prog> mutator) {
        synchronized (prog) {
            mutator.accept(prog);
            prog.updatedAt = System.currentTimeMillis();
            status.set(prog.snapshot());
        }
    }

    private void runBuild(AppConfig cfg, kbapp.kb.KbRegistry.KbEntry kb) throws Exception {
        long started = System.currentTimeMillis();
        Path root = registry.docsRoot(kb);
        Path luceneDir = registry.indexDir(kb);
        Files.createDirectories(luceneDir);

        resetProg(kb, started, P_SCANNING);

        List<Path> files = scanner.scan(root);
        update(p -> p.filesTotal = files.size());

        List<Map<String, String>> failedList = new ArrayList<>();
        // 空文档清单跨构建累积：跳过的文件本轮不处理，不能把它们的记录丢掉
        List<String> emptyFiles = new ArrayList<>();
        Map<String, String> emptySigs = new HashMap<>(); // 解析为空的文件 → sig，用于跳过重复解析
        int added = 0, updated = 0, skipped = 0, removed = 0, embedded = 0, vectorFailed = 0, errors = 0;
        int filesDone = 0;

        Analyzer analyzer = AnalyzerFactory.create(cfg.getRag().getAnalyzer());
        try (Directory dir = FSDirectory.open(luceneDir);
             IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig(analyzer)
                     .setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND))) {

            // 维度不匹配（换 embedding 模型）→ 全量重建
            // 注意：Integer 必须按值比较，用 != 会比对象引用，导致每次构建都误判维度变更而清空索引
            Integer existingDim = detectVectorDim(writer);
            Integer wantDim = wantVectorDim(cfg);
            if (existingDim != null && wantDim != null && existingDim.intValue() != wantDim.intValue()) {
                log.warn("向量维度 {} -> {} 变更，清空索引全量重建", existingDim, wantDim);
                writer.deleteAll();
            }
            final Integer vDim = wantDim;
            update(p -> p.vectorDim = vDim);
            EmbeddingClient emb = wantDim != null ? embeddingProvider.getIfAvailable() : null;
            String embModel = wantDim != null ? activeEmbeddingLabel(cfg) : "";

            // 既有文档对照表 + 向量覆盖情况
            Map<String, String[]> existing = new HashMap<>(); // docPath -> {sig, fileHash}
            Map<String, int[]> vecCoverage = new HashMap<>(); // docPath -> {总块数, 有向量块数}
            readIndexState(writer, existing, vecCoverage);

            // 需要处理的文件：内容变化 / 缺向量回填 / 上次失败重试
            Set<String> lastFailedPaths = new HashSet<>();
            Map<String, String> prevEmptySigs = new HashMap<>();
            BuildInfo prev = lastBuilds.get(kb.id());
            if (prev != null) {
                if (prev.failed() != null) {
                    for (Map<String, String> fm : prev.failed()) lastFailedPaths.add(fm.get("path"));
                }
                if (prev.emptySigs() != null) prevEmptySigs.putAll(prev.emptySigs());
            }
            // 继承上一轮的空文档记录，本轮按处理结果增量更新
            emptySigs.putAll(prevEmptySigs);
            if (prev != null && prev.empty() != null) emptyFiles.addAll(prev.empty());
            List<Path> toProcess = new ArrayList<>();
            for (Path f : files) {
                String rel = relOf(root, f);
                String[] old = existing.get(rel);
                String sig = sigOf(f);
                boolean changed;
                if (old == null) {
                    // 上次解析为空的文件（不在索引里）：sig 未变则跳过，避免每次重建都算"新增"
                    changed = !sig.equals(prevEmptySigs.get(rel));
                } else {
                    changed = !old[0].equals(sig);
                }
                // 内容未变但缺向量的文档（首次启用向量 / 上次跳过向量）也要重处理
                if (!changed && emb != null) {
                    int[] cov = vecCoverage.get(rel);
                    if (cov != null && cov[0] > 0 && cov[1] < cov[0]) changed = true;
                }
                if (!changed && lastFailedPaths.contains(rel)) changed = true; // 上次失败强制重试
                if (changed) toProcess.add(f);
                else skipped++;
            }
            int unchanged = skipped;
            filesDone = unchanged; // 未变化文件视为已完成，进度从实际完成度起算
            update(p -> {
                p.phase = P_PARSING;
                p.filesDone = unchanged;
            });

            // ── 解析阶段：解析 + 分块；无需新向量的文件直接写入索引（零 API 成本）──
            List<PendingFile> staged = new ArrayList<>();
            long needEmbed = 0, needChars = 0;
            int sinceCommit = 0;

            for (Path f : toProcess) {
                if (cancelled) break;
                String rel = relOf(root, f);
                update(p -> p.currentFile = rel);
                try {
                    PendingFile pf = parseFile(f, root, cfg, existing, writer);
                    int missing = emb == null ? 0 : pf.missingVectors();
                    if (missing == 0) {
                        writer.deleteDocuments(new Term(IndexSchema.DOC_PATH, pf.rel()));
                        for (PendingChunk c : pf.chunks()) writer.addDocument(toDoc(pf, c));
                        // 无文本的文件不产生索引文档，不计入新增/更新
                        if (!pf.chunks().isEmpty()) {
                            if (pf.isNew()) added++; else updated++;
                        }
                        if (pf.chunks().isEmpty()) {
                            if (!emptyFiles.contains(pf.rel())) emptyFiles.add(pf.rel());
                            emptySigs.put(pf.rel(), pf.sig());
                        } else {
                            emptyFiles.remove(pf.rel());
                            emptySigs.remove(pf.rel());
                        }
                        if (++sinceCommit >= COMMIT_EVERY) {
                            writer.commit();
                            searcherService.refresh(luceneDir);
                            sinceCommit = 0;
                        }
                    } else {
                        // 需要新向量：先暂存，等护栏确认后统一向量化，避免重复解析
                        staged.add(pf);
                        needEmbed += missing;
                        needChars += pf.missingChars();
                    }
                } catch (Throwable e) {
                    // 坏文件跳过，不拖垮整体构建；清单供面板展示与下次重试
                    errors++;
                    if (failedList.size() < 200) {
                        Map<String, String> fm = new LinkedHashMap<>();
                        fm.put("path", rel);
                        fm.put("reason", e.getMessage() == null ? e.toString() : e.getMessage());
                        failedList.add(fm);
                    }
                    log.warn("文件处理失败，跳过 {}: {}", rel, e.toString());
                }
                filesDone++;
                final int fd = filesDone, fa = added, fu = updated, fe = errors;
                final int ne = (int) Math.min(Integer.MAX_VALUE, needEmbed);
                final long nc = needChars;
                update(p -> {
                    p.filesDone = fd;
                    p.added = fa;
                    p.updated = fu;
                    p.errors = fe;
                    p.needEmbed = ne;
                    p.awaitingChars = nc;
                });
            }
            writer.commit();
            searcherService.refresh(luceneDir);

            // ── 成本护栏：待向量化块数超阈值时暂停等确认（10 分钟未确认自动放弃）──
            if (emb != null && !cancelled && needEmbed > cfg.getGuard().getEmbedThreshold()) {
                final int ne = (int) Math.min(Integer.MAX_VALUE, needEmbed);
                final long nc = needChars;
                final String model = embModel;
                update(p -> {
                    p.phase = P_AWAITING;
                    p.awaitingModel = model;
                    p.awaitingCount = ne;
                    p.awaitingChars = nc;
                    p.currentFile = "";
                });
                confirmLatch = new CountDownLatch(1);
                boolean confirmed = confirmLatch.await(CONFIRM_TIMEOUT_MINUTES, TimeUnit.MINUTES);
                confirmLatch = null;
                if (!confirmed) {
                    log.info("向量化确认超时（{} 分钟），自动放弃本次构建", CONFIRM_TIMEOUT_MINUTES);
                    cancelled = true;
                }
                if (cancelled) {
                    int[] st = countIndex(writer);
                    persistBuildInfo(kb, P_CANCELLED, System.currentTimeMillis() - started,
                            files.size(), st[0], st[1], added, updated, unchanged, removed,
                            embedded, vectorFailed, errors, failedList, emptyFiles, emptySigs);
                    final int fd = files.size();
                    update(p -> {
                        p.phase = P_CANCELLED;
                        p.filesDone = fd;
                        p.error = "向量化确认超时或已取消，本次构建已放弃";
                        p.awaitingCount = null;
                        p.awaitingModel = null;
                        p.awaitingChars = null;
                        p.currentFile = "";
                    });
                    return;
                }
                if (skipVectorThisRun) {
                    log.info("用户选择跳过向量化，本次仅建关键词索引（{} 块无向量）", needEmbed);
                    emb = null;
                }
            }

            // ── 向量化阶段：补齐向量并写入索引（跳过向量化时直接无向量写入）──
            if (!staged.isEmpty()) {
                update(p -> {
                    p.phase = P_EMBEDDING;
                    p.currentFile = "";
                });
                EmbeddingClient embedder = emb;
                int idx = 0;
                for (; idx < staged.size(); idx++) {
                    if (cancelled) break;
                    PendingFile pf = staged.get(idx);
                    update(p -> p.currentFile = pf.rel());
                    // 按文件批量向量化（embedAll 内部 16 条/请求），失败再逐条兜底
                    List<PendingChunk> chunks = pf.chunks();
                    float[][] vecs = new float[chunks.size()][];
                    List<String> texts = new ArrayList<>();
                    List<Integer> pos = new ArrayList<>();
                    for (int k = 0; k < chunks.size(); k++) {
                        PendingChunk c = chunks.get(k);
                        vecs[k] = c.vec();
                        if (embedder != null && c.vec() == null) {
                            texts.add(c.text());
                            pos.add(k);
                        }
                    }
                    if (!texts.isEmpty()) {
                        try {
                            List<float[]> got = embedder.embedAll(texts);
                            for (int t = 0; t < pos.size(); t++) {
                                float[] v = t < got.size() ? got.get(t) : null;
                                vecs[pos.get(t)] = v;
                                if (v != null) embedded++; else vectorFailed++;
                            }
                        } catch (Exception e) {
                            log.warn("批量向量化失败 {}（{}），改逐条重试", pf.rel(), e.toString());
                            for (int t = 0; t < pos.size(); t++) {
                                float[] v = null;
                                try {
                                    v = embedder.embed(texts.get(t));
                                    embedded++;
                                } catch (Exception ex) {
                                    vectorFailed++;
                                    log.warn("embedding 失败 {}#{}: {}", pf.rel(), pos.get(t), ex.toString());
                                }
                                vecs[pos.get(t)] = v;
                            }
                        }
                    }
                    List<PendingChunk> filled = new ArrayList<>(chunks.size());
                    for (int k = 0; k < chunks.size(); k++) {
                        PendingChunk c = chunks.get(k);
                        filled.add(new PendingChunk(c.seq(), c.heading(), c.text(), c.hash(), vecs[k]));
                    }
                    writer.deleteDocuments(new Term(IndexSchema.DOC_PATH, pf.rel()));
                    for (PendingChunk c : filled) writer.addDocument(toDoc(pf, c));
                    // 无文本的文件不产生索引文档，不计入新增/更新
                    if (!pf.chunks().isEmpty()) {
                        if (pf.isNew()) added++; else updated++;
                    }
                    if (filled.isEmpty()) {
                        if (!emptyFiles.contains(pf.rel())) emptyFiles.add(pf.rel());
                        emptySigs.put(pf.rel(), pf.sig());
                    } else {
                        emptyFiles.remove(pf.rel());
                        emptySigs.remove(pf.rel());
                    }
                    if (++sinceCommit >= COMMIT_EVERY) {
                        writer.commit();
                        searcherService.refresh(luceneDir);
                        sinceCommit = 0;
                    }
                    final int fa = added, fu = updated, fem = embedded, fvf = vectorFailed;
                    update(p -> {
                        p.added = fa;
                        p.updated = fu;
                        p.embedded = fem;
                        p.vectorFailed = fvf;
                    });
                }
                // 中途取消：剩余文件按无向量写入，保证文本仍可检索
                if (cancelled) {
                    for (PendingFile pf : staged.subList(idx, staged.size())) {
                        writer.deleteDocuments(new Term(IndexSchema.DOC_PATH, pf.rel()));
                        for (PendingChunk c : pf.chunks()) writer.addDocument(toDoc(pf, c));
                        // 无文本的文件不产生索引文档，不计入新增/更新
                        if (!pf.chunks().isEmpty()) {
                            if (pf.isNew()) added++; else updated++;
                        }
                    }
                }
            }

            // 源文件已删除 → 清理索引
            Set<String> onDisk = new HashSet<>();
            for (Path f : files) onDisk.add(relOf(root, f));
            for (String p : existing.keySet()) {
                if (!onDisk.contains(p)) {
                    writer.deleteDocuments(new Term(IndexSchema.DOC_PATH, p));
                    removed++;
                }
            }
            writer.commit();
            searcherService.refresh(luceneDir);

            int[] st = countIndex(writer);
            int docCount = st[0], chunkCount = st[1];
            long durationMs = System.currentTimeMillis() - started;
            boolean wasCancelled = cancelled || Thread.currentThread().isInterrupted();
            String finalPhase = wasCancelled ? P_CANCELLED : P_DONE;

            persistBuildInfo(kb, finalPhase, durationMs, files.size(), docCount, chunkCount,
                    added, updated, unchanged, removed, embedded, vectorFailed, errors,
                    failedList, emptyFiles, emptySigs);

            final int fTotal = files.size();
            final int fAdded = added, fUpdated = updated, fRemoved = removed;
            final int fEmbedded = embedded, fVectorFailed = vectorFailed, fErrors = errors;
            update(p -> {
                p.phase = finalPhase;
                p.filesTotal = fTotal;
                p.filesDone = fTotal;
                p.added = fAdded;
                p.updated = fUpdated;
                p.removed = fRemoved;
                p.embedded = fEmbedded;
                p.vectorFailed = fVectorFailed;
                p.errors = fErrors;
                p.awaitingCount = null;
                p.awaitingModel = null;
                p.awaitingChars = null;
                p.currentFile = "";
            });
            log.info("索引构建{}: 文件 {} 索引文档 {} 分块 {} 新增 {} 更新 {} 跳过 {} 删除 {} 向量化 {} 失败 {} 出错 {} 耗时 {}ms",
                    wasCancelled ? "取消" : "完成", fTotal, docCount, chunkCount,
                    added, updated, unchanged, removed, embedded, vectorFailed, errors, durationMs);
        } finally {
            analyzer.close();
        }
    }

    private Document toDoc(PendingFile pf, PendingChunk c) {
        Document d = new Document();
        IndexSchema.fill(d, pf.rel(), pf.title(), pf.ext(), pf.kind(), pf.folder(),
                c.seq(), c.heading(), c.text(), c.hash(), pf.fileHash(), pf.sig(), c.vec());
        return d;
    }

    /** 解析单个文件并复用旧向量（不写索引）。 */
    private PendingFile parseFile(Path f, Path root, AppConfig cfg,
                                  Map<String, String[]> existing, IndexWriter writer) throws Exception {
        String rel = relOf(root, f);
        String sig = sigOf(f);
        String fileHash = sha1(Files.readAllBytes(f));
        String title = stripExt(f.getFileName().toString());
        String ext = ScanFilter.extOf(f.getFileName().toString());
        String kind = kindOf(ext);
        String folder = folderOf(root, f);
        boolean isNew = !existing.containsKey(rel);

        Map<Integer, String> oldHashes = new HashMap<>();
        Map<Integer, float[]> oldVectors = new HashMap<>();
        if (!isNew) {
            try (DirectoryReader oldReader = DirectoryReader.open(writer)) {
                readOldChunks(new IndexSearcher(oldReader), rel, oldHashes, oldVectors);
            }
        }

        Extracted extracted = extractor.extract(f);
        List<Chunk> chunks = chunker.chunk(extracted, cfg.getIndex());
        List<PendingChunk> pcs = new ArrayList<>(chunks.size());
        for (Chunk c : chunks) {
            String hash = sha1(c.text().getBytes(StandardCharsets.UTF_8));
            float[] vec = oldVectors.get(c.seq());
            if (vec == null || !hash.equals(oldHashes.get(c.seq()))) vec = null; // hash 不一致不复用
            pcs.add(new PendingChunk(c.seq(), c.headingPath(), c.text(), hash, vec));
        }
        return new PendingFile(rel, title, ext, kind, folder, fileHash, sig, isNew, pcs);
    }

    /** 遍历索引内「未被删除」的文档（maxDoc 含已删除文档，会把统计算多）。 */
    private static void forEachLiveDoc(DirectoryReader reader, Set<String> fields,
                                       java.util.function.Consumer<Document> fn) throws IOException {
        for (org.apache.lucene.index.LeafReaderContext lc : reader.leaves()) {
            org.apache.lucene.index.LeafReader lr = lc.reader();
            org.apache.lucene.util.Bits live = lr.getLiveDocs();
            for (int i = 0; i < lr.maxDoc(); i++) {
                if (live != null && !live.get(i)) continue;
                fn.accept(lr.storedFields().document(i, fields));
            }
        }
    }

    /** 读取索引内 docPath→{sig,fileHash} 与向量覆盖情况（用 vecDim 判断，无需加载向量本体）。 */
    private void readIndexState(IndexWriter writer, Map<String, String[]> existing,
                                Map<String, int[]> vecCoverage) throws IOException {
        try (DirectoryReader reader = DirectoryReader.open(writer)) {
            forEachLiveDoc(reader, Set.of(IndexSchema.DOC_PATH, IndexSchema.SIG,
                    IndexSchema.FILE_HASH, IndexSchema.VEC_DIM), d -> {
                String p = IndexSchema.getString(d, IndexSchema.DOC_PATH);
                if (p.isEmpty()) return;
                existing.putIfAbsent(p, new String[]{
                        IndexSchema.getString(d, IndexSchema.SIG),
                        IndexSchema.getString(d, IndexSchema.FILE_HASH)});
                int[] c = vecCoverage.computeIfAbsent(p, k -> new int[]{0, 0});
                c[0]++;
                if (IndexSchema.getInt(d, IndexSchema.VEC_DIM) > 0) c[1]++;
            });
        }
    }

    /** 统计索引内文档数与块数（只算未删除文档）。 */
    private int[] countIndex(IndexWriter writer) {
        int[] chunks = {0};
        Set<String> paths = new HashSet<>();
        try (DirectoryReader reader = DirectoryReader.open(writer)) {
            forEachLiveDoc(reader, Set.of(IndexSchema.DOC_PATH), d -> {
                String p = IndexSchema.getString(d, IndexSchema.DOC_PATH);
                if (!p.isEmpty()) paths.add(p);
                chunks[0]++;
            });
        } catch (Exception e) {
            log.warn("统计索引规模失败: {}", e.toString());
        }
        return new int[]{paths.size(), chunks[0]};
    }

    private void persistBuildInfo(kbapp.kb.KbRegistry.KbEntry kb, String phase, long durationMs,
                                  int files, int docCount, int chunkCount,
                                  int added, int updated, int skipped, int removed,
                                  int embedded, int vectorFailed, int errors,
                                  List<Map<String, String>> failed, List<String> empty,
                                  Map<String, String> emptySigs) {
        BuildInfo info = new BuildInfo(kb.id(), kb.name(), phase, System.currentTimeMillis(), durationMs,
                files, docCount, chunkCount, added, updated, skipped, removed,
                embedded, vectorFailed, errors, prog.vectorDim,
                new ArrayList<>(failed), new ArrayList<>(empty), new HashMap<>(emptySigs));
        lastBuilds.put(kb.id(), info);
        try {
            Path dir = config.dataDir().resolve("build");
            Files.createDirectories(dir);
            M.writerWithDefaultPrettyPrinter().writeValue(dir.resolve(kb.id() + ".json").toFile(), info);
        } catch (Exception e) {
            log.warn("持久化构建信息失败: {}", e.toString());
        }
    }

    /** 启动加载：.kb/build/*.json；兼容迁移旧的单文件 .kb/build.json。 */
    private void loadBuildInfos() {
        Path dir = config.dataDir().resolve("build");
        try {
            Files.createDirectories(dir);
            try (var s = Files.list(dir)) {
                for (Path p : (Iterable<Path>) s.filter(x -> x.toString().endsWith(".json"))::iterator) {
                    try {
                        BuildInfo b = M.readValue(p.toFile(), BuildInfo.class);
                        if (b != null && b.kbId() != null) lastBuilds.put(b.kbId(), b);
                    } catch (Exception e) {
                        log.warn("读取构建信息失败 {}: {}", p.getFileName(), e.toString());
                    }
                }
            }
            Path legacy = config.dataDir().resolve("build.json");
            if (Files.exists(legacy) && !Files.exists(dir.resolve("default.json"))) {
                migrateLegacy(legacy, dir);
            }
        } catch (Exception e) {
            log.warn("加载构建信息失败: {}", e.toString());
        }
    }

    private void migrateLegacy(Path legacy, Path dir) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = M.readValue(legacy.toFile(), Map.class);
            long at = m.get("lastBuildAt") instanceof Number n ? n.longValue() : 0;
            if (at <= 0) return;
            int added = num(m, "added"), updated = num(m, "updated"),
                    skipped = num(m, "skipped"), removed = num(m, "removed"),
                    embedded = num(m, "embedded"), vf = num(m, "vectorFailed"),
                    errs = num(m, "errors");
            int total = num(m, "total");
            // 旧版只写 total：无分项计数时按「全部跳过」还原，避免面板显示 0
            if (added == 0 && updated == 0 && skipped == 0 && total > 0) skipped = total;
            int files = Math.max(total, added + updated + skipped);
            List<Map<String, String>> failed = new ArrayList<>();
            if (m.get("failed") instanceof List<?> l) {
                for (Object o : l) {
                    if (o instanceof Map<?, ?> mm) {
                        Map<String, String> fm = new LinkedHashMap<>();
                        fm.put("path", String.valueOf(mm.get("path")));
                        fm.put("reason", String.valueOf(mm.get("reason")));
                        failed.add(fm);
                    }
                }
            }
            BuildInfo b = new BuildInfo("default", "默认知识库", P_DONE, at, 0,
                    files, files, 0,
                    added, updated, skipped, removed, embedded, vf, errs, null,
                    failed, List.of(), java.util.Map.of());
            lastBuilds.put("default", b);
            M.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("default.json").toFile(), b);
            log.info("已从 build.json 迁移默认知识库构建信息");
        } catch (Exception e) {
            log.warn("迁移旧 build.json 失败: {}", e.toString());
        }
    }

    private static int num(Map<String, Object> m, String k) {
        return m.get(k) instanceof Number n ? n.intValue() : 0;
    }

    private void readOldChunks(IndexSearcher searcher, String rel,
                               Map<Integer, String> hashes, Map<Integer, float[]> vectors) throws IOException {
        var hits = searcher.search(new TermQuery(new Term(IndexSchema.DOC_PATH, rel)), Integer.MAX_VALUE);
        for (var sd : hits.scoreDocs) {
            Document d = searcher.storedFields().document(sd.doc, Set.of(
                    IndexSchema.SEQ, IndexSchema.CHUNK_HASH, IndexSchema.VECTOR_BIN, IndexSchema.VECTOR));
            IndexableField seqField = d.getField(IndexSchema.SEQ);
            if (seqField == null || seqField.numericValue() == null) continue;
            int seq = seqField.numericValue().intValue();
            hashes.put(seq, IndexSchema.getString(d, IndexSchema.CHUNK_HASH));
            float[] v = IndexSchema.getVector(d);
            if (v != null) vectors.put(seq, v);
        }
    }

    private Integer detectVectorDim(IndexWriter writer) throws IOException {
        try (DirectoryReader reader = DirectoryReader.open(writer)) {
            if (reader.maxDoc() == 0) return null;
            var hits = new IndexSearcher(reader).search(new org.apache.lucene.search.MatchAllDocsQuery(), 1);
            if (hits.scoreDocs.length == 0) return null;
            Document d = reader.storedFields().document(hits.scoreDocs[0].doc,
                    Set.of(IndexSchema.VEC_DIM, IndexSchema.VECTOR));
            int dim = IndexSchema.getInt(d, IndexSchema.VEC_DIM);
            if (dim > 0) return dim;
            // 兼容旧索引（无 vecDim 字段且向量未存储时返回 null）
            float[] v = IndexSchema.getVector(d);
            return v == null ? null : Integer.valueOf(v.length);
        }
    }

    private Integer wantVectorDim(AppConfig cfg) {
        AppConfig.Provider p = cfg.providerByName(cfg.getActiveEmbedding());
        if (p == null || p.getApiKey().isBlank() || p.getEmbeddingModel().isBlank()) return null;
        return p.getEmbeddingDim() > 0 ? p.getEmbeddingDim() : null;
    }

    private String activeEmbeddingLabel(AppConfig cfg) {
        AppConfig.Provider p = cfg.providerByName(cfg.getActiveEmbedding());
        return p == null ? "" : p.getName() + "/" + p.getEmbeddingModel();
    }

    private static String relOf(Path root, Path f) {
        return root.relativize(f).toString().replace('\\', '/');
    }

    private static String sigOf(Path f) throws IOException {
        var st = Files.readAttributes(f, java.nio.file.attribute.BasicFileAttributes.class);
        return st.size() + ":" + st.lastModifiedTime().toMillis();
    }

    private static String sha1(byte[] data) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(data);
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private static String kindOf(String ext) {
        return switch (ext) {
            case ".docx", ".doc" -> "word";
            case ".xlsx", ".xls" -> "excel";
            case ".pptx", ".ppt" -> "ppt";
            case ".pdf" -> "pdf";
            case ".sql" -> "sql";
            case ".html", ".htm" -> "html";
            default -> "text";
        };
    }

    private static String folderOf(Path root, Path f) {
        Path parent = f.getParent();
        if (parent == null) return "";
        Path rel = root.relativize(parent);
        return rel.toString().replace('\\', '/');
    }

    @PreDestroy
    public void shutdown() {
        worker.shutdownNow();
    }
}
