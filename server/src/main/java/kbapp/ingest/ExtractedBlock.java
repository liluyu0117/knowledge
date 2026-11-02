package kbapp.ingest;

/** 解析产出的一个语义块（通常对应一个标题段落/一页/一个 sheet）。 */
public record ExtractedBlock(String headingPath, String text) {

    public boolean hasText() {
        return text != null && !text.isBlank();
    }
}
