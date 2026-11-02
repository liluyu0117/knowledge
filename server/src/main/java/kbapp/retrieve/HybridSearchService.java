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
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.highlight.Highlighter;
import org.apache.lucene.search.highlight.QueryScorer;
import org.apache.lucene.search.highlight.SimpleFragmenter;
import org.apache.lucene.search.highlight.SimpleHTMLFormatter;
import org.apache.lucene.search.highlight.TextFragment;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.SearcherManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合检索（设计文档 6）：BM25 与 KNN 双路召回 → RRF 融合 → 每文档去重 → 高亮。
 */
@Service
public class HybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);

    private final SearcherService searcherService;
    private final ConfigService config;
    private final EmbeddingClient embedding;
    private final kbapp.kb.KbRegistry registry;

    public HybridSearchService(SearcherService searcherService, ConfigService config,
                               EmbeddingClient embedding, kbapp.kb.KbRegistry registry) {
        this.searcherService = searcherService;
        this.config = config;
        this.embedding = embedding;
        this.registry = registry;
    }

    public record Result(List<SearchHit> hits, int candidatesKw, int candidatesVec, boolean vectorUsed) {}

    public Result search(String query, Integer topK, String folder, Integer maxPerDoc, boolean withVector) {
        return search(query, topK, folder, maxPerDoc, withVector, null);
    }

    public Result search(String query, Integer topK, String folder, Integer maxPerDoc, boolean withVector,
                         String kbId) {
        AppConfig cfg = config.get();
        int k = topK != null && topK > 0 ? topK : cfg.getRag().getTopK();
        int mpd = maxPerDoc != null && maxPerDoc > 0 ? maxPerDoc : cfg.getRag().getMaxPerDoc();
        int cand = cfg.getRag().getCandidate();
        if (query == null || query.isBlank()) {
            return new Result(List.of(), 0, 0, false);
        }
        query = query.strip();

        // 解析知识库索引目录
        kbapp.kb.KbRegistry.KbEntry kb = registry.byId(kbId);
        if (kb == null) throw new IllegalStateException("知识库不存在: " + kbId);
        Path indexDir = registry.indexDir(kb);

        try (Analyzer analyzer = AnalyzerFactory.create(cfg.getRag().getAnalyzer())) {
            SearcherManager mgr = searcherService.manager(indexDir);
            mgr.maybeRefresh();
            IndexSearcher searcher = mgr.acquire();
            try {
                Query kwQuery = buildKwQuery(query, analyzer);
                List<ScoreDoc> kwHits = kwQuery == null ? List.of()
                        : List.of(searcher.search(kwQuery, cand).scoreDocs);

                List<ScoreDoc> vecHits = List.of();
                boolean vectorUsed = false;
                if (withVector) {
                    try {
                        float[] qv = embedding.embed(query);
                        if (qv != null && qv.length > 0) {
                            Query vq = new KnnFloatVectorQuery(IndexSchema.VECTOR, qv, cand);
                            vecHits = List.of(searcher.search(vq, cand).scoreDocs);
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

                // 融合排序 → folder 过滤 → 每文档去重
                List<Map.Entry<String, Double>> ranked = new ArrayList<>(rrf.entrySet());
                ranked.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

                List<SearchHit> out = new ArrayList<>();
                Map<String, Integer> perDoc = new HashMap<>();
                for (Map.Entry<String, Double> e : ranked) {
                    if (out.size() >= k) break;
                    int docId = Integer.parseInt(e.getKey());
                    Document d = searcher.storedFields().document(docId);
                    if (folder != null && !folder.isBlank()) {
                        String f = IndexSchema.getString(d, IndexSchema.FOLDER);
                        if (!f.equals(folder) && !f.startsWith(folder + "/")) continue;
                    }
                    String path = IndexSchema.getString(d, IndexSchema.DOC_PATH);
                    int seen = perDoc.merge(path, 1, Integer::sum);
                    if (seen > mpd) continue;
                    String body = IndexSchema.getString(d, IndexSchema.BODY);
                    String snippet = highlight(body, highlighter, analyzer);
                    out.add(new SearchHit(path,
                            IndexSchema.getString(d, IndexSchema.DOC_TITLE),
                            IndexSchema.getString(d, IndexSchema.EXT),
                            IndexSchema.getString(d, IndexSchema.KIND),
                            IndexSchema.getString(d, IndexSchema.FOLDER),
                            IndexSchema.getInt(d, IndexSchema.SEQ),
                            IndexSchema.getString(d, IndexSchema.HEADING),
                            snippet,
                            e.getValue(),
                            kwSet.containsKey(e.getKey()),
                            vecSet.containsKey(e.getKey())));
                }
                return new Result(out, kwHits.size(), vecHits.size(), vectorUsed);
            } finally {
                mgr.release(searcher);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("检索失败: " + ex.getMessage(), ex);
        }
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

    /** 分词后对 body(1.0)/headingText(2.0) 做 OR 组合。 */
    private Query buildKwQuery(String query, Analyzer analyzer) {
        List<String> tokens = tokenize(query, analyzer);
        if (tokens.isEmpty()) return null;
        BooleanQuery.Builder b = new BooleanQuery.Builder();
        for (String t : tokens) {
            b.add(new BoostQuery(new TermQuery(new Term(IndexSchema.BODY, t)), 1.0f),
                    BooleanClause.Occur.SHOULD);
            b.add(new BoostQuery(new TermQuery(new Term(IndexSchema.HEADING_TEXT, t)), 2.0f),
                    BooleanClause.Occur.SHOULD);
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
