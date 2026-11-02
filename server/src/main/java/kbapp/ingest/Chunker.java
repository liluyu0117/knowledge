package kbapp.ingest;

import kbapp.config.AppConfig;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 标题感知滑动窗口分块（设计文档 5.2）：
 * 目标 chunk_chars、相邻窗口重叠 overlap、短块向后并入当前窗口；
 * headingPath 取窗口首块的标题路径。
 *
 * 另提供父子分块 chunkParentChild()：子块切小（默认 ~380 字）用于检索召回，
 * 父块 = 同一小节的连续子块群（默认 ≤2000 字）用于命中后回读完整上下文；
 * 父块不进索引，只以「父区间（parentStart..parentEnd）」挂在子块上，检索时动态拼接。
 */
@Component
public class Chunker {

    /**
     * 父子分块：子块切小（默认 ~500 字、带 overlap 滑窗）进索引用于精确召回；
     * 父块 = 同一小节的连续子块群（默认 ≤2000 字），不落索引，
     * 只把「父区间（parentStart..parentEnd）」挂在子块上，检索命中后按区间把子块拼回完整上下文。
     */
    public List<Chunk> chunkParentChild(Extracted extracted, AppConfig.IndexCfg cfg) {
        int childTarget = Math.max(120, cfg.getChildChars());
        int overlap = Math.max(0, Math.min(cfg.getChunkOverlapChars(), childTarget / 2));
        int parentLimit = Math.max(childTarget * 2, cfg.getParentChars());
        int minChars = Math.max(0, cfg.getMinChunkChars());

        List<Chunk> children = new ArrayList<>();
        String curHeading = null;
        int curStart = -1, curChars = 0;

        for (ExtractedBlock b : extracted.blocks()) {
            String text = b.text().strip();
            if (text.isEmpty()) continue;
            String heading = b.headingPath() == null ? "" : b.headingPath();

            List<String> pieces = slidingPieces(text, childTarget, overlap);
            boolean first = true;
            for (String piece : pieces) {
                boolean tiny = piece.length() < minChars;
                boolean newSection = !heading.equals(curHeading)
                        || (curStart >= 0 && curChars + piece.length() > parentLimit && !tiny);
                if (newSection && curStart >= 0) {
                    markParent(children, curStart, children.size() - 1);
                    curStart = -1;
                }
                if (curStart < 0) {
                    curHeading = heading;
                    curStart = children.size();
                    curChars = 0;
                }
                // 碎片段落并入前一个子块，避免同一小节内出现大量微小块
                if (tiny && children.size() - 1 >= curStart
                        && children.get(children.size() - 1).text().length() + piece.length() <= childTarget) {
                    Chunk prev = children.get(children.size() - 1);
                    children.set(children.size() - 1,
                            new Chunk(prev.seq(), prev.headingPath(), prev.text() + "\n" + piece));
                    curChars += piece.length();
                    continue;
                }
                children.add(new Chunk(children.size(), heading, piece));
                curChars += piece.length();
                first = false;
            }
        }
        if (curStart >= 0) markParent(children, curStart, children.size() - 1);
        return children;
    }

    /** 带 overlap 的滑窗切片：每段 ≤target，相邻段共享 overlap 字尾巴，尽量在句读处断开。 */
    private List<String> slidingPieces(String text, int target, int overlap) {
        List<String> out = new ArrayList<>();
        if (text.length() <= target) { out.add(text); return out; }
        int step = Math.max(1, target - overlap);
        int pos = 0;
        while (pos < text.length()) {
            int end = Math.min(text.length(), pos + target);
            int cut = end;
            if (end < text.length()) cut = pos + breakPoint(text.substring(pos, end), end - pos);
            out.add(text.substring(pos, cut));
            if (cut >= text.length()) break;
            pos = cut - overlap;
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
            if (pos <= 0) pos = cut + 1;
        }
        return out;
    }

    /** 给 [start..end] 区间的子块挂父区间。 */
    private void markParent(List<Chunk> children, int start, int end) {
        for (int i = start; i <= end; i++) {
            Chunk c = children.get(i);
            children.set(i, new Chunk(c.seq(), c.headingPath(), c.text(), start, end));
        }
    }

    /** 取不超过 target 的首段（chunk() 滑窗内部自行推进，无需要此方法时删除）。 */
    private String cutToTarget(String text, int target) {
        if (text.length() <= target) return text;
        int cut = breakPoint(text, target);
        return text.substring(0, cut);
    }

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
