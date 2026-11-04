package kbapp.web;

import kbapp.ingest.IndexBuilder;
import kbapp.retrieve.HybridSearchService;
import kbapp.retrieve.SearchHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import kbapp.kb.KbRegistry;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 多知识库管理：列表 / 新建（复制源目录 + 自动构建）/ 删除 / 单独重建。 */
@RestController
@RequestMapping("/api/kb")
public class KbController {

    private final KbRegistry registry;
    private final IndexBuilder builder;
    private final HybridSearchService searchSvc;
    private final kbapp.retrieve.DocMetaStore docMeta;
    private final kbapp.ingest.UrlIngestService urlIngest;
    private static final Logger log = LoggerFactory.getLogger(KbController.class);

    public KbController(KbRegistry registry, IndexBuilder builder, HybridSearchService searchSvc,
                        kbapp.retrieve.DocMetaStore docMeta, kbapp.ingest.UrlIngestService urlIngest) {
        this.registry = registry;
        this.builder = builder;
        this.searchSvc = searchSvc;
        this.docMeta = docMeta;
        this.urlIngest = urlIngest;
    }

    @GetMapping("/s")
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        List<KbRegistry.KbEntry> entries = new ArrayList<>(registry.list());
        entries.sort(Comparator.comparing(KbRegistry.KbEntry::createdAt));
        for (KbRegistry.KbEntry e : entries) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id());
            m.put("name", e.name());
            m.put("path", e.fixed() ? "(跟随设置页数据源目录)" : e.sourcePath());
            m.put("fixed", e.fixed());
            out.add(m);
        }
        return out;
    }

    public record CreateBody(String name, String path) {}

    /** 新建知识库：复制源目录 → 自动构建（含向量）。 */
    @PostMapping("/create")
    public Map<String, Object> create(@RequestBody CreateBody body) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (body.name() == null || body.name().isBlank()
                || body.path() == null || body.path().isBlank()) {
            m.put("ok", false);
            m.put("error", "名称和路径不能为空");
            return m;
        }
        String name = body.name().trim();
        String path = body.path().trim();
        // 幂等防重：前端重复点击/双击提交时不重复建库
        for (KbRegistry.KbEntry k : registry.list()) {
            boolean sameName = k.name().equals(name);
            boolean samePath = k.sourcePath() != null && k.sourcePath().equals(path);
            if (sameName && samePath) {
                m.put("ok", true);
                m.put("id", k.id());
                m.put("name", k.name());
                m.put("existed", true);
                return m;
            }
            if (sameName) {
                m.put("ok", false);
                m.put("error", "已存在同名知识库「" + k.name() + "」（源目录：" + k.sourcePath() + "），请换个名称");
                return m;
            }
        }
        try {
            KbRegistry.KbEntry e = registry.create(name, path);
            registry.copySource(path, e.id());
            builder.start(e.id());
            m.put("ok", true);
            m.put("id", e.id());
            m.put("name", e.name());
        } catch (IOException | IllegalStateException ex) {
            m.put("ok", false);
            m.put("error", ex.getMessage() == null ? ex.toString() : ex.getMessage());
        }
        return m;
    }

    /**
     * 知识库文件树：目录层级 + 每文件真实索引状态 + 分块数 + 该库统计。
     *
     * <p>status 取值：ok（已索引）/ empty（解析无文本）/ skipped（类型不支持或空文件）/
     * pending（可索引但未入索引）/ stale（索引后源文件已变更）/ fail（构建失败）。
     */
    @GetMapping("/{id}/tree")
    public Map<String, Object> tree(@PathVariable String id) throws IOException {
        KbRegistry.KbEntry kb = registry.byId(id);
        Map<String, Object> m = new LinkedHashMap<>();
        if (kb == null) { m.put("error", "知识库不存在"); return m; }
        Path root = registry.docsRoot(kb);
        m.put("name", kb.name());
        m.put("root", root == null ? "" : root.toString());

        IndexBuilder.BuildInfo last = builder.lastBuild(id);
        Map<String, String> failedReason = new java.util.HashMap<>();
        java.util.Set<String> emptyDocs = new java.util.HashSet<>();
        if (last != null) {
            if (last.failed() != null) {
                for (Map<String, String> fm : last.failed()) failedReason.put(fm.get("path"), fm.get("reason"));
            }
            if (last.empty() != null) emptyDocs.addAll(last.empty());
        }

        // 索引内：docPath -> 块数、docPath -> sig
        Map<String, Integer> chunkCounts = new java.util.HashMap<>();
        Map<String, String> sigs = new java.util.HashMap<>();
        try (var reader = org.apache.lucene.index.DirectoryReader.open(
                org.apache.lucene.store.FSDirectory.open(registry.indexDir(kb)))) {
            // 只遍历未删除文档：maxDoc 含历史删除段，会把分块数算多
            for (var lc : reader.leaves()) {
                var lr = lc.reader();
                var live = lr.getLiveDocs();
                for (int i = 0; i < lr.maxDoc(); i++) {
                    if (live != null && !live.get(i)) continue;
                    var d = lr.storedFields().document(i, java.util.Set.of(
                            kbapp.ingest.IndexSchema.DOC_PATH, kbapp.ingest.IndexSchema.SIG));
                    String p = kbapp.ingest.IndexSchema.getString(d, kbapp.ingest.IndexSchema.DOC_PATH);
                    if (p.isEmpty()) continue;
                    chunkCounts.merge(p, 1, Integer::sum);
                    sigs.putIfAbsent(p, kbapp.ingest.IndexSchema.getString(d, kbapp.ingest.IndexSchema.SIG));
                }
            }
        } catch (Exception ignored) { }

        List<Map<String, Object>> children = new ArrayList<>();
        int[] stats = new int[7];   // files, indexable, skipped, pending, stale, failed, empty
        long[] chunkSum = new long[1];
        if (root != null && Files.exists(root)) {
            buildTree(children, root, root, failedReason, emptyDocs, chunkCounts, sigs, stats, chunkSum);
        }

        Map<String, Object> st = new LinkedHashMap<>();
        st.put("files", stats[0]);
        st.put("indexable", stats[1]);
        st.put("skipped", stats[2]);
        st.put("pending", stats[3]);
        st.put("stale", stats[4]);
        st.put("failed", stats[5]);
        st.put("empty", stats[6]);
        st.put("indexed", chunkCounts.size());
        st.put("chunks", chunkSum[0]);
        st.put("lastBuiltAt", last == null ? 0L : last.builtAt());
        st.put("lastPhase", last == null ? "" : last.phase());
        st.put("lastDurationMs", last == null ? 0L : last.durationMs());
        m.put("stats", st);
        m.put("children", children);
        return m;
    }

    @SuppressWarnings("unchecked")
    private void buildTree(List<Map<String, Object>> out, Path dir, Path root,
                           Map<String, String> failedReason, java.util.Set<String> emptyDocs,
                           Map<String, Integer> chunkCounts, Map<String, String> sigs,
                           int[] stats, long[] chunkSum) throws IOException {
        List<Map<String, Object>> folders = new ArrayList<>();
        List<Map<String, Object>> files = new ArrayList<>();
        List<Path> entries = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            for (Path p : (Iterable<Path>) stream.sorted(Comparator.comparing(Path::getFileName))::iterator) {
                entries.add(p);
            }
        }
        for (Path p : entries) {
            String rel = root.relativize(p).toString().replace(java.io.File.separatorChar, '/');
            if (Files.isDirectory(p)) {
                Map<String, Object> fm = new LinkedHashMap<>();
                fm.put("name", p.getFileName().toString());
                fm.put("type", "folder");
                fm.put("path", rel);
                List<Map<String, Object>> kids = new ArrayList<>();
                int[] kidStats = new int[7];
                long[] kidChunks = new long[1];
                buildTree(kids, p, root, failedReason, emptyDocs, chunkCounts, sigs, kidStats, kidChunks);
                // 子目录统计并入当前层，保证根统计=全库
                for (int i = 0; i < stats.length; i++) stats[i] += kidStats[i];
                chunkSum[0] += kidChunks[0];
                int kidFiles = 0;
                for (Map<String, Object> k : kids) {
                    Object n = k.get("fileCount");
                    kidFiles += n instanceof Number num ? num.intValue() : 0;
                }
                fm.put("children", kids);
                fm.put("fileCount", kidFiles);
                fm.put("chunkSum", kidChunks[0]);
                fm.put("status", worstStatus(kidStats));
                folders.add(fm);
            } else {
                String name = p.getFileName().toString();
                long size;
                try { size = Files.size(p); } catch (Exception e) { size = 0; }
                boolean indexable = kbapp.ingest.ScanFilter.accept(name) && size > 0;
                String status;
                if (!indexable) status = "skipped";
                else if (failedReason.containsKey(rel)) status = "fail";
                else if (emptyDocs.contains(rel)) status = "empty";
                else if (!chunkCounts.containsKey(rel)) status = "pending";
                else if (!sigOf(p).equals(sigs.get(rel))) status = "stale";
                else status = "ok";

                Map<String, Object> fm = new LinkedHashMap<>();
                fm.put("name", name);
                fm.put("type", "file");
                fm.put("path", rel);
                fm.put("status", status);
                fm.put("size", size);
                if (failedReason.containsKey(rel)) fm.put("failReason", failedReason.get(rel));
                fm.put("chunkCount", chunkCounts.getOrDefault(rel, 0));
                fm.put("fileCount", 1);

                stats[0]++;
                if (indexable) stats[1]++;
                chunkSum[0] += chunkCounts.getOrDefault(rel, 0);
                switch (status) {
                    case "skipped" -> stats[2]++;
                    case "pending" -> stats[3]++;
                    case "stale" -> stats[4]++;
                    case "fail" -> stats[5]++;
                    case "empty" -> stats[6]++;
                    default -> { }
                }
                files.add(fm);
            }
        }
        out.addAll(folders);
        out.addAll(files);
    }

    /** 目录聚合状态：只在真有失败/未建/待更新时冒泡（空文档不算问题，不上浮到目录）。 */
    private String worstStatus(int[] s) {
        if (s[5] > 0) return "fail";
        if (s[3] > 0) return "pending";
        if (s[4] > 0) return "stale";
        return "ok";
    }

    private static String sigOf(Path f) {
        try {
            var st = Files.readAttributes(f, java.nio.file.attribute.BasicFileAttributes.class);
            return st.size() + ":" + st.lastModifiedTime().toMillis();
        } catch (Exception e) {
            return "";
        }
    }

    /** 文档列表（供知识库页"文档"标签：检索预览）。 */
    @GetMapping("/{id}/docs")
    public Map<String, Object> docs(@PathVariable String id,
                                    @RequestParam(required = false) String folder,
                                    @RequestParam(required = false) String ext,
                                    @RequestParam(required = false) String q,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        Map<String, Object> out = new LinkedHashMap<>();
        // 全文检索模式
        if (q != null && !q.isBlank()) {
            HybridSearchService.Result r = searchSvc.search(q, 20, folder, 2, true, id);
            List<Map<String, Object>> items = new ArrayList<>();
            for (SearchHit h : r.hits()) {
                Map<String, Object> it = new LinkedHashMap<>();
                it.put("docPath", h.docPath());
                it.put("docTitle", h.docTitle());
                it.put("ext", h.ext());
                it.put("kind", h.kind());
                it.put("snippet", h.snippetHtml());
                it.put("hitSeq", h.seq());
                items.add(it);
            }
            out.put("total", items.size());
            out.put("items", items);
            return out;
        }
        // 普通列表：从该库索引聚合
        List<Map<String, Object>> items = new ArrayList<>();
        int total = 0;
        int from = Math.max(0, page * size);
        try (var reader = org.apache.lucene.index.DirectoryReader.open(
                org.apache.lucene.store.FSDirectory.open(registry.indexDir(registry.byId(id))))) {
            Map<String, Object> first = new LinkedHashMap<>();
            Map<String, Integer> counts = new java.util.TreeMap<>();
            var live = org.apache.lucene.index.MultiBits.getLiveDocs(reader);
            for (int i = 0; i < reader.maxDoc(); i++) {
                if (live != null && !live.get(i)) continue; // 跳过已删除文档
                var d = reader.storedFields().document(i, java.util.Set.of(
                        kbapp.ingest.IndexSchema.DOC_PATH, kbapp.ingest.IndexSchema.DOC_TITLE,
                        kbapp.ingest.IndexSchema.EXT, kbapp.ingest.IndexSchema.KIND,
                        kbapp.ingest.IndexSchema.FOLDER, kbapp.ingest.IndexSchema.BODY));
                String pth = kbapp.ingest.IndexSchema.getString(d, kbapp.ingest.IndexSchema.DOC_PATH);
                String fld = kbapp.ingest.IndexSchema.getString(d, kbapp.ingest.IndexSchema.FOLDER);
                if (folder != null && !folder.isBlank()) {
                    if (!fld.equals(folder) && !fld.startsWith(folder + "/")) continue;
                }
                String ex = kbapp.ingest.IndexSchema.getString(d, kbapp.ingest.IndexSchema.EXT);
                if (ext != null && !ext.isBlank() && !ext.equalsIgnoreCase(ex)) continue;
                counts.merge(pth, 1, Integer::sum);
                first.putIfAbsent(pth, Integer.valueOf(i));
            }
            List<String> paths = new ArrayList<>(counts.keySet());
            total = paths.size();
            for (int i = from; i < Math.min(paths.size(), from + size); i++) {
                String pth = paths.get(i);
                var d = reader.storedFields().document(((Number) first.get(pth)).intValue());
                Map<String, Object> it = new LinkedHashMap<>();
                it.put("docPath", pth);
                it.put("docTitle", kbapp.ingest.IndexSchema.getString(d, kbapp.ingest.IndexSchema.DOC_TITLE));
                it.put("ext", kbapp.ingest.IndexSchema.getString(d, kbapp.ingest.IndexSchema.EXT));
                it.put("kind", kbapp.ingest.IndexSchema.getString(d, kbapp.ingest.IndexSchema.KIND));
                it.put("folder", kbapp.ingest.IndexSchema.getString(d, kbapp.ingest.IndexSchema.FOLDER));
                it.put("chunkCount", counts.get(pth));
                String body = kbapp.ingest.IndexSchema.getString(d, kbapp.ingest.IndexSchema.BODY);
                it.put("snippet", body.length() > 120 ? body.substring(0, 120) + "…" : body);
                items.add(it);
            }
        } catch (Exception e) {
            log.warn("文档列表读取失败: {}", e.toString());
        }
        out.put("total", total);
        out.put("page", page);
        out.put("size", size);
        out.put("items", items);
        return out;
    }

    @PostMapping("/{id}/rebuild")
    public Map<String, Object> rebuild(@PathVariable String id,
                                       @RequestParam(defaultValue = "false") boolean forceFull) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("started", builder.start(id, forceFull));
        return m;
    }

    /** 单文件上传上限：200MB。 */
    private static final long MAX_UPLOAD_BYTES = 200L * 1024 * 1024;

    /**
     * 上传文档到知识库副本目录（原始字节流，不依赖 multipart）。
     * folder 为相对子目录（空 = 根目录），name 为文件名；同名文件直接覆盖。
     */
    @PostMapping(value = "/{id}/upload", consumes = org.springframework.http.MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public Map<String, Object> upload(@PathVariable String id,
                                      @RequestParam("name") String name,
                                      @RequestParam(defaultValue = "") String folder,
                                      @RequestParam(defaultValue = "") String tags,
                                      jakarta.servlet.http.HttpServletRequest req) {
        Map<String, Object> m = new LinkedHashMap<>();
        KbRegistry.KbEntry kb = registry.byId(id);
        if (kb == null) { m.put("ok", false); m.put("error", "知识库不存在"); return m; }
        Path root = registry.docsRoot(kb);
        Path target;
        try {
            target = safeResolve(root, folder, name);
        } catch (IllegalArgumentException ex) {
            m.put("ok", false);
            m.put("error", ex.getMessage());
            return m;
        }
        if (req.getContentLengthLong() > MAX_UPLOAD_BYTES) {
            m.put("ok", false);
            m.put("error", "文件超过 200MB 上限");
            return m;
        }
        try {
            Files.createDirectories(target.getParent());
            long n;
            try (var in = req.getInputStream();
                 var out = Files.newOutputStream(target, java.nio.file.StandardOpenOption.CREATE,
                         java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
                n = in.transferTo(out);
            }
            if (n == 0) {
                Files.deleteIfExists(target);
                m.put("ok", false);
                m.put("error", "文件内容为空");
                return m;
            }
            m.put("ok", true);
            m.put("path", root.relativize(target).toString().replace('\\', '/'));
            m.put("size", n);
            // 上传时顺带落标签（逗号分隔），不阻塞上传结果
            List<String> tagList = splitTags(tags);
            if (!tagList.isEmpty()) {
                docMeta.setTagsBulk(kb.id(), Map.of(m.get("path").toString(), tagList), "upload");
                m.put("tags", tagList);
            }
        } catch (IOException e) {
            m.put("ok", false);
            m.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
        }
        return m;
    }

    /**
     * 在知识库内新建文件夹（物理目录，构建时按目录归档）。
     * path 为父目录相对路径（空 = 根目录），name 为文件夹名。
     */
    @PostMapping("/{id}/folder")
    public Map<String, Object> createFolder(@PathVariable String id,
                                            @RequestParam(defaultValue = "") String path,
                                            @RequestParam("name") String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        KbRegistry.KbEntry kb = registry.byId(id);
        if (kb == null) { m.put("ok", false); m.put("error", "知识库不存在"); return m; }
        Path root = registry.docsRoot(kb);
        if (name == null || name.isBlank()) { m.put("ok", false); m.put("error", "文件夹名不能为空"); return m; }
        if (name.contains("/") || name.contains("\\") || name.startsWith(".")) {
            m.put("ok", false); m.put("error", "文件夹名不合法"); return m;
        }
        Path dir = root;
        if (path != null && !path.isBlank()) {
            String f = path.replace('\\', '/');
            for (String part : f.split("/")) {
                if (part.isBlank() || part.equals(".") || part.equals("..")) {
                    m.put("ok", false); m.put("error", "父目录不合法"); return m;
                }
            }
            dir = root.resolve(f).normalize();
            if (!dir.startsWith(root) || !Files.isDirectory(dir)) {
                m.put("ok", false); m.put("error", "父目录不存在"); return m;
            }
        }
        Path target = dir.resolve(name).normalize();
        if (!target.startsWith(root)) { m.put("ok", false); m.put("error", "路径不合法"); return m; }
        if (Files.exists(target)) { m.put("ok", false); m.put("error", "同名文件或文件夹已存在"); return m; }
        try {
            Files.createDirectories(target);
            m.put("ok", true);
            m.put("path", root.relativize(target).toString().replace('\\', '/'));
        } catch (IOException e) {
            m.put("ok", false);
            m.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
        }
        return m;
    }

    /**
     * 删除知识库内的文件夹（连同其中全部文件）。
     * 目录非空且未带 confirm=true 时返回 needConfirm，由前端做二次确认。
     */
    @DeleteMapping("/{id}/folder")
    public Map<String, Object> deleteFolder(@PathVariable String id,
                                            @RequestParam("path") String relPath,
                                            @RequestParam(defaultValue = "false") boolean confirm) {
        Map<String, Object> m = new LinkedHashMap<>();
        KbRegistry.KbEntry kb = registry.byId(id);
        if (kb == null) { m.put("ok", false); m.put("error", "知识库不存在"); return m; }
        Path root = registry.docsRoot(kb);
        if (relPath == null || relPath.isBlank()) { m.put("ok", false); m.put("error", "根目录不可删除"); return m; }
        Path target = root.resolve(relPath.replace('\\', '/')).normalize();
        if (!target.startsWith(root) || target.equals(root) || !Files.isDirectory(target)) {
            m.put("ok", false); m.put("error", "文件夹不存在或路径不合法"); return m;
        }
        List<Path> files = new ArrayList<>();
        try (var s = Files.walk(target)) {
            s.filter(Files::isRegularFile).forEach(files::add);
        } catch (IOException e) {
            m.put("ok", false); m.put("error", e.toString()); return m;
        }
        if (!files.isEmpty() && !confirm) {
            m.put("ok", false);
            m.put("needConfirm", true);
            m.put("fileCount", files.size());
            m.put("error", "该文件夹包含 " + files.size() + " 个文件");
            return m;
        }
        try {
            // 先清这些文件的标签元数据，再自底向上删除目录树
            List<String> rels = new ArrayList<>();
            for (Path f : files) {
                String rel = root.relativize(f).toString().replace('\\', '/');
                rels.add(rel);
                docMeta.removeDoc(kb.id(), rel);
            }
            try (var s = Files.walk(target)) {
                s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { Files.delete(p); } catch (IOException ignored) { }
                });
            }
            // 让检索立即生效：从索引移除这些文档（构建中则跳过，由构建清理兜底）
            int idx = builder.removeDocs(id, rels);
            m.put("ok", true);
            m.put("removed", files.size());
            if (idx < 0) m.put("indexDeferred", true);
        } catch (IOException e) {
            m.put("ok", false);
            m.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
        }
        return m;
    }

    /** 删除知识库内的单个文件（只动副本，源目录不受影响）；删空后顺带清理空目录。 */
    @DeleteMapping("/{id}/file")
    public Map<String, Object> deleteFile(@PathVariable String id, @RequestParam("path") String relPath) {
        Map<String, Object> m = new LinkedHashMap<>();
        KbRegistry.KbEntry kb = registry.byId(id);
        if (kb == null) { m.put("ok", false); m.put("error", "知识库不存在"); return m; }
        Path root = registry.docsRoot(kb);
        Path target = root.resolve(relPath == null ? "" : relPath).normalize();
        if (!target.startsWith(root) || target.equals(root) || !Files.isRegularFile(target)) {
            m.put("ok", false);
            m.put("error", "文件不存在或路径不合法");
            return m;
        }
        try {
            Files.delete(target);
            String rel = relPath.replace('\\', '/');
            docMeta.removeDoc(kb.id(), rel);
            // 让检索立即生效：从索引移除该文档（构建中则跳过，由构建清理兜底）
            builder.removeDocs(id, List.of(rel));
            // 清理因此变空的目录，避免树里留下空壳
            Path parent = target.getParent();
            while (parent != null && !parent.equals(root)) {
                boolean empty;
                try (var s = Files.list(parent)) { empty = s.findAny().isEmpty(); }
                if (!empty) break;
                try { Files.delete(parent); } catch (IOException ignored) { break; }
                parent = parent.getParent();
            }
            m.put("ok", true);
        } catch (IOException e) {
            m.put("ok", false);
            m.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
        }
        return m;
    }

    /** 校验上传目标路径：文件名不带路径分隔符、扩展名在索引白名单内、目录不得越出知识库根。 */
    private Path safeResolve(Path root, String folder, String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("文件名不能为空");
        if (name.contains("/") || name.contains("\\") || name.startsWith(".")) {
            throw new IllegalArgumentException("文件名不合法");
        }
        if (!kbapp.ingest.ScanFilter.accept(name)) {
            throw new IllegalArgumentException("该类型不会被索引（支持 docx/pdf/xlsx/pptx/md/txt/sql/html/csv 等）");
        }
        Path dir = root;
        if (folder != null && !folder.isBlank()) {
            String f = folder.replace('\\', '/');
            for (String part : f.split("/")) {
                if (part.isBlank() || part.equals(".") || part.equals("..")) {
                    throw new IllegalArgumentException("目录不合法");
                }
            }
            dir = root.resolve(f).normalize();
            if (!dir.startsWith(root)) throw new IllegalArgumentException("目录不合法");
        }
        Path target = dir.resolve(name).normalize();
        if (!target.startsWith(root)) throw new IllegalArgumentException("路径不合法");
        return target;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> remove(@PathVariable String id) throws IOException {
        KbRegistry.KbEntry e = registry.byId(id);
        Map<String, Object> m = new LinkedHashMap<>();
        if (e == null) { m.put("ok", false); m.put("error", "知识库不存在"); return m; }
        if (e.fixed()) { m.put("ok", false); m.put("error", "内置知识库「" + e.name() + "」不可删除"); return m; }

        // 删除索引目录与复制的工作副本（源目录不动）；被占用而删不掉的必须如实上报
        List<String> leftover = new ArrayList<>(deleteTree(registry.indexDir(e)));
        leftover.addAll(deleteTree(registry.docsRoot(e)));
        registry.remove(id);
        docMeta.dropKb(id);
        m.put("ok", true);
        m.put("leftoverCount", leftover.size());
        m.put("leftover", leftover.size() > 20 ? leftover.subList(0, 20) : leftover);
        return m;
    }

    /**
     * 逐项删除并二次重试，返回仍未删掉的路径（通常是被预览器 / Office 占用的文件）。
     * 不再静默吞掉失败：调用方会把清单展示给用户。
     */
    private List<String> deleteTree(Path dir) throws IOException {
        List<String> failed = new ArrayList<>();
        if (dir == null || !Files.exists(dir)) return failed;
        List<Path> paths;
        try (var stream = Files.walk(dir)) {
            paths = stream.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path p : paths) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException ex) {
                failed.add(p.toString());
            }
        }
        if (!failed.isEmpty()) {
            try { Thread.sleep(200); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            failed.removeIf(p -> {
                try { return Files.deleteIfExists(Path.of(p)); } catch (Exception ex) { return false; }
            });
        }
        return failed;
    }

    // ───────────────────────── URL 网页接入 ─────────────────────────

    public record FetchBody(List<String> urls, String folder, List<String> tags) {}

    /** 抓取网页转 Markdown 存入知识库；只支持无需登录的静态页。 */
    @PostMapping("/{id}/fetch-url")
    public Map<String, Object> fetchUrl(@PathVariable String id, @RequestBody FetchBody body) {
        Map<String, Object> m = new LinkedHashMap<>();
        KbRegistry.KbEntry kb = registry.byId(id);
        if (kb == null) { m.put("ok", false); m.put("error", "知识库不存在"); return m; }
        if (body == null || body.urls() == null || body.urls().isEmpty()) {
            m.put("ok", false); m.put("error", "请填写至少一条 URL"); return m;
        }
        List<kbapp.ingest.UrlIngestService.UrlResult> results =
                urlIngest.fetchAll(kb.id(), body.urls(), body.folder() == null ? "" : body.folder());
        // 抓取成功的文档顺带落标签
        Map<String, List<String>> tagsByPath = new LinkedHashMap<>();
        for (var r : results) {
            if (r.ok() && body.tags() != null && !body.tags().isEmpty()) tagsByPath.put(r.path(), body.tags());
        }
        if (!tagsByPath.isEmpty()) docMeta.setTagsBulk(kb.id(), tagsByPath, "url");
        m.put("ok", true);
        m.put("results", results);
        long okCount = results.stream().filter(kbapp.ingest.UrlIngestService.UrlResult::ok).count();
        m.put("success", okCount);
        m.put("failed", results.size() - okCount);
        return m;
    }

    // ───────────────────────── 文档元数据（标签） ─────────────────────────

    /** 整库的标签表：{tags:[全库标签], docs:{path:{tags,source,updatedAt}}}。 */
    @GetMapping("/{id}/docmeta")
    public Map<String, Object> docMetaList(@PathVariable String id) {
        Map<String, Object> m = new LinkedHashMap<>();
        KbRegistry.KbEntry kb = registry.byId(id);
        if (kb == null) { m.put("ok", false); m.put("error", "知识库不存在"); return m; }
        m.put("ok", true);
        m.put("tags", docMeta.allTags(kb.id()));
        Map<String, Object> docs = new LinkedHashMap<>();
        for (var e : docMeta.all(kb.id()).entrySet()) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("tags", e.getValue().tags());
            d.put("source", e.getValue().source());
            d.put("updatedAt", e.getValue().updatedAt());
            docs.put(e.getKey(), d);
        }
        m.put("docs", docs);
        return m;
    }

    public record DocTagsBody(String path, List<String> tags) {}

    /** 设置单个文档的标签；tags 为空 = 清除。 */
    @PostMapping("/{id}/docmeta")
    public Map<String, Object> docMetaSet(@PathVariable String id, @RequestBody DocTagsBody body) {
        Map<String, Object> m = new LinkedHashMap<>();
        KbRegistry.KbEntry kb = registry.byId(id);
        if (kb == null) { m.put("ok", false); m.put("error", "知识库不存在"); return m; }
        if (body == null || body.path() == null || body.path().isBlank()) {
            m.put("ok", false); m.put("error", "缺少 path"); return m;
        }
        String path = body.path().trim().replace('\\', '/');
        docMeta.setTags(kb.id(), path, body.tags(), "manual");
        m.put("ok", true);
        m.put("path", path);
        m.put("tags", docMeta.tagsOf(kb.id(), path));
        return m;
    }

    /** 逗号/中文逗号分隔 → 去空去重。 */
    private static List<String> splitTags(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isBlank()) return out;
        for (String t : s.split("[,，;；]")) {
            String v = t.trim();
            if (!v.isEmpty() && !out.contains(v)) out.add(v);
        }
        return out;
    }
}
