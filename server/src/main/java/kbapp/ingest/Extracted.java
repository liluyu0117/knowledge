package kbapp.ingest;

import java.util.List;

/** 一个文件解析后的全部语义块，按文档顺序排列。 */
public record Extracted(List<ExtractedBlock> blocks) {

    public int totalChars() {
        int n = 0;
        for (ExtractedBlock b : blocks) n += b.text().length();
        return n;
    }
}
