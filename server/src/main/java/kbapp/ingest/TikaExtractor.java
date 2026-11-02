package kbapp.ingest;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档解析：Office 系列走 POI 保留标题层级；其余类型 Tika 抽取纯文本。
 * 产出带标题路径的语义块流（设计文档 5.2）。
 */
@Component
public class TikaExtractor {

    private static final Logger log = LoggerFactory.getLogger(TikaExtractor.class);
    private static final Pattern MD_HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern HTML_HEADING = Pattern.compile("<h([1-6])[^>]*>(.*?)</h\\1>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");

    public Extracted extract(Path file) {
        String ext = ScanFilter.extOf(file.getFileName().toString());
        try {
            return switch (ext) {
                case ".docx" -> extractDocx(file);
                case ".xlsx", ".xls" -> extractExcel(file);
                case ".pptx" -> extractPptx(file);
                case ".md", ".markdown" -> extractMarkdown(file);
                case ".html", ".htm" -> extractHtml(file);
                case ".doc", ".ppt" -> legacyAsSingleBlock(file, "旧版二进制格式 " + ext + " 仅做全文抽取");
                default -> tikaWhole(file);
            };
        } catch (Exception e) {
            log.warn("解析失败 {}: {}", file, e.toString());
            throw new IllegalStateException("解析失败: " + file, e);
        }
    }

    /** docx：段落样式 HeadingN / 大纲级别判标题，表格转制表文本。 */
    private Extracted extractDocx(Path file) throws Exception {
        List<ExtractedBlock> blocks = new ArrayList<>();
        try (InputStream in = Files.newInputStream(file); XWPFDocument doc = new XWPFDocument(in)) {
            int[] level = {0};
            StringBuilder heading = new StringBuilder();
            StringBuilder buf = new StringBuilder();
            for (IBodyElement el : doc.getBodyElements()) {
                if (el instanceof XWPFParagraph p) {
                    int lv = headingLevel(p);
                    String text = p.getText() == null ? "" : p.getText().trim();
                    if (lv > 0 && !text.isEmpty()) {
                        flush(blocks, heading, buf);
                        level[0] = lv;
                        // 标题路径只保留到当前层级（如 1级/3级）
                        heading.setLength(0);
                        heading.append(text);
                        continue;
                    }
                    if (!text.isEmpty()) buf.append(text).append('\n');
                } else if (el instanceof XWPFTable t) {
                    buf.append(tableText(t)).append('\n');
                }
            }
            flush(blocks, heading, buf);
        }
        blocks.removeIf(b -> !b.hasText());
        return new Extracted(blocks);
    }

    private int headingLevel(XWPFParagraph p) {
        String style = p.getStyle();
        if (style != null) {
            Matcher m = Pattern.compile("(?i)heading\\s*([1-9])").matcher(style);
            if (m.find()) return Integer.parseInt(m.group(1));
        }
        return 0;
    }

    private String tableText(XWPFTable t) {
        StringBuilder sb = new StringBuilder();
        for (XWPFTableRow row : t.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell c : row.getTableCells()) {
                cells.add(c.getText().replaceAll("\\s+", " ").trim());
            }
            sb.append(String.join(" | ", cells)).append('\n');
        }
        return sb.toString();
    }

    /** xlsx/xls：每个 sheet 一块，sheet 名即标题，行内单元格以 | 连接。 */
    private Extracted extractExcel(Path file) throws Exception {
        List<ExtractedBlock> blocks = new ArrayList<>();
        try (InputStream in = Files.newInputStream(file); Workbook wb = ".xls".equals(
                ScanFilter.extOf(file.getFileName().toString())) ? new HSSFWorkbook(in) : new XSSFWorkbook(in)) {
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                Sheet sheet = wb.getSheetAt(i);
                StringBuilder sb = new StringBuilder();
                for (Row row : sheet) {
                    List<String> cells = new ArrayList<>();
                    short last = row.getLastCellNum();
                    for (int c = 0; c < last; c++) {
                        var cell = row.getCell(c);
                        String v = cell == null ? "" : switch (cell.getCellType()) {
                            case STRING -> cell.getStringCellValue().trim();
                            case NUMERIC -> String.valueOf(cell.getNumericCellValue());
                            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
                            case FORMULA -> {
                                try { yield cell.toString(); }
                                catch (Exception e) { yield ""; }
                            }
                            default -> "";
                        };
                        cells.add(v);
                    }
                    String line = String.join(" | ", cells).replaceAll("(\\s*\\|\\s*)+$", "");
                    if (!line.isBlank()) sb.append(line).append('\n');
                }
                if (!sb.isEmpty()) {
                    blocks.add(new ExtractedBlock(sheet.getSheetName(), sb.toString()));
                }
            }
        }
        return new Extracted(blocks);
    }

    /** pptx：每页一块，"第 n 页"为标题，页内形状文本顺序拼接。 */
    private Extracted extractPptx(Path file) throws Exception {
        List<ExtractedBlock> blocks = new ArrayList<>();
        try (InputStream in = Files.newInputStream(file); XMLSlideShow ppt = new XMLSlideShow(in)) {
            int n = 1;
            for (XSLFSlide slide : ppt.getSlides()) {
                StringBuilder sb = new StringBuilder();
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof org.apache.poi.xslf.usermodel.XSLFTextShape ts) {
                        for (XSLFTextParagraph para : ts.getTextParagraphs()) {
                            String t = para.getText();
                            if (t != null && !t.isBlank()) sb.append(t.trim()).append('\n');
                        }
                    }
                }
                if (!sb.isEmpty()) blocks.add(new ExtractedBlock("第 " + n + " 页", sb.toString()));
                n++;
            }
        }
        return new Extracted(blocks);
    }

    /** markdown：# 层级为标题路径（拼接两级，如 "一级 / 二级"）。 */
    private Extracted extractMarkdown(Path file) throws Exception {
        List<ExtractedBlock> blocks = new ArrayList<>();
        List<String> stack = new ArrayList<>(); // stack[i] = i+1 级最近标题
        StringBuilder buf = new StringBuilder();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            Matcher m = MD_HEADING.matcher(line.trim());
            if (m.find()) {
                if (!buf.isEmpty()) {
                    blocks.add(new ExtractedBlock(String.join(" / ", stack), buf.toString()));
                    buf.setLength(0);
                }
                int lv = m.group(1).length();
                while (stack.size() >= lv) stack.remove(stack.size() - 1);
                while (stack.size() < lv - 1) stack.add("");
                stack.add(m.group(2).trim());
            } else {
                buf.append(line).append('\n');
            }
        }
        if (!buf.isEmpty()) blocks.add(new ExtractedBlock(String.join(" / ", stack), buf.toString()));
        blocks.removeIf(b -> !b.hasText());
        return new Extracted(blocks);
    }

    /** html：h1-h6 切段（Axure 导出等无标题页面自然归为单块）。 */
    private Extracted extractHtml(Path file) throws Exception {
        String html = Files.readString(file, StandardCharsets.UTF_8);
        List<ExtractedBlock> blocks = new ArrayList<>();
        Matcher m = HTML_HEADING.matcher(html);
        List<String> stack = new ArrayList<>();
        record Head(int start, int end, int level, String text) {}
        List<Head> heads = new ArrayList<>();
        while (m.find()) {
            heads.add(new Head(m.start(), m.end(), Integer.parseInt(m.group(1)),
                    HTML_TAG.matcher(m.group(2)).replaceAll("").trim()));
        }
        int lastEnd = 0;
        for (Head h : heads) {
            if (h.start() > lastEnd) addHtmlBody(blocks, stack, html.substring(lastEnd, h.start()));
            while (stack.size() >= h.level()) stack.remove(stack.size() - 1);
            while (stack.size() < h.level() - 1) stack.add("");
            stack.add(h.text());
            lastEnd = h.end();
        }
        if (lastEnd < html.length()) addHtmlBody(blocks, stack, html.substring(lastEnd));
        blocks.removeIf(b -> !b.hasText());
        return new Extracted(blocks);
    }

    private void addHtmlBody(List<ExtractedBlock> blocks, List<String> stack, String raw) {
        String text = HTML_TAG.matcher(raw).replaceAll(" ");
        text = text.replace("&nbsp;", " ").replaceAll("[ \\t]+", " ").trim();
        if (!text.isEmpty()) {
            blocks.add(new ExtractedBlock(String.join(" / ", stack), text));
        }
    }

    /** 旧版 .doc/.ppt：Tika 全文，单块。 */
    private Extracted legacyAsSingleBlock(Path file, String note) throws Exception {
        log.info("{}: {}", file.getFileName(), note);
        return tikaWhole(file);
    }

    /** 兜底：Tika 全文单块。 */
    private Extracted tikaWhole(Path file) throws Exception {
        AutoDetectParser parser = new AutoDetectParser();
        BodyContentHandler handler = new BodyContentHandler(-1);
        Metadata meta = new Metadata();
        try (InputStream in = Files.newInputStream(file)) {
            parser.parse(in, handler, meta, new ParseContext());
        }
        String text = handler.toString().replaceAll("\\n{3,}", "\n\n").trim();
        return new Extracted(text.isEmpty() ? List.of() : List.of(new ExtractedBlock("", text)));
    }

    private void flush(List<ExtractedBlock> blocks, StringBuilder heading, StringBuilder buf) {
        if (!buf.isEmpty()) {
            blocks.add(new ExtractedBlock(heading.toString().trim(), buf.toString()));
            buf.setLength(0);
        }
    }
}
