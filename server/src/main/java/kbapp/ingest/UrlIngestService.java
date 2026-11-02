package kbapp.ingest;

import kbapp.kb.KbRegistry;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * URL 网页接入：抓取 → 正文去噪 → 转 Markdown 落到知识库副本目录。
 *
 * 说明：
 * - 只支持公网可访问、无需登录态的页面（带 cookie/JS 渲染的页面抓到的会是壳，抓前给出提示）；
 * - 去噪策略：优先取 main/article/.content 语义容器，剔除 nav/footer/aside/script 等噪音节点；
 * - 每页 ≤2MB 正文，超长截断；文件名从 <title> 或 URL 推导，重名自动加序号。
 */
@Service
public class UrlIngestService {

    private static final Logger log = LoggerFactory.getLogger(UrlIngestService.class);
    private static final int MAX_BODY_CHARS = 200_000;
    private static final int TIMEOUT_MS = 20_000;
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Safari/537.36";

    private final KbRegistry registry;

    public UrlIngestService(KbRegistry registry) {
        this.registry = registry;
    }

    public record UrlResult(String url, boolean ok, String path, String title, int chars, String error) {}

    /** 批量抓取：urlLines 每行一条；folder 为知识库内目标子目录（空 = 根）。 */
    public List<UrlResult> fetchAll(String kbId, List<String> urlLines, String folder) {
        List<UrlResult> out = new ArrayList<>();
        KbRegistry.KbEntry kb = registry.byId(kbId);
        if (kb == null) {
            out.add(new UrlResult("", false, "", "", 0, "知识库不存在"));
            return out;
        }
        Path root = registry.docsRoot(kb);
        Path dir = root;
        if (folder != null && !folder.isBlank()) {
            String f = folder.replace('\\', '/');
            for (String part : f.split("/")) {
                if (part.isBlank() || part.equals(".") || part.equals("..")) {
                    out.add(new UrlResult("", false, "", "", 0, "目录不合法: " + folder));
                    return out;
                }
            }
            dir = root.resolve(f).normalize();
            if (!dir.startsWith(root)) {
                out.add(new UrlResult("", false, "", "", 0, "目录不合法"));
                return out;
            }
        }
        java.util.Set<String> usedNames = new java.util.HashSet<>();
        for (String line : urlLines) {
            String url = line == null ? "" : line.trim();
            if (url.isEmpty() || url.startsWith("#")) continue;
            out.add(fetchOne(dir, root, url, usedNames));
        }
        return out;
    }

    private UrlResult fetchOne(Path dir, Path root, String url, java.util.Set<String> usedNames) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return new UrlResult(url, false, "", "", 0, "仅支持 http/https 链接");
        }
        try {
            Document doc = Jsoup.connect(url)
                    .userAgent(UA)
                    .timeout(TIMEOUT_MS)
                    .maxBodySize(3 * 1024 * 1024)
                    .followRedirects(true)
                    .get();
            String title = cleanTitle(doc.title());
            String md = toMarkdown(doc);
            if (md.length() > MAX_BODY_CHARS) md = md.substring(0, MAX_BODY_CHARS) + "\n\n（内容超长已截断）";
            if (md.strip().length() < 80) {
                return new UrlResult(url, false, "", title, 0,
                        "正文过短（可能是 JS 渲染页或需要登录），暂不支持该类页面");
            }
            String base = sanitizeFileName(title.isEmpty() ? fileNameFromUrl(url) : title);
            String name = uniqueName(dir, base, usedNames);
            Path target = dir.resolve(name + ".md");
            Files.createDirectories(dir);
            StringBuilder file = new StringBuilder();
            file.append("---\nsource: ").append(url)
                .append("\nfetchedAt: ").append(LocalDateTime.now().withNano(0))
                .append("\n---\n\n# ").append(title.isEmpty() ? base : title).append("\n\n");
            file.append(md);
            Files.writeString(target, file.toString(), StandardCharsets.UTF_8);
            return new UrlResult(url, true, root.relativize(target).toString().replace('\\', '/'),
                    title, md.length(), "");
        } catch (IOException e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            if (msg.length() > 200) msg = msg.substring(0, 200);
            log.info("URL 抓取失败 {}: {}", url, msg);
            return new UrlResult(url, false, "", "", 0, msg);
        }
    }

    /** 正文去噪：优先语义容器，剔噪音，按标题/段落/列表转 Markdown。 */
    private String toMarkdown(Document doc) {
        doc.select("script,style,noscript,iframe,svg,nav,footer,header,aside,form,button,"
                + ".nav,.navbar,.footer,.header,.sidebar,.menu,.breadcrumb,.pagination,"
                + "#nav,#footer,#header,#sidebar,#comment,.comment,#comment-list,.ad,.ads,.advertisement").remove();

        Element main = doc.selectFirst("main, article, [role=main], .content, .article-content, "
                + ".markdown-body, #content, .post-content, .entry-content");
        Element scope = main != null ? main : doc.body();
        if (scope == null) return "";

        StringBuilder sb = new StringBuilder();
        walk(scope, sb, 0);
        return sb.toString().replaceAll("\n{3,}", "\n\n").strip();
    }

    /** 深度优先把块级元素转成 Markdown 行。 */
    private void walk(Element el, StringBuilder sb, int listDepth) {
        for (org.jsoup.nodes.Node node : el.childNodes()) {
            if (node instanceof Element child) {
                String tag = child.tagName().toLowerCase();
                switch (tag) {
                    case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                        int level = tag.charAt(1) - '0';
                        sb.append("\n").append("#".repeat(Math.min(level + 1, 6))).append(' ')
                          .append(child.text().strip()).append("\n\n");
                    }
                    case "p" -> {
                        String t = child.text().strip();
                        if (!t.isEmpty()) sb.append(t).append("\n\n");
                    }
                    case "li" -> {
                        String t = child.text().strip();
                        if (!t.isEmpty()) sb.append("  ".repeat(Math.min(listDepth, 3))).append("- ").append(t).append('\n');
                    }
                    case "ul", "ol" -> walk(child, sb, listDepth + 1);
                    case "pre" -> {
                        sb.append("\n```\n").append(child.text().strip()).append("\n```\n\n");
                    }
                    case "table" -> tableToMd(child, sb);
                    case "blockquote" -> {
                        for (String line : child.text().strip().split("\n")) {
                            sb.append("> ").append(line.strip()).append('\n');
                        }
                        sb.append('\n');
                    }
                    case "hr" -> sb.append("\n---\n\n");
                    case "div", "section", "article", "main", "span", "td", "tr", "tbody", "thead" -> {
                        // 容器类继续下钻；无块级子节点的 div 当段落收
                        if (child.children().isEmpty()) {
                            String t = child.text().strip();
                            if (!t.isEmpty()) sb.append(t).append("\n\n");
                        } else if (tag.equals("div") || tag.equals("section")) {
                            walk(child, sb, listDepth);
                        } else {
                            walk(child, sb, listDepth);
                        }
                    }
                    default -> { /* 行内噪音忽略 */ }
                }
            }
        }
    }

    /** 简单表格转 Markdown（首个 tr 当表头）。 */
    private void tableToMd(Element table, StringBuilder sb) {
        Elements rows = table.select("tr");
        if (rows.isEmpty()) return;
        sb.append('\n');
        int cols = 0;
        List<List<String>> grid = new ArrayList<>();
        for (Element tr : rows) {
            List<String> cells = new ArrayList<>();
            for (Element td : tr.select("th,td")) cells.add(td.text().strip());
            if (cells.isEmpty()) continue;
            cols = Math.max(cols, cells.size());
            grid.add(cells);
            if (grid.size() >= 60) break;   // 超大表截断
        }
        if (grid.isEmpty()) return;
        for (int i = 0; i < cols; i++) sb.append("| ").append(grid.get(0).size() > i ? grid.get(0).get(i) : "").append(' ');
        sb.append("|\n");
        sb.append("|".repeat(Math.max(1, cols + 1))).append("\n");
        for (int r = 1; r < grid.size(); r++) {
            for (int i = 0; i < cols; i++) sb.append("| ").append(grid.get(r).size() > i ? grid.get(r).get(i) : "").append(' ');
            sb.append("|\n");
        }
        sb.append('\n');
    }

    private String cleanTitle(String t) {
        return t == null ? "" : t.replaceAll("\\s+", " ").strip();
    }

    private String fileNameFromUrl(String url) {
        String u = url.replaceAll("[?#].*$", "");
        int i = u.lastIndexOf('/');
        String last = i >= 0 ? u.substring(i + 1) : u;
        last = last.replaceAll("\\.[a-zA-Z0-9]{1,5}$", "");
        return last.isBlank() ? u.replaceAll("https?://", "") : last;
    }

    private String sanitizeFileName(String name) {
        String s = name.replaceAll("[\\\\/:*?\"<>|\\n\\r\\t]", " ").replaceAll("\\s+", " ").strip();
        if (s.length() > 60) s = s.substring(0, 60).strip();
        return s.isEmpty() ? "网页" : s;
    }

    /** 同名自动加序号：name.md、name-2.md、name-3.md… */
    private String uniqueName(Path dir, String base, java.util.Set<String> usedNames) {
        String candidate = base;
        int i = 2;
        while (usedNames.contains(candidate.toLowerCase())
                || Files.exists(dir.resolve(candidate + ".md"))) {
            candidate = base + "-" + i++;
        }
        usedNames.add(candidate.toLowerCase());
        return candidate;
    }
}
