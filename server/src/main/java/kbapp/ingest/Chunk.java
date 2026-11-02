package kbapp.ingest;

/**
 * 索引分块：块序号 + 所属标题路径 + 正文。
 * parentStart/parentEnd：父子分块模式下该子块所属「父块」覆盖的子块 seq 区间（含端点）；
 * fixed 模式下两者都等于 seq，检索回读时无差异。
 */
public record Chunk(int seq, String headingPath, String text, int parentStart, int parentEnd) {
    public Chunk(int seq, String headingPath, String text) {
        this(seq, headingPath, text, seq, seq);
    }
}
