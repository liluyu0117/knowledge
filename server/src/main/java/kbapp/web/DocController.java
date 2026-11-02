package kbapp.web;

import kbapp.config.ConfigService;
import kbapp.ingest.IndexBuilder;
import kbapp.ingest.IndexSchema;
import kbapp.ingest.ScanFilter;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.FSDirectory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 文档列表与预览（设计文档 13）。带聚合缓存：索引重建后自动失效。 */
@RestController
@RequestMapping("/api")
public class DocController {

    private final ConfigService config;
    private final kbapp.config.AppPaths paths;
    private final IndexBuilder indexBuilder;
    private final kbapp.kb.KbRegistry registry;

    /** 按知识库缓存（各库构建时间独立，不能共用一个 key）。 */
    private final Map<String, CachedDocs> docCache = new java.util.concurrent.ConcurrentHashMap<>();

    private record CachedDocs(long key, List<DocEntry> list) {}

    public DocController(ConfigService config, kbapp.config.AppPaths paths, IndexBuilder indexBuilder,
                         kbapp.kb.KbRegistry registry) {
        this.config = config;
        this.paths = paths;
        this.indexBuilder = indexBuilder;
        this.registry = registry;
    }

    private Path kbIndex(String kbId) {
        kbapp.kb.KbRegistry.KbEntry kb = registry.byId(kbId);
        return kb == null ? null : registry.indexDir(kb);
    }

    /** 只有索引真实存在时才返回目录，避免空目录 / 半删状态导致 IndexNotFound 500。 */
    private Path usableIndex(String kbId) {
        Path dir = kbIndex(kbId);
        if (dir == null) return null;
        try (org.apache.lucene.store.Directory d = org.apache.lucene.store.FSDirectory.open(dir)) {
            return org.apache.lucene.index.DirectoryReader.indexExists(d) ? dir : null;
        } catch (Exception e) {
            return null;
        }
    }

    private Path kbRoot(String kbId) {
        kbapp.kb.KbRegistry.KbEntry kb = registry.byId(kbId);
        return kb == null ? null : registry.docsRoot(kb);
    }

    private record DocEntry(String path, String title, String ext, String kind,
                            String folder, int chunkCount, String snippet) {}

    private synchronized List<DocEntry> docEntries(String kbId) throws Exception {
        String id = kbId == null || kbId.isBlank() ? "default" : kbId;
        long key = Math.max(indexBuilder.lastBuildAt(id), 1);
        CachedDocs cached = docCache.get(id);
        if (cached != null && cached.key() == key) return cached.list();
        List<DocEntry> list = new ArrayList<>();
        Path indexDir = usableIndex(id);
        if (indexDir == null) {
            // 知识库已被删除（智能体仍绑定着它）：返回空列表，别让接口 500
            docCache.put(id, new CachedDocs(key, list));
            return list;
        }
        try (IndexReader reader = DirectoryReader.open(FSDirectory.open(indexDir))) {
            Map<String, int[]> docFirst = new LinkedHashMap<>();
            Map<String, Document> firstDocs = new LinkedHashMap<>();
            var live = org.apache.lucene.index.MultiBits.getLiveDocs(reader);
            for (int i = 0; i < reader.maxDoc(); i++) {
                if (live != null && !live.get(i)) continue; // 跳过已删除文档，避免分块数虚高
                Document d = reader.storedFields().document(i,
                        java.util.Set.of(IndexSchema.DOC_PATH, IndexSchema.FOLDER, IndexSchema.EXT,
                                IndexSchema.DOC_TITLE, IndexSchema.KIND, IndexSchema.BODY));
                String p = IndexSchema.getString(d, IndexSchema.DOC_PATH);
                final int fi = i;
                int[] v = docFirst.computeIfAbsent(p, k -> new int[]{fi, 0});
                v[1]++;
                firstDocs.putIfAbsent(p, d);
            }
            for (Map.Entry<String, int[]> e : docFirst.entrySet()) {
                Document d = firstDocs.get(e.getKey());
                String body = IndexSchema.getString(d, IndexSchema.BODY);
                list.add(new DocEntry(e.getKey(),
                        IndexSchema.getString(d, IndexSchema.DOC_TITLE),
                        IndexSchema.getString(d, IndexSchema.EXT),
                        IndexSchema.getString(d, IndexSchema.KIND),
                        IndexSchema.getString(d, IndexSchema.FOLDER),
                        e.getValue()[1],
                        body.length() > 120 ? body.substring(0, 120) + "…" : body));
            }
        }
        docCache.put(id, new CachedDocs(key, list));
        return list;
    }

    private Map<String, Object> toMap(DocEntry de) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("docPath", de.path());
        m.put("docTitle", de.title());
        m.put("ext", de.ext());
        m.put("kind", de.kind());
        m.put("folder", de.folder());
        m.put("chunkCount", de.chunkCount());
        m.put("snippet", de.snippet());
        return m;
    }

    /** 一级目录列表（问答范围 chips / 文档库筛选）。 */
    @GetMapping("/docs/folders")
    public List<String> folders(@RequestParam(defaultValue = "default") String kbId) throws Exception {
        LinkedHashMap<String, Boolean> out = new LinkedHashMap<>();
        for (DocEntry de : docEntries(kbId)) {
            String f = de.folder();
            String top = f == null || f.isBlank() ? "" : f.split("/")[0];
            if (!top.isBlank()) out.putIfAbsent(top, true);
        }
        return new ArrayList<>(out.keySet());
    }

    /** 文档卡片列表：folder/ext 筛选 + 分页（按文档聚合）。 */
    @GetMapping("/docs")
    public Map<String, Object> docs(@RequestParam(required = false) String folder,
                                    @RequestParam(required = false) String ext,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size,
                                    @RequestParam(defaultValue = "default") String kbId) throws Exception {
        List<DocEntry> all = docEntries(kbId);
        List<DocEntry> filtered = new ArrayList<>();
        for (DocEntry de : all) {
            if (folder != null && !folder.isBlank()) {
                String f = de.folder();
                if (!f.equals(folder) && !f.startsWith(folder + "/")) continue;
            }
            if (ext != null && !ext.isBlank() && !ext.equalsIgnoreCase(de.ext())) continue;
            filtered.add(de);
        }
        List<Map<String, Object>> items = new ArrayList<>();
        int from = Math.max(0, page * size);
        int to = Math.min(filtered.size(), from + size);
        for (int i = from; i < to; i++) items.add(toMap(filtered.get(i)));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", filtered.size());
        m.put("page", page);
        m.put("size", size);
        m.put("items", items);
        return m;
    }

    /** 单文档元信息（预览抽屉头部）。 */
    @GetMapping("/doc/meta")
    public Map<String, Object> meta(@RequestParam("path") String relPath,
                                    @RequestParam(defaultValue = "default") String kbId) throws Exception {
        Path idx = usableIndex(kbId);
        if (idx == null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("title", relPath);
            m.put("ext", ScanFilter.extOf(relPath));
            m.put("kind", "");
            return m;
        }
        try (IndexReader reader = DirectoryReader.open(FSDirectory.open(idx))) {
            IndexSearcher searcher = new IndexSearcher(reader);
            var hits = searcher.search(new TermQuery(new Term(IndexSchema.DOC_PATH, relPath)), 1);
            Map<String, Object> m = new LinkedHashMap<>();
            if (hits.scoreDocs.length == 0) {
                m.put("title", relPath);
                m.put("ext", ScanFilter.extOf(relPath));
                m.put("kind", "");
                return m;
            }
            Document d = reader.storedFields().document(hits.scoreDocs[0].doc);
            m.put("title", IndexSchema.getString(d, IndexSchema.DOC_TITLE));
            m.put("ext", IndexSchema.getString(d, IndexSchema.EXT));
            m.put("kind", IndexSchema.getString(d, IndexSchema.KIND));
            return m;
        }
    }

    /** 原文件流（预览"打开原文件"与前端渲染器取文件）。 */
    @GetMapping("/doc/raw")
    public ResponseEntity<FileSystemResource> raw(@RequestParam("path") String relPath,
                                                  @RequestParam(defaultValue = "default") String kbId) {
        Path resolved = resolve(relPath, kbId);
        if (resolved == null) return ResponseEntity.badRequest().build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" +
                        java.net.URLEncoder.encode(resolved.getFileName().toString(),
                                java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20"))
                .contentType(mediaTypeOf(resolved))
                .body(new FileSystemResource(resolved));
    }

    /** 调用系统默认程序打开原文件（等同双击：docx→Word/WPS、pdf→阅读器）。 */
    @GetMapping("/doc/open")
    public Map<String, Object> open(@RequestParam("path") String relPath,
                                    @RequestParam(defaultValue = "default") String kbId) {
        Path resolved = resolve(relPath, kbId);
        Map<String, Object> m = new LinkedHashMap<>();
        if (resolved == null) {
            m.put("ok", false);
            m.put("error", "文件不存在或不在数据源目录内");
            return m;
        }
        try {
            java.awt.Desktop desktop = java.awt.Desktop.getDesktop();
            if (!desktop.isSupported(java.awt.Desktop.Action.OPEN)) {
                m.put("ok", false);
                m.put("error", "当前系统不支持默认程序打开");
                return m;
            }
            desktop.open(resolved.toFile());
            m.put("ok", true);
        } catch (Exception e) {
            m.put("ok", false);
            m.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
        }
        return m;
    }

    /** 提取文本：整篇或单块（PPT 按页文本预览同源）。 */
    @GetMapping("/doc/text")
    public Map<String, Object> text(@RequestParam("path") String relPath,
                                    @RequestParam(required = false) Integer seq,
                                    @RequestParam(defaultValue = "default") String kbId) throws Exception {
        Path idx = usableIndex(kbId);
        if (idx == null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("docPath", relPath);
            m.put("chunks", new ArrayList<>());
            return m;
        }
        try (IndexReader reader = DirectoryReader.open(FSDirectory.open(idx))) {
            IndexSearcher searcher = new IndexSearcher(reader);
            var hits = searcher.search(new TermQuery(new Term(IndexSchema.DOC_PATH, relPath)), Integer.MAX_VALUE);
            List<Map<String, Object>> chunks = new ArrayList<>();
            for (var sd : hits.scoreDocs) {
                Document d = reader.storedFields().document(sd.doc);
                int s = IndexSchema.getInt(d, IndexSchema.SEQ);
                if (seq != null && s != seq) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("seq", s);
                m.put("heading", IndexSchema.getString(d, IndexSchema.HEADING));
                m.put("text", IndexSchema.getString(d, IndexSchema.BODY));
                chunks.add(m);
            }
            chunks.sort((a, b) -> Integer.compare((int) a.get("seq"), (int) b.get("seq")));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("docPath", relPath);
            m.put("chunks", chunks);
            return m;
        }
    }

    /** 目录穿越防护：解析后必须仍在 sourceRoot 内。 */
    private Path resolve(String relPath, String kbId) {
        try {
            Path base = kbRoot(kbId);
            if (base == null) return null;
            Path p = base.resolve(relPath).normalize();
            if (!p.startsWith(base) || !Files.isRegularFile(p)) return null;
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    private MediaType mediaTypeOf(Path p) {
        return switch (ScanFilter.extOf(p.getFileName().toString())) {
            case ".pdf" -> MediaType.APPLICATION_PDF;
            case ".html", ".htm" -> MediaType.TEXT_HTML;
            case ".txt", ".md", ".log" -> MediaType.parseMediaType("text/plain;charset=UTF-8");
            case ".csv" -> MediaType.parseMediaType("text/csv;charset=UTF-8");
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
    }
}
