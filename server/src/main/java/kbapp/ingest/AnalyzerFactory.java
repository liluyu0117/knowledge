package kbapp.ingest;

import kbapp.config.AppConfig;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.ngram.NGramTokenizer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;

/** 中文分析器工厂：smart（SmartChineseAnalyzer）| bigram（二元切分），配置切换。 */
public final class AnalyzerFactory {

    private AnalyzerFactory() {}

    public static Analyzer create(String kind) {
        if ("bigram".equalsIgnoreCase(kind)) {
            // CJK 二元切分：召回稳，精度略逊；索引与查询两侧必须一致
            return new Analyzer() {
                @Override
                protected TokenStreamComponents createComponents(String fieldName) {
                    NGramTokenizer t = new NGramTokenizer(2, 2);
                    return new TokenStreamComponents(t, new LowerCaseFilter(t));
                }
            };
        }
        if ("standard".equalsIgnoreCase(kind)) {
            return new StandardAnalyzer();
        }
        return new SmartChineseAnalyzer();
    }
}
