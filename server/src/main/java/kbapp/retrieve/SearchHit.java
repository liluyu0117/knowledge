package kbapp.retrieve;

/** 检索命中块。 */
public record SearchHit(
        String docPath, String docTitle, String ext, String kind, String folder,
        int seq, String heading, String snippetHtml,
        double rrfScore, boolean kwHit, boolean vecHit) {}
