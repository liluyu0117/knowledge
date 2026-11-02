package kbapp.notes;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import kbapp.config.AppPaths;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 独立笔记：存储于 .kb/notes/（md/txt 原样、doc 生成 .docx），注册表 notes.json。
 */
@Service
public class NotesStore {

    private static final Logger log = LoggerFactory.getLogger(NotesStore.class);
    private static final ObjectMapper M = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public record Note(String id, String title, String type, String createdAt, String updatedAt) {}

    private final AppPaths paths;
    private volatile List<Note> notes = new ArrayList<>();

    public NotesStore(AppPaths paths) {
        this.paths = paths;
    }

    private Path regFile() { return paths.dataDir().resolve("notes.json"); }
    private Path notesDir() { return paths.dataDir().resolve("notes"); }

    @PostConstruct
    public synchronized void init() {
        try {
            Files.createDirectories(notesDir());
            Path f = regFile();
            if (Files.exists(f)) {
                notes = new ArrayList<>(List.of(M.readValue(f.toFile(), Note[].class)));
            }
        } catch (IOException e) {
            log.warn("读取 notes.json 失败", e);
        }
    }

    private void save() {
        try {
            M.writerWithDefaultPrettyPrinter().writeValue(regFile().toFile(), notes);
        } catch (IOException e) {
            throw new IllegalStateException("写入 notes.json 失败", e);
        }
    }

    public synchronized List<Note> list() {
        List<Note> out = new ArrayList<>(notes);
        out.sort((a, b) -> b.updatedAt().compareTo(a.updatedAt()));
        return out;
    }

    public synchronized Note byId(String id) {
        return notes.stream().filter((n) -> n.id().equals(id)).findFirst().orElse(null);
    }

    private Path noteFile(Note n) { return notesDir().resolve(n.id() + "." + n.type()); }

    public synchronized String content(String id) {
        Note n = byId(id);
        if (n == null) return null;
        Path f = noteFile(n);
        if (!Files.exists(f)) return "";
        try {
            if ("docx".equals(n.type())) {
                try (var in = Files.newInputStream(f); var doc = new XWPFDocument(in)) {
                    StringBuilder sb = new StringBuilder();
                    for (XWPFParagraph p : doc.getParagraphs()) sb.append(p.getText()).append('\n');
                    return sb.toString();
                }
            }
            return Files.readString(f, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("读取笔记失败: " + e.getMessage(), e);
        }
    }

    public synchronized Note create(String title, String type, String content) {
        String t = "doc".equals(type) ? "docx" : type;
        if (!List.of("md", "txt", "docx").contains(t)) throw new IllegalArgumentException("不支持的笔记类型: " + type);
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String now = java.time.LocalDateTime.now().withNano(0).toString();
        String base = title == null || title.isBlank() ? "未命名笔记" : title.trim();
        Note n = new Note(id, uniqueTitle(base, null), t, now, now);
        writeFile(n, content == null ? "" : content);
        notes.add(n);
        save();
        return n;
    }

    /**
     * 只改文件名（标题），不动正文与类型。文件名唯一，重名直接报错让用户改。
     */
    public synchronized Note rename(String id, String title) {
        Note n = byId(id);
        if (n == null) throw new IllegalArgumentException("笔记不存在");
        String t = title == null || title.isBlank() ? n.title() : title.trim();
        if (t.equals(n.title())) return n;
        if (notes.stream().anyMatch((x) -> !x.id().equals(id) && x.title().equals(t))) {
            throw new IllegalArgumentException("已存在同名笔记「" + t + "」");
        }
        Note nn = new Note(n.id(), t, n.type(), n.createdAt(), java.time.LocalDateTime.now().withNano(0).toString());
        notes.removeIf((x) -> x.id().equals(id));
        notes.add(nn);
        save();
        return nn;
    }

    /** 同名时自动加序号（(2)、(3)…），避免"未命名笔记"堆一屏。 */
    private String uniqueTitle(String base, String exceptId) {
        boolean taken = notes.stream().anyMatch((x) -> !x.id().equals(exceptId) && x.title().equals(base));
        if (!taken) return base;
        int i = 2;
        while (true) {
            String candidate = base + " (" + i + ")";
            boolean used = notes.stream().anyMatch((x) -> !x.id().equals(exceptId) && x.title().equals(candidate));
            if (!used) return candidate;
            i++;
        }
    }

    public synchronized void update(String id, String title, String content) {
        Note n = byId(id);
        if (n == null) throw new IllegalArgumentException("笔记不存在");
        Note nn = new Note(n.id(), title == null || title.isBlank() ? n.title() : title.trim(),
                n.type(), n.createdAt(), java.time.LocalDateTime.now().withNano(0).toString());
        notes.removeIf((x) -> x.id().equals(id));
        notes.add(nn);
        writeFile(nn, content == null ? "" : content);
        save();
    }

    public synchronized void remove(String id) {
        Note n = byId(id);
        if (n != null) {
            try { Files.deleteIfExists(noteFile(n)); } catch (IOException ignored) { }
            notes.removeIf((x) -> x.id().equals(id));
            save();
        }
    }

    private void writeFile(Note n, String content) {
        try {
            Path f = noteFile(n);
            if ("docx".equals(n.type())) {
                try (var doc = new XWPFDocument()) {
                    for (String line : content.split("\n", -1)) {
                        XWPFParagraph p = doc.createParagraph();
                        p.createRun().setText(line);
                    }
                    try (var out = Files.newOutputStream(f)) { doc.write(out); }
                }
            } else {
                Files.writeString(f, content, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new IllegalStateException("写入笔记文件失败", e);
        }
    }
}
