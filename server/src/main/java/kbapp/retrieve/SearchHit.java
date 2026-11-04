package kbapp.retrieve;

/** 检索命中块。 */
public record SearchHit(
        String docPath, String docTitle, String ext, String kind, String folder,
        int seq, String heading, String snippetHtml,
        double rrfScore, boolean kwHit, boolean vecHit,
        /** 重排分数（0~1）；未启用 rerank 时为 null。 */
        Double rerankScore,
        /**
         * 喂给 LLM 的上下文文本：父子分块模式下是父块（小节）全文，fixed 模式为 null（用 snippet）。
         * 引用定位仍以 seq（命中子块）为准。
         */
        String contextText,
        /** 命中所在知识库 id（跨库检索时区分来源；单库检索为该库 id）。 */
        String kbId) {

    /** 兼容旧调用（无 contextText）。 */
    public SearchHit(String docPath, String docTitle, String ext, String kind, String folder,
                     int seq, String heading, String snippetHtml,
                     double rrfScore, boolean kwHit, boolean vecHit, Double rerankScore) {
        this(docPath, docTitle, ext, kind, folder, seq, heading, snippetHtml,
                rrfScore, kwHit, vecHit, rerankScore, null, null);
    }
}
