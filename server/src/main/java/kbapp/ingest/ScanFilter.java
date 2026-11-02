package kbapp.ingest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/** 扫描过滤规则：扩展名白名单、排除目录、生成物文件名（设计文档 5.1）。 */
public final class ScanFilter {

    /** 只索引"可能含文本"的扩展名；图片/字体/压缩包等直接跳过。 */
    public static final Set<String> INCLUDE_EXT = Set.of(
            ".docx", ".doc", ".pdf", ".xlsx", ".xls", ".pptx", ".ppt",
            ".md", ".markdown", ".txt", ".sql", ".yml", ".yaml",
            ".java", ".py", ".ts", ".sh", ".bat",
            ".html", ".htm", ".csv", ".log", ".properties", ".ini", ".conf");

    public static final Set<String> EXCLUDE_DIRS = Set.of(
            ".kb", ".git", ".svn", ".idea", ".vscode", "node_modules",
            ".venv", "venv", "$recycle.bin", "system volume information");

    private static final String[] EXCLUDE_PREFIXES = {"~$"};
    private static final String[] EXCLUDE_SUFFIXES = {
            ".tmp", ".bak", ".swp", ".ds_store"};

    private static final Set<String> EXCLUDE_NAMES = Set.of(
            "thumbs.db", ".ds_store");

    private ScanFilter() {}

    public static boolean isExcludedDir(String dirName) {
        return dirName != null && EXCLUDE_DIRS.contains(dirName.toLowerCase(Locale.ROOT));
    }

    public static boolean isExcludedFile(String fileName) {
        if (fileName == null || fileName.isBlank()) return true;
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (EXCLUDE_NAMES.contains(lower)) return true;
        for (String p : EXCLUDE_PREFIXES) if (lower.startsWith(p)) return true;
        for (String s : EXCLUDE_SUFFIXES) if (lower.endsWith(s)) return true;
        return false;
    }

    public static boolean isIndexedExt(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) return false;
        return INCLUDE_EXT.contains(fileName.substring(dot).toLowerCase(Locale.ROOT));
    }

    public static boolean accept(String fileName) {
        return !isExcludedFile(fileName) && isIndexedExt(fileName);
    }

    /** 0 字节文件直接跳过（常见于"新建 DOCX 文档"类空文件）。 */
    public static boolean isEmptyFile(Path p) {
        try {
            return Files.size(p) == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static String extOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot).toLowerCase(Locale.ROOT);
    }
}
