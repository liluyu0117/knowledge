package kbapp.mcp;

import kbapp.config.ConfigService;
import kbapp.ingest.IndexSchema;
import kbapp.kb.KbRegistry;
import kbapp.retrieve.DocMetaStore;
import kbapp.retrieve.HybridSearchService;
import kbapp.retrieve.SearchHit;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 检索工具：kb_list / kb_search / kb_docs / kb_open，纯检索不含 LLM。
 *
 * 设计要点：
 * - 所有工具都可省略 kbId（用第一个知识库），也接受库名而不只是 id —— 模型更容易记住名字；
 * - 索引目录一律走 KbRegistry.indexDir(kb)，不再写死 .kb/lucene（多库改造后的旧路径早已不存在）；
 * - 出错不抛栈，返回 {ok:false,error,hint}，让客户端能读懂并自行纠正。
 */
@Component
public class KbSearchTools {

    private static final int MAX_SNIPPET = 400;
    private static final int MAX_TOP_K = 30;
    private static final int MAX_DOCS = 500;
    private static final int DEFAULT_OPEN_CHARS = 20000;
    private static final int MAX_OPEN_CHARS = 100000;

    private final HybridSearchService search;
    private final KbRegistry registry;
    private final ConfigService config;
    private final DocMetaStore docMeta;

    public KbSearchTools(HybridSearchService search, KbRegistry registry, ConfigService config,
                         DocMetaStore docMeta) {
        this.search = search;
        this.registry = registry;
        this.config = config;
        this.docMeta = docMeta;
    }

    // ───────────────────────── 工具 ─────────────────────────

    @Tool(name = "kb_list",
          description = "列出本机知识库：kbId、名称、文档数、分块数。检索前先用它确认要查哪个库；只有一个库时可以省略 kbId")
    public Map<String, Object> kbList() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> list = new ArrayList<>();
        for (KbRegistry.KbEntry kb : registry.list()) {
            int[] c = counts(kb);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kbId", kb.id());
            m.put("name", kb.name());
            m.put("docs", c[0]);
            m.put("chunks", c[1]);
            m.put("createdAt", kb.createdAt());
            list.add(m);
        }
        out.put("ok", true);
        out.put("count", list.size());
        out.put("kbs", list);
        if (list.isEmpty()) {
            out.put("hint", "还没有知识库，请先在「知识库」页新建并构建索引");
        } else if (list.size() == 1) {
            out.put("hint", "只有一个库，后续调用可以省略 kbId");
        }
        return out;
    }

    @Tool(name = "kb_search",
          description = "在知识库中做混合检索（关键词 + 向量 + 重排），返回最相关的文档片段。"
                      + "每条结果的 ref 可直接传给 kb_open 打开原文")
    public Map<String, Object> kbSearch(
            @ToolParam(description = "检索问题或关键词，用文档里的原话效果最好") String query,
            @ToolParam(required = false, description = "知识库 id 或名称（kb_list 返回）；省略则用第一个库") String kbId,
            @ToolParam(required = false, description = "返回片段数，默认 8，上限 30") Integer topK,
            @ToolParam(required = false, description = "限定子目录（相对库根，如「项目A/上线方案」）；省略则全库") String folder,
            @ToolParam(required = false, description = "每个文档最多返回几个片段，默认 3") Integer maxPerDoc,
            @ToolParam(required = false, description = "只返回带这些标签（任一命中即可）的文档，逗号分隔，如「上线方案,需求」；标签清单先用 kb_tags 或管理页确认") String tags) {

        Map<String, Object> out = new LinkedHashMap<>();
        if (!enabled(out)) return out;
        if (query == null || query.isBlank()) return fail(out, "query 不能为空", "传入要检索的问题或关键词");

        KbRegistry.KbEntry kb = resolve(kbId);
        if (kb == null) return noKb(out, kbId);

        int k = clamp(topK == null ? 8 : topK, 1, MAX_TOP_K);
        int mpd = clamp(maxPerDoc == null ? 3 : maxPerDoc, 1, 10);
        String folderParam = folder == null || folder.isBlank() ? null : folder.trim();
        List<String> tagList = splitTags(tags);

        try {
            HybridSearchService.Result r = search.search(query, k, folderParam, mpd, true, kb.id(), tagList);
            List<Map<String, Object>> results = new ArrayList<>();
            for (SearchHit h : r.hits()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ref", h.docPath() + "#" + h.seq());
                m.put("title", h.docTitle());
                m.put("path", h.docPath());
                m.put("seq", h.seq());
                m.put("heading", h.heading());
                m.put("score", round(h.rerankScore() != null ? h.rerankScore() : h.rrfScore()));
                m.put("snippet", snippet(h.snippetHtml()));
                results.add(m);
            }
            out.put("ok", true);
            out.put("kbId", kb.id());
            out.put("kbName", kb.name());
            out.put("total", results.size());
            out.put("rerankUsed", r.rerankUsed());
            out.put("dropped", r.dropped());
            if (!tagList.isEmpty()) out.put("tagsFilter", tagList);
            if (!r.expanded().isEmpty()) out.put("expandedTerms", r.expanded());
            if (results.isEmpty()) {
                if (!tagList.isEmpty()) {
                    int n = docMeta.pathsWithAnyTag(kb.id(), tagList).size();
                    out.put("hint", "标签 " + tagList + " 范围内共 " + n + " 篇文档，但没有与该问题相关的片段；可放宽标签条件或换个问法");
                } else if (r.dropped() > 0) {
                    out.put("hint", "有 " + r.dropped() + " 个片段相关度低于阈值被丢弃，换个更具体的问法或换关键词再试");
                } else {
                    out.put("hint", "该库没有命中；可先用 kb_docs 确认文档是否存在、是否已建索引");
                }
            }
            out.put("results", results);
        } catch (Exception e) {
            return fail(out, "检索失败：" + brief(e), "确认知识库已完成索引构建（知识库页可看到分块数）");
        }
        return out;
    }

    @Tool(name = "kb_docs",
          description = "列出知识库里的文档（路径、标题、分块数），可按关键字过滤。用于确认某份文档是否存在、路径怎么写")
    public Map<String, Object> kbDocs(
            @ToolParam(required = false, description = "知识库 id 或名称；省略则用第一个库") String kbId,
            @ToolParam(required = false, description = "按路径/标题包含的关键字过滤，如「上线方案」「需求规格」") String keyword,
            @ToolParam(required = false, description = "返回条数上限，默认 100，最大 500") Integer limit) {

        Map<String, Object> out = new LinkedHashMap<>();
        if (!enabled(out)) return out;
        KbRegistry.KbEntry kb = resolve(kbId);
        if (kb == null) return noKb(out, kbId);

        String kw = keyword == null ? "" : keyword.trim().toLowerCase();
        int max = clamp(limit == null ? 100 : limit, 1, MAX_DOCS);

        Map<String, Integer> byPath = new LinkedHashMap<>();
        Map<String, String> titles = new LinkedHashMap<>();
        try {
            eachChunk(kb, (docPath, title, seq, heading, body) -> {
                byPath.merge(docPath, 1, Integer::sum);
                if (!title.isEmpty()) titles.putIfAbsent(docPath, title);
            });
        } catch (Exception e) {
            return fail(out, "读取索引失败：" + brief(e), "该库可能还没有构建索引");
        }

        List<Map<String, Object>> docs = new ArrayList<>();
        for (Map.Entry<String, Integer> e : byPath.entrySet()) {
            String path = e.getKey();
            String title = titles.getOrDefault(path, "");
            if (!kw.isEmpty() && !path.toLowerCase().contains(kw) && !title.toLowerCase().contains(kw)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", path);
            m.put("title", title.isEmpty() ? fileName(path) : title);
            m.put("chunks", e.getValue());
            docs.add(m);
        }
        docs.sort(Comparator.comparing(m -> String.valueOf(m.get("path"))));

        out.put("ok", true);
        out.put("kbId", kb.id());
        out.put("kbName", kb.name());
        out.put("total", docs.size());
        out.put("returned", Math.min(docs.size(), max));
        if (docs.size() > max) out.put("truncated", true);
        out.put("docs", docs.subList(0, Math.min(docs.size(), max)));
        if (docs.isEmpty()) {
            out.put("hint", kw.isEmpty()
                    ? "该库索引里没有文档，先在「知识库」页构建索引"
                    : "没有路径/标题包含「" + keyword + "」的文档");
        }
        return out;
    }

    @Tool(name = "kb_open",
          description = "按路径读取文档的提取文本：带 seq 只返回该块，省略 seq 返回全篇（超长会截断并标记 truncated）")
    public Map<String, Object> kbOpen(
            @ToolParam(description = "文档相对路径（kb_search 的 path，或 kb_docs 列表里的 path）") String path,
            @ToolParam(required = false, description = "知识库 id 或名称；省略则用第一个库") String kbId,
            @ToolParam(required = false, description = "块序号；省略则返回整篇") Integer seq,
            @ToolParam(required = false, description = "整篇最大字符数，默认 20000，上限 100000") Integer maxChars) {

        Map<String, Object> out = new LinkedHashMap<>();
        if (!enabled(out)) return out;
        String want = path == null ? "" : path.trim().replace('\\', '/');
        while (want.startsWith("./")) want = want.substring(2);
        if (want.isEmpty()) return fail(out, "path 不能为空", "传 kb_search 返回的 path");
        final String wantPath = want;   // lambda 里要用，必须是 effectively final

        KbRegistry.KbEntry kb = resolve(kbId);
        if (kb == null) return noKb(out, kbId);

        try {
            // 先精确匹配；不中再按「路径后缀唯一匹配」兜底，省得模型写不全前缀
            ChunkPage page = readChunks(kb, want, seq);
            List<Map<String, Object>> chunks = page.chunks();
            String resolved = want;

            // 文档在、但 seq 越界：直接给出真实块号范围，别让调用方去猜
            if (chunks.isEmpty() && page.exists() && seq != null) {
                return seqMiss(out, want, page, seq);
            }

            if (chunks.isEmpty()) {
                List<String> candidates = new ArrayList<>();
                eachChunk(kb, (docPath, title, s, heading, body) -> {
                    if (docPath.endsWith(wantPath) && !candidates.contains(docPath)) candidates.add(docPath);
                });
                if (candidates.size() == 1) {
                    resolved = candidates.get(0);
                    page = readChunks(kb, resolved, seq);
                    chunks = page.chunks();
                } else if (candidates.size() > 1) {
                    out.put("ok", false);
                    out.put("error", "路径不唯一，匹配到 " + candidates.size() + " 个文档");
                    out.put("candidates", candidates.subList(0, Math.min(10, candidates.size())));
                    out.put("hint", "用 kb_docs 拿到完整 path 再调用");
                    return out;
                }
            }

            if (chunks.isEmpty() && page.exists() && seq != null) {
                return seqMiss(out, resolved, page, seq);
            }

            if (chunks.isEmpty()) {
                // 本库没有：看看是不是在别的库，直接告诉调用方
                List<String> elsewhere = new ArrayList<>();
                for (KbRegistry.KbEntry other : registry.list()) {
                    if (other.id().equals(kb.id())) continue;
                    final boolean[] hit = {false};
                    try {
                        eachChunk(other, (docPath, t, s, h, b) -> {
                            if (docPath.equals(wantPath) || docPath.endsWith(wantPath)) hit[0] = true;
                        });
                    } catch (Exception ignored) { }
                    if (hit[0]) elsewhere.add(other.id() + "（" + other.name() + "）");
                }
                out.put("ok", false);
                out.put("found", false);
                out.put("docPath", want);
                out.put("error", "该库里没有这份文档");
                if (seq != null) out.put("hint", "确认 seq 是否正确；也可以去掉 seq 读整篇看有哪些块");
                else if (!elsewhere.isEmpty()) out.put("hint", "这份文档在：" + String.join("、", elsewhere) + "，用对应 kbId 再调");
                else out.put("hint", "用 kb_docs 确认路径；文档没被索引时先在知识库页构建索引");
                return out;
            }

            int budget = clamp(maxChars == null ? DEFAULT_OPEN_CHARS : maxChars, 200, MAX_OPEN_CHARS);
            List<Map<String, Object>> kept = new ArrayList<>();
            int used = 0;
            boolean truncated = false;
            for (Map<String, Object> c : chunks) {
                String text = String.valueOf(c.get("text"));
                int room = budget - used;
                if (text.length() > room) {
                    if (room > 0) {
                        c.put("text", text.substring(0, room) + "…");
                        used += room;           // 截断部分也要计入，否则 charCount 会漏掉最后一块
                        kept.add(c);
                    }
                    truncated = true;
                    break;
                }
                used += text.length();
                kept.add(c);
            }

            out.put("ok", true);
            out.put("kbId", kb.id());
            out.put("kbName", kb.name());
            out.put("docPath", resolved);
            if (!resolved.equals(want)) out.put("requestedPath", want);
            out.put("title", page.title().isEmpty() ? fileName(resolved) : page.title());
            out.put("chunkCount", page.seqs().size());
            out.put("returned", kept.size());
            out.put("charCount", used);
            out.put("truncated", truncated);
            if (truncated) {
                out.put("hint", kept.size() < chunks.size()
                        ? "共 " + chunks.size() + " 块，按 maxChars 只返回前 " + kept.size() + " 块；调大 maxChars 或分块用 seq 读取"
                        : "该块超出 maxChars 被截断，调大 maxChars 可看完整内容");
            }
            out.put("chunks", kept);
        } catch (Exception e) {
            return fail(out, "读取失败：" + brief(e), "确认该库索引存在；必要时重建索引");
        }
        return out;
    }

    private static List<String> splitTags(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isBlank()) return out;
        for (String t : s.split("[,，;；]")) {
            String v = t.trim();
            if (!v.isEmpty() && !out.contains(v)) out.add(v);
        }
        return out;
    }

    @Tool(name = "kb_tags",
          description = "列出知识库的全部标签及每个标签下的文档数。需要按标签缩小检索范围时，先用它确认标签名，再把这些标签传给 kb_search 的 tags 参数")
    public Map<String, Object> kbTags(
            @ToolParam(required = false, description = "知识库 id 或名称；省略则用第一个库") String kbId) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!enabled(out)) return out;
        KbRegistry.KbEntry kb = resolve(kbId);
        if (kb == null) return noKb(out, kbId);
        Map<String, Integer> byTag = new LinkedHashMap<>();
        int tagged = 0;
        for (var e : docMeta.all(kb.id()).entrySet()) {
            if (e.getValue().tags().isEmpty()) continue;
            tagged++;
            for (String t : e.getValue().tags()) byTag.merge(t, 1, Integer::sum);
        }
        out.put("ok", true);
        out.put("kbId", kb.id());
        out.put("kbName", kb.name());
        out.put("tagCount", byTag.size());
        out.put("taggedDocs", tagged);
        List<Map<String, Object>> list = new ArrayList<>();
        for (var e : byTag.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tag", e.getKey());
            m.put("docs", e.getValue());
            list.add(m);
        }
        out.put("tags", list);
        if (byTag.isEmpty()) {
            out.put("hint", "该库还没有标签；在知识库管理页给文档打标签，或上传时带上标签");
        }
        return out;
    }

    // ───────────────────────── 索引读取 ─────────────────────────

    /** 逐块回调：只读存储字段，不加载向量。 */
    private interface ChunkVisitor {
        void accept(String docPath, String title, int seq, String heading, String body);
    }

    private void eachChunk(KbRegistry.KbEntry kb, ChunkVisitor visitor) throws Exception {
        Path dir = registry.indexDir(kb);
        if (dir == null || !Files.exists(dir)) return;
        try (Directory d = FSDirectory.open(dir)) {
            if (!DirectoryReader.indexExists(d)) return;
            try (DirectoryReader reader = DirectoryReader.open(d)) {
                // 只遍历未删除文档：maxDoc 含历史删除段
                for (var lc : reader.leaves()) {
                    var lr = lc.reader();
                    var live = lr.getLiveDocs();
                    for (int i = 0; i < lr.maxDoc(); i++) {
                        if (live != null && !live.get(i)) continue;
                        var doc = lr.storedFields().document(i, java.util.Set.of(
                                IndexSchema.DOC_PATH, IndexSchema.DOC_TITLE, IndexSchema.SEQ,
                                IndexSchema.HEADING, IndexSchema.BODY));
                        String p = IndexSchema.getString(doc, IndexSchema.DOC_PATH);
                        if (p.isEmpty()) continue;
                        visitor.accept(p,
                                IndexSchema.getString(doc, IndexSchema.DOC_TITLE),
                                IndexSchema.getInt(doc, IndexSchema.SEQ),
                                IndexSchema.getString(doc, IndexSchema.HEADING),
                                IndexSchema.getString(doc, IndexSchema.BODY));
                    }
                }
            }
        }
    }

    /** 一次索引扫描的结果：要返回的块、文档标题、该文档实际的全部块号。 */
    private record ChunkPage(List<Map<String, Object>> chunks, String title, List<Integer> seqs) {
        boolean exists() { return !seqs.isEmpty(); }
    }

    /** 读一个文档的块（可只取某个 seq）；标题与全部块号顺带取出，免得多扫一遍索引。 */
    private ChunkPage readChunks(KbRegistry.KbEntry kb, String docPath, Integer seq) throws Exception {
        Path dir = registry.indexDir(kb);
        List<Map<String, Object>> out = new ArrayList<>();
        List<Integer> seqs = new ArrayList<>();
        String title = "";
        if (dir == null || !Files.exists(dir)) return new ChunkPage(out, title, seqs);
        try (Directory d = FSDirectory.open(dir)) {
            if (!DirectoryReader.indexExists(d)) return new ChunkPage(out, title, seqs);
            try (DirectoryReader reader = DirectoryReader.open(d)) {
                var searcher = new IndexSearcher(reader);
                var hits = searcher.search(new TermQuery(new Term(IndexSchema.DOC_PATH, docPath)), 5000);
                for (var sd : hits.scoreDocs) {
                    var doc = searcher.storedFields().document(sd.doc,
                            java.util.Set.of(IndexSchema.SEQ, IndexSchema.HEADING, IndexSchema.BODY,
                                    IndexSchema.DOC_TITLE));
                    int s = IndexSchema.getInt(doc, IndexSchema.SEQ);
                    if (title.isEmpty()) title = IndexSchema.getString(doc, IndexSchema.DOC_TITLE);
                    seqs.add(s);
                    if (seq != null && s != seq) continue;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("seq", s);
                    String heading = IndexSchema.getString(doc, IndexSchema.HEADING);
                    if (!heading.isEmpty()) m.put("heading", heading);
                    m.put("text", IndexSchema.getString(doc, IndexSchema.BODY));
                    out.add(m);
                }
            }
        }
        out.sort(Comparator.comparingInt(m -> (int) m.get("seq")));
        seqs.sort(Integer::compareTo);
        return new ChunkPage(out, title, seqs);
    }

    /** seq 越界时的明确答复：给出真实块号范围，避免调用方瞎猜。 */
    private Map<String, Object> seqMiss(Map<String, Object> out, String docPath, ChunkPage page, int seq) {
        out.put("ok", false);
        out.put("found", true);
        out.put("docPath", docPath);
        out.put("error", "该文档没有第 " + seq + " 块");
        out.put("chunkCount", page.seqs().size());
        out.put("seqRange", seqRange(page.seqs()));
        out.put("hint", "省略 seq 读整篇，或用 seqRange 范围内的块号");
        return out;
    }

    private static String seqRange(List<Integer> seqs) {
        if (seqs.isEmpty()) return "";
        int lo = seqs.get(0);
        int hi = seqs.get(seqs.size() - 1);
        return lo == hi ? String.valueOf(lo) : lo + "-" + hi;
    }

    /** [文档数, 分块数] */    private int[] counts(KbRegistry.KbEntry kb) {
        java.util.Set<String> paths = new java.util.HashSet<>();
        int[] chunks = {0};
        try {
            eachChunk(kb, (p, t, s, h, b) -> { paths.add(p); chunks[0]++; });
        } catch (Exception ignored) { }
        return new int[]{paths.size(), chunks[0]};
    }

    // ───────────────────────── 小工具 ─────────────────────────

    /** kbId 为空 → 第一个库；也接受按库名匹配，模型不用记 12 位 id。 */
    private KbRegistry.KbEntry resolve(String kbId) {
        return registry.resolve(kbId);
    }

    private boolean enabled(Map<String, Object> out) {
        if (config.get().getMcp().isEnabled()) return true;
        out.put("ok", false);
        out.put("error", "MCP 已停用");
        out.put("hint", "在「设置 → 集成与接口」里重新启用");
        return false;
    }

    private Map<String, Object> noKb(Map<String, Object> out, String kbId) {
        StringBuilder sb = new StringBuilder();
        for (KbRegistry.KbEntry k : registry.list()) {
            if (sb.length() > 0) sb.append("、");
            sb.append(k.id()).append("（").append(k.name()).append("）");
        }
        out.put("ok", false);
        out.put("error", (kbId == null || kbId.isBlank() ? "还没有知识库" : "找不到知识库：" + kbId));
        out.put("hint", sb.length() == 0 ? "先在「知识库」页新建一个" : "可用：" + sb);
        return out;
    }

    private Map<String, Object> fail(Map<String, Object> out, String error, String hint) {
        out.put("ok", false);
        out.put("error", error);
        if (hint != null) out.put("hint", hint);
        return out;
    }

    private static int clamp(Integer v, int min, int max) {
        int n = v == null ? min : v;
        return Math.max(min, Math.min(max, n));
    }

    private static double round(Double v) {
        if (v == null) return 0;
        return Math.round(v * 10000d) / 10000d;
    }

    private static String snippet(String html) {
        if (html == null) return "";
        String t = html.replace("<mark>", "").replace("</mark>", "").replaceAll("\\s+", " ").trim();
        return t.length() <= MAX_SNIPPET ? t : t.substring(0, MAX_SNIPPET) + "…";
    }

    private static String fileName(String path) {
        int i = path.lastIndexOf('/');
        return i >= 0 ? path.substring(i + 1) : path;
    }

    private static String brief(Exception e) {
        String s = e.getMessage() == null ? e.toString() : e.getMessage();
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
