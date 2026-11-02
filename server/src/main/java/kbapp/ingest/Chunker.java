package kbapp.ingest;

import kbapp.config.AppConfig;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 标题感知滑动窗口分块（设计文档 5.2）：
 * 目标 chunk_chars、相邻窗口重叠 overlap、短块向后并入当前窗口；
 * headingPath 取窗口首块的标题路径。
 */
@Component
public class Chunker {

    public List<Chunk> chunk(Extracted extracted, AppConfig.IndexCfg cfg) {
        int target = Math.max(100, cfg.getChunkChars());
        int overlap = Math.max(0, Math.min(cfg.getChunkOverlapChars(), target / 2));
        int minChars = Math.max(0, cfg.getMinChunkChars());

        List<Chunk> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        String bufHeading = "";

        for (ExtractedBlock b : extracted.blocks()) {
            String text = b.text().strip();
            if (text.isEmpty()) continue;

            if (text.length() < minChars) {
                // 短块并入当前窗口（无当前窗口则起新窗口）
                if (buf.isEmpty()) bufHeading = b.headingPath();
                appendWithSpace(buf, text);
                continue;
            }

            // 用游标推进避免对超长块反复 substring（O(n²) 隐患）
            int pos = 0;
            while (pos < text.length()) {
                int room = target - buf.length();
                if (room <= 0) {
                    // 当前窗口已满，落块并保留重叠尾巴
                    out.add(new Chunk(out.size(), bufHeading, buf.toString()));
                    String tail = overlap > 0 ? tail(buf, overlap) : "";
                    buf.setLength(0);
                    buf.append(tail);
                    bufHeading = b.headingPath();
                    room = target - buf.length();
                }
                int remain = text.length() - pos;
                if (remain <= room) {
                    if (buf.isEmpty()) bufHeading = b.headingPath();
                    appendWithSpace(buf, text.substring(pos));
                    pos = text.length();
                } else {
                    int cut = breakPoint(text.substring(pos, pos + room), room);
                    if (buf.isEmpty()) bufHeading = b.headingPath();
                    appendWithSpace(buf, text.substring(pos, pos + cut));
                    pos += cut;
                    // 跳过硬切后残留的空白
                    while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
                }
            }
        }
        if (!buf.isEmpty()) {
            out.add(new Chunk(out.size(), bufHeading, buf.toString()));
        }
        return out;
    }

    /** 尽量在换行/句读处断开，回退到硬切。 */
    private int breakPoint(String text, int room) {
        int limit = Math.min(room, text.length());
        int nl = text.lastIndexOf('\n', limit - 1);
        if (nl > limit / 2) return nl + 1;
        for (int i = limit - 1; i > limit / 2; i--) {
            char c = text.charAt(i);
            if (c == '。' || c == '；' || c == '；' || c == '.' || c == '！' || c == '？'
                    || c == '!' || c == '?' || c == ' ') return i + 1;
        }
        return limit;
    }

    private String tail(StringBuilder buf, int overlapChars) {
        String s = buf.toString();
        int from = Math.max(0, s.length() - overlapChars);
        String t = s.substring(from);
        int nl = t.indexOf('\n');
        return nl >= 0 ? t.substring(nl + 1) : t;
    }

    private void appendWithSpace(StringBuilder buf, String text) {
        if (!buf.isEmpty() && buf.charAt(buf.length() - 1) != '\n') buf.append('\n');
        buf.append(text);
    }
}
