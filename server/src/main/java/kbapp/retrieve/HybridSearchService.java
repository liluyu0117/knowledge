package kbapp.retrieve;

import kbapp.config.AppConfig;
import kbapp.config.ConfigService;
import kbapp.ingest.AnalyzerFactory;
import kbapp.ingest.IndexSchema;
import kbapp.llm.EmbeddingClient;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.highlight.Highlighter;
import org.apache.lucene.search.highlight.QueryScorer;
import org.apache.lucene.search.highlight.SimpleFragmenter;
import org.apache.lucene.search.highlight.SimpleHTMLFormatter;
import org.apache.lucene.search.highlight.TextFragment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 混合检索：BM25 与 KNN 双路召回 → RRF 融合 → 每文档去重 →（可选）rerank 重排 → 阈值过滤 → 高亮。
 * 术语表扩展只作用于关键词路；向量路始终用原句。
 */
@Service
public class HybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);

    private final SearcherService searcherService;
    private final ConfigService config;
    private final EmbeddingClient embedding;
    private final kbapp.kb.KbRegistry registry;
    private final RerankClient rerank;
    private final GlossaryStore glossary;
    private final ChunkFlagStore chunkFlags;
    private final kbapp.usage.UsageStats usage;
    private final DocMetaStore docMeta;
    private final GraphStore graphStore;

    public HybridSearchService(SearcherService searcherService, ConfigService config,
                               EmbeddingClient embedding, kbapp.kb.KbRegistry registry,
                               RerankClient rerank, GlossaryStore glossary,
                               ChunkFlagStore chunkFlags, kbapp.usage.UsageStats usage,
                               DocMetaStore docMeta, GraphStore graphStore) {
        this.searcherService = searcherService;
        this.config = config;
        this.embedding = embedding;
        this.registry = registry;
        this.rerank = rerank;
        this.glossary = glossary;
        this.chunkFlags = chunkFlags;
        this.usage = usage;
        this.docMeta = docMeta;
        this.graphStore = graphStore;
    }

    public record Result(List<SearchHit> hits, int candidatesKw, int candidatesVec, boolean vectorUsed,
                         boolean rerankUsed, int dropped, List<String> expanded) {}

    private record Cand(SearchHit hit, String body) {}

    public Result search(String query, Integer topK, String folder, Integer maxPerDoc, boolean withVector) {
        return search(query, topK, folder, maxPerDoc, withVector, null);
    }

    public Result search(String query, Integer topK, String folder, Integer maxPerDoc, boolean withVector,
                         String kbId) {
        return search(query, topK, folder, maxPerDoc, withVector, kbId, null);
    }

    /**
     * 带标签过滤的检索：tags 非空时只返回带其中任一标签的文档。
     * 过滤下推为 Lucene FILTER（TermInSetQuery on docPath），两路召回都受限，
     * 不会出现「召回了 N 条再被筛掉」导致结果不足的问题。
     */
    public Result search(String query, Integer topK, String folder, Integer maxPerDoc, boolean withVector,
                         String kbId, java.util.Collection<String> tags) {
        AppConfig cfg = config.get();
        int k = topK != null && topK > 0 ? topK : cfg.getRag().getTopK();
        int mpd = maxPerDoc != null && maxPerDoc > 0 ? maxPerDoc : cfg.getRag().getMaxPerDoc();
        int cand = cfg.getRag().getCandidate();
        if (query == null || query.isBlank()) {
            return new Result(List.of(), 0, 0, false, false, 0, List.of());
        }
        query = query.strip();

        kbapp.kb.KbRegistry.KbEntry kb = registry.byId(kbId);
        if (kb == null) throw new IllegalStateException("知识库不存在: " + kbId);
        Path indexDir = registry.indexDir(kb);

        // 标签过滤：取「带任一所选标签」的 path 集合；空集合 = 没有文档带这些标签，直接返回
        List<String> tagList = tags == null ? List.of()
                : tags.stream().filter(t -> t != null && !t.isBlank()).map(String::trim).distinct().toList();
        org.apache.lucene.search.Query tagFilter = null;
        if (!tagList.isEmpty()) {
            Set<String> allowed = docMeta.pathsWithAnyTag(kb.id(), tagList);
            if (allowed.isEmpty()) {
                return new Result(List.of(), 0, 0, false, false, 0, List.of());
            }
            List<org.apache.lucene.util.BytesRef> terms = new ArrayList<>();
            for (String p : allowed) terms.add(new org.apache.lucene.util.BytesRef(p));
            tagFilter = new org.apache.lucene.search.TermInSetQuery(IndexSchema.DOC_PATH, terms);
        }

        usage.inc("search");
        boolean wantRerank = rerank.enabled();
        // 有 rerank 时召回要更宽，才能让重排有挑选余地
        int fetch = wantRerank
                ? Math.max(k, Math.max(1, cfg.getRerank().getCandidate()))
                : Math.max(k, cand);
        List<String> expanded = glossary.expand(query);
        // 知识图谱扩展：问句命中实体时补关联实体名（GraphStore 为空图时自动跳过）
        List<String> graphTerms = graphStore.expand(kb.id(), query);
        if (!graphTerms.isEmpty()) {
            for (String t : graphTerms) if (!expanded.contains(t)) expanded.add(t);
        }

        try (Analyzer analyzer = AnalyzerFactory.create(cfg.getRag().getAnalyzer())) {
            SearcherManager mgr = searcherService.manager(indexDir);
            mgr.maybeRefresh();
            IndexSearcher searcher = mgr.acquire();
            try {
                Query kwQuery = buildKwQuery(query, expanded, analyzer);
                if (kwQuery != null && tagFilter != null) {
                    kwQuery = new BooleanQuery.Builder()
                            .add(kwQuery, BooleanClause.Occur.SHOULD)
                            .add(tagFilter, BooleanClause.Occur.FILTER)
                            .build();
                }
                List<ScoreDoc> kwHits = kwQuery == null ? List.of()
                        : List.of(searcher.search(kwQuery, fetch).scoreDocs);

                List<ScoreDoc> vecHits = List.of();
                boolean vectorUsed = false;
                if (withVector) {
                    try {
                        float[] qv = embedding.embed(query);
                        if (qv != null && qv.length > 0) {
                            Query vq = tagFilter != null
                                    ? new KnnFloatVectorQuery(IndexSchema.VECTOR, qv, fetch, tagFilter)
                                    : new KnnFloatVectorQuery(IndexSchema.VECTOR, qv, fetch);
                            vecHits = List.of(searcher.search(vq, fetch).scoreDocs);
                            vectorUsed = true;
                        }
                    } catch (Exception e) {
                        log.info("向量路不可用，仅关键词召回: {}", e.toString());
                    }
                }

                Highlighter highlighter = kwQuery == null ? null : new Highlighter(
                        new SimpleHTMLFormatter("<mark>", "</mark>"), new QueryScorer(kwQuery, IndexSchema.BODY));
                if (highlighter != null) highlighter.setTextFragmenter(new SimpleFragmenter(180));

                Map<String, Double> rrf = new HashMap<>();
                Map<String, Boolean> kwSet = new HashMap<>();
                Map<String, Boolean> vecSet = new HashMap<>();
                accumulate(kwHits, cfg.getRag().getRrfK(), rrf, kwSet, true);
                accumulate(vecHits, cfg.getRag().getRrfK(), rrf, vecSet, false);

                List<Map.Entry<String, Double>> ranked = new ArrayList<>(rrf.entrySet());
                ranked.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

                List<Cand> pool = new ArrayList<>();
                Map<String, Integer> perDoc = new HashMap<>();
                // 父块全文缓存：同文档多块命中只查一次索引
                Map<String, Map<Integer, String>> parentCache = new HashMap<>();
                for (Map.Entry<String, Double> e : ranked) {
                    if (pool.size() >= fetch) break;
                    int docId = Integer.parseInt(e.getKey());
                    Document d = searcher.storedFields().document(docId);
                    if (folder != null && !folder.isBlank()) {
                        String f = IndexSchema.getString(d, IndexSchema.FOLDER);
                        if (!f.equals(folder) && !f.startsWith(folder + "/")) continue;
                    }
                    String path = IndexSchema.getString(d, IndexSchema.DOC_PATH);
                    int seqNo = IndexSchema.getInt(d, IndexSchema.SEQ);
                    // 人工屏蔽的块直接跳过（解析错的表、过时段落）
                    if (chunkFlags.isDisabled(kb.id(), path, seqNo)) continue;
                    if (perDoc.merge(path, 1, Integer::sum) > mpd) continue;
                    String body = IndexSchema.getString(d, IndexSchema.BODY);
                    // 父子分块：命中子块 → 按父区间把小节全文拼回来，喂给重排与 LLM 上下文
                    String contextText = null;
                    int ps = IndexSchema.getInt(d, IndexSchema.PARENT_START);
                    int pe = IndexSchema.getInt(d, IndexSchema.PARENT_END);
                    if (pe > ps) {
                        contextText = parentText(searcher, path, ps, pe, parentCache);
                    }
                    SearchHit hit = new SearchHit(path,
                            IndexSchema.getString(d, IndexSchema.DOC_TITLE),
                            IndexSchema.getString(d, IndexSchema.EXT),
                            IndexSchema.getString(d, IndexSchema.KIND),
                            IndexSchema.getString(d, IndexSchema.FOLDER),
                            seqNo,
                            IndexSchema.getString(d, IndexSchema.HEADING),
                            highlight(body, highlighter, analyzer),
                            e.getValue(),
                            kwSet.containsKey(e.getKey()),
                            vecSet.containsKey(e.getKey()),
                            null,
                            contextText);
                    pool.add(new Cand(hit, body));
                }

                // ── 重排 + 阈值 ──
                List<SearchHit> out = new ArrayList<>();
                boolean rerankUsed = false;
                int dropped = 0;
                if (wantRerank && pool.size() > 1) {
                    // 送重排的文本带上「标题 · 小节」，否则表格类段落本身没有主题词，重排会跑偏
                    List<String> docs = new ArrayList<>();
                    for (Cand c : pool) docs.add(rerankText(c));
                    int keep = cfg.getRerank().getTopN() > 0 ? cfg.getRerank().getTopN() : k;
                    double[] scores = rerank.score(query, docs, Math.max(keep, k));
                    if (scores != null) {
                        rerankUsed = true;
                        double min = cfg.getRag().getMinRerankScore();
                        List<Integer> order = new ArrayList<>();
                        for (int i = 0; i < scores.length; i++) order.add(i);
                        order.sort(Comparator.comparingDouble((Integer i) -> scores[i]).reversed());
                        for (int i : order) {
                            if (scores[i] < min) { dropped++; continue; }
                            if (out.size() >= k) { dropped++; continue; }
                            out.add(withScore(pool.get(i).hit(), scores[i]));
                        }
                    }
                }
                if (!rerankUsed) {
                    // 未启用或调用失败：按相对阈值过滤（低于最高分 × minRelScore 的丢掉）
                    double best = pool.isEmpty() ? 0 : pool.get(0).hit().rrfScore();
                    double min = best * cfg.getRag().getMinRelScore();
                    for (Cand c : pool) {
                        if (c.hit().rrfScore() < min) { dropped++; continue; }
                        if (out.size() >= k) break;
                        out.add(c.hit());
                    }
                }
                return new Result(out, kwHits.size(), vecHits.size(), vectorUsed,
                        rerankUsed, dropped, expanded);
            } finally {
                mgr.release(searcher);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("检索失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 重排输入：恒用命中子块（标题 + 小节 + 子块正文，截 1200 字）。
     * 不用父块全文 —— 长文本会稀释相关度信号，把正确文档的分压到阈值以下。
     * 父块全文只用于喂 LLM 的上下文（SearchHit.contextText），即「小块重排、大块生成」。
     */
    private String rerankText(Cand c) {
        SearchHit h = c.hit();
        StringBuilder sb = new StringBuilder();
        if (h.docTitle() != null) sb.append(h.docTitle());
        if (h.heading() != null && !h.heading().isBlank()) sb.append(" · ").append(h.heading());
        sb.append('\n').append(c.body() == null ? "" : c.body());
        String text = sb.toString().strip();
        return text.length() <= 1200 ? text : text.substring(0, 1200);
    }

    /** 复制一条命中并挂上重排分数。 */
    private SearchHit withScore(SearchHit h, double score) {
        return new SearchHit(h.docPath(), h.docTitle(), h.ext(), h.kind(), h.folder(),
                h.seq(), h.heading(), h.snippetHtml(), h.rrfScore(), h.kwHit(), h.vecHit(),
                score, h.contextText());
    }

    /**
     * 父块全文：把 [ps..pe] 区间内的子块按 seq 拼回完整小节。
     * 同文档的块做一次索引查询并缓存，重排候选里同文档多块命中不会重复扫。
     */
    private String parentText(IndexSearcher searcher, String path, int ps, int pe,
                              Map<String, Map<Integer, String>> cache) {
        Map<Integer, String> bySeq = cache.computeIfAbsent(path, p -> {
            Map<Integer, String> m = new java.util.TreeMap<>();
            try {
                for (var sd : searcher.search(new TermQuery(new Term(IndexSchema.DOC_PATH, p)), 5000).scoreDocs) {
                    var doc = searcher.storedFields().document(sd.doc,
                            java.util.Set.of(IndexSchema.SEQ, IndexSchema.BODY));
                    m.put(IndexSchema.getInt(doc, IndexSchema.SEQ), IndexSchema.getString(doc, IndexSchema.BODY));
                }
            } catch (Exception e) {
                log.debug("读父块失败: {}", e.toString());
            }
            return m;
        });
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, ps); i <= pe; i++) {
            String t = bySeq.get(i);
            if (t == null || t.isBlank()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(t);
        }
        String s = sb.toString();
        return s.length() > 4000 ? s.substring(0, 4000) : s;
    }

    private void accumulate(List<ScoreDoc> hits, int rrfK, Map<String, Double> rrf,
                            Map<String, Boolean> mark, boolean kw) {
        int rank = 1;
        for (ScoreDoc sd : hits) {
            String key = String.valueOf(sd.doc);
            rrf.merge(key, 1.0 / (rrfK + rank), Double::sum);
            mark.put(key, kw);
            rank++;
        }
    }

    /** 分词后对 body(1.0)/headingText(2.0) 做 OR 组合；术语表补充词权重 0.6。 */
    private Query buildKwQuery(String query, List<String> extra, Analyzer analyzer) {
        List<String> tokens = tokenize(query, analyzer);
        if (tokens.isEmpty() && (extra == null || extra.isEmpty())) return null;
        BooleanQuery.Builder b = new BooleanQuery.Builder();
        for (String t : tokens) {
            b.add(new BoostQuery(new TermQuery(new Term(IndexSchema.BODY, t)), 1.0f), BooleanClause.Occur.SHOULD);
            b.add(new BoostQuery(new TermQuery(new Term(IndexSchema.HEADING_TEXT, t)), 2.0f), BooleanClause.Occur.SHOULD);
        }
        if (extra != null) {
            for (String e : extra) {
                for (String t : tokenize(e, analyzer)) {
                    if (tokens.contains(t)) continue;
                    b.add(new BoostQuery(new TermQuery(new Term(IndexSchema.BODY, t)), 0.6f), BooleanClause.Occur.SHOULD);
                    b.add(new BoostQuery(new TermQuery(new Term(IndexSchema.HEADING_TEXT, t)), 1.2f), BooleanClause.Occur.SHOULD);
                }
            }
        }
        return b.build();
    }

    private List<String> tokenize(String text, Analyzer analyzer) {
        List<String> out = new ArrayList<>();
        try (TokenStream ts = analyzer.tokenStream("", new StringReader(text))) {
            var term = ts.addAttribute(org.apache.lucene.analysis.tokenattributes.CharTermAttribute.class);
            ts.reset();
            while (ts.incrementToken()) out.add(term.toString());
            ts.end();
        } catch (Exception e) {
            log.warn("分词失败: {}", e.toString());
        }
        return out;
    }

    private String highlight(String body, Highlighter hl, Analyzer analyzer) {
        if (body == null || body.isBlank()) return "";
        if (hl == null) {
            return body.length() <= 180 ? body : body.substring(0, 180) + "…";
        }
        try {
            TokenStream ts = analyzer.tokenStream(IndexSchema.BODY, new StringReader(body));
            TextFragment[] frags = hl.getBestTextFragments(ts, body, false, 2);
            for (TextFragment f : frags) {
                if (f.getScore() > 0) return f.toString();
            }
            return body.length() <= 180 ? body : body.substring(0, 180) + "…";
        } catch (Exception e) {
            return body.length() <= 180 ? body : body.substring(0, 180) + "…";
        }
    }
}
