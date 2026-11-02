package kbapp.mcp;

import kbapp.config.ConfigService;
import kbapp.retrieve.HybridSearchService;
import kbapp.retrieve.SearchHit;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 检索工具（设计文档 10）：kb_search / kb_open，纯检索无 LLM 调用。
 */
@Component
public class KbSearchTools {

    private final HybridSearchService search;
    private final ConfigService config;

    public KbSearchTools(HybridSearchService search, ConfigService config) {
        this.search = search;
        this.config = config;
    }

    @Tool(name = "kb_search", description = "在本地知识库中做混合检索（关键词+语义），返回最相关的文档片段，含标题、路径、块号、得分与摘录")
    public List<Map<String, Object>> kbSearch(
            @ToolParam(description = "检索问题或关键词，中文效果最佳") String query,
            @ToolParam(required = false, description = "返回条数，默认 8") Integer topK,
            @ToolParam(required = false, description = "限定子目录（相对数据源根目录的路径前缀）") String folder,
            @ToolParam(required = false, description = "每个文档最多返回的片段数，默认 3") Integer maxPerDoc) {

        HybridSearchService.Result r = search.search(query, topK, folder, maxPerDoc, true);
        List<Map<String, Object>> out = new ArrayList<>();
        int n = 1;
        for (SearchHit h : r.hits()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", n++);
            m.put("title", h.docTitle());
            m.put("path", h.docPath());
            m.put("seq", h.seq());
            m.put("heading", h.heading());
            m.put("score", h.rrfScore());
            m.put("snippet", h.snippetHtml().replace("<mark>", "").replace("</mark>", ""));
            out.add(m);
        }
        return out;
    }

    @Tool(name = "kb_open", description = "按路径读取知识库文档的提取文本：不带 seq 返回整篇全部分块，带 seq 返回单块")
    public Map<String, Object> kbOpen(
            @ToolParam(description = "文档相对路径（kb_search 返回的 path）") String docPath,
            @ToolParam(required = false, description = "块序号；省略则返回整篇") Integer seq) {
        try {
            Path resolved = resolve();
            List<Map<String, Object>> chunks = new ArrayList<>();
            try (var reader = org.apache.lucene.index.DirectoryReader.open(
                    org.apache.lucene.store.FSDirectory.open(resolved))) {
                var searcher = new org.apache.lucene.search.IndexSearcher(reader);
                var hits = searcher.search(
                        new org.apache.lucene.search.TermQuery(
                                new org.apache.lucene.index.Term(kbapp.ingest.IndexSchema.DOC_PATH, docPath)),
                        Integer.MAX_VALUE);
                for (var sd : hits.scoreDocs) {
                    var d = reader.storedFields().document(sd.doc);
                    int s = kbapp.ingest.IndexSchema.getInt(d, kbapp.ingest.IndexSchema.SEQ);
                    if (seq != null && s != seq) continue;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("seq", s);
                    m.put("heading", kbapp.ingest.IndexSchema.getString(d, kbapp.ingest.IndexSchema.HEADING));
                    m.put("text", kbapp.ingest.IndexSchema.getString(d, kbapp.ingest.IndexSchema.BODY));
                    chunks.add(m);
                }
            }
            chunks.sort((a, b) -> Integer.compare((int) a.get("seq"), (int) b.get("seq")));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("docPath", docPath);
            out.put("found", !chunks.isEmpty());
            out.put("chunks", chunks);
            return out;
        } catch (Exception e) {
            return Map.of("docPath", docPath, "found", false, "error",
                    e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private Path resolve() {
        Path p = config.dataDir().resolve("lucene");
        return p;
    }
}
