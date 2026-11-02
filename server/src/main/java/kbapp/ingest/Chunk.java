package kbapp.ingest;

/** 索引分块：块序号 + 所属标题路径 + 正文。 */
public record Chunk(int seq, String headingPath, String text) {}
