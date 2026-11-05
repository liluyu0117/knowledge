package kbapp.notes;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import kbapp.config.AppPaths;
import kbapp.ingest.IndexBuilder;
import kbapp.kb.KbRegistry;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 笔记存储（方案 A2）：正文落盘到知识库工作目录 .kb/kbdata/notes/，
 * 文件名即标题（检索结果直接可读）；注册表 notes.json 增加 kbId（固定 "notes"）。
 * 笔记因此天然进入「我的笔记」内置知识库的索引，问答 / 检索 / MCP 可直接命中。
 * 保存后 30 秒防抖触发该库的增量构建（向量按内容 hash 复用，不重复计费）。
 */
@Service
public class NotesStore {

    private static final Logger log = LoggerFactory.getLogger(NotesStore.class);
    private static final ObjectMapper M = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    /** 笔记所在内置知识库 id（KbRegistry 启动时确保存在，fixed 不可删）。 */
    public static final String NOTES_KB_ID = "notes";

    public record Note(String id, String title, String type, String createdAt, String updatedAt,
                       String kbId, String folder) {
        public String folderOrRoot() { return folder == null ? "" : folder.trim(); }
    }

    private final AppPaths paths;
    private final KbRegistry registry;
    private final IndexBuilder builder;
    private volatile List<Note> notes = new ArrayList<>();

    /** 30 秒防抖的单库增量构建（连续保存只触发一次）。 */
    private final ScheduledExecutorService debouncer =
            Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "notes-rebuild"); t.setDaemon(true); return t; });
    private final AtomicReference<ScheduledFuture<?>> pending = new AtomicReference<>();

    public NotesStore(AppPaths paths, KbRegistry registry, IndexBuilder builder) {
        this.paths = paths;
        this.registry = registry;
        this.builder = builder;
    }

    private Path regFile() { return paths.dataDir().resolve("notes.json"); }
    private Path notesDir() { return paths.dataDir().resolve("kbdata").resolve(NOTES_KB_ID); }

    @PostConstruct
    public synchronized void init() {
        try {
            Files.createDirectories(notesDir());
            Path f = regFile();
            if (Files.exists(f)) {
                List<Note> loaded = new ArrayList<>(List.of(M.readValue(f.toFile(), Note[].class)));
                // 旧注册表无 kbId 字段：补默认值，向后兼容
                notes = new ArrayList<>(loaded.stream().map((n) -> normalize(n)).toList());
            }
            migrateLegacy();
            repairPaths();
        } catch (IOException e) {
            log.warn("读取 notes.json 失败", e);
        }
    }

    /**
     * 路径自愈：注册表的 folder 与磁盘实际位置不一致时（例如文件被拖进子目录
     * 但归属没落库、或旧版本遗留），把 folder 纠正为文件真实所在目录。
     * 否则应用会去错误路径读文件、读到空内容 —— 表面现象就是「笔记全空了」。
     */
    private void repairPaths() {
        boolean changed = false;
        try {
            for (int i = 0; i < notes.size(); i++) {
                Note n = notes.get(i);
                Path expect = noteFile(n);
                if (Files.isRegularFile(expect)) continue;      // 位置正确
                String fileName = sanitizeFileName(n.title()) + "." + n.type();
                Path found = null;
                try (var st = Files.walk(notesDir())) {
                    found = st.filter(Files::isRegularFile)
                            .filter((p) -> p.getFileName().toString().equals(fileName))
                            .findFirst().orElse(null);
                } catch (IOException ignored) { }
                if (found == null) continue;                    // 真找不到：保持原样，不猜
                Path parent = found.getParent();
                String rel = parent.equals(notesDir()) ? "" : notesDir().relativize(parent).toString().replace('\\', '/');
                if (rel.equals(n.folderOrRoot())) continue;
                notes.set(i, new Note(n.id(), n.title(), n.type(), n.createdAt(), n.updatedAt(), n.kbId(), rel));
                changed = true;
                log.warn("笔记「{}」归属已纠正：{} → 「{}」（按磁盘实际位置）", n.title(), n.folderOrRoot(), rel);
            }
            if (changed) save();
        } catch (Exception e) {
            log.warn("笔记路径自愈失败（不影响启动）: {}", e.toString());
        }
    }

    /**
     * 一次性迁移：旧版笔记散落在 .kb/notes/{id}.{type}，按注册表搬迁到
     * kbdata/notes/{标题}.{type}；完成后旧目录整体留档为 .kb/notes.bak。
     * 幂等：旧目录不存在则直接跳过；失败时保留原目录不动，下次启动重试。
     */
    private void migrateLegacy() {
        Path oldDir = paths.dataDir().resolve("notes");
        if (!Files.isDirectory(oldDir)) return;
        int moved = 0;
        try {
            for (Note n : notes) {
                Path old = oldDir.resolve(n.id() + "." + n.type());
                if (!Files.exists(old)) continue;
                writeFile(n, readLegacy(old, n.type()));
                moved++;
            }
            Path bak = paths.dataDir().resolve("notes.bak");
            if (Files.exists(bak)) deleteTree(bak);
            Files.move(oldDir, bak);
            if (moved > 0) save();
            if (moved > 0) log.info("笔记迁移完成：{} 篇进入知识库 kbdata/notes（旧目录留档 notes.bak）", moved);
        } catch (Exception e) {
            log.warn("笔记迁移失败，保留原目录 .kb/notes 不动，下次启动重试", e);
        }
    }

    /** 旧注册表缺 kbId/folder：补默认值，向后兼容。 */
    private Note normalize(Note n) {
        String kbId = n.kbId() == null || n.kbId().isBlank() ? NOTES_KB_ID : n.kbId();
        String folder = n.folder() == null ? "" : sanitizeFolder(n.folder());
        return new Note(n.id(), n.title(), n.type(), n.createdAt(), n.updatedAt(), kbId, folder);
    }

    /** 文件夹名清洗：允许中文/字母/数字与 / 分隔多级，禁止 .. 与非法字符。 */
    private static String sanitizeFolder(String f) {
        String s = f == null ? "" : f.replace('\\', '/').trim();
        while (s.startsWith("/")) s = s.substring(1);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        for (String part : s.split("/")) {
            if (part.isBlank() || part.equals(".") || part.equals("..")
                    || part.matches(".*[\\:*?\"<>|].*")) return "";
        }
        return s;
    }

    private String readLegacy(Path f, String type) throws IOException {
        if ("docx".equals(type)) {
            try (var in = Files.newInputStream(f); var doc = new XWPFDocument(in)) {
                StringBuilder sb = new StringBuilder();
                for (XWPFParagraph p : doc.getParagraphs()) sb.append(p.getText()).append('\n');
                return sb.toString();
            }
        }
        return Files.readString(f, StandardCharsets.UTF_8);
    }

    private void deleteTree(Path dir) throws IOException {
        try (var s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException ignored) { }
            });
        }
    }

    private void save() {
        try {
            M.writerWithDefaultPrettyPrinter().writeValue(regFile().toFile(), notes);
        } catch (IOException e) {
            throw new IllegalStateException("写入 notes.json 失败", e);
        }
    }

    /** 笔记变更后 30 秒防抖触发「我的笔记」库的增量索引。 */
    private void scheduleRebuild() {
        ScheduledFuture<?> prev = pending.get();
        if (prev != null) prev.cancel(false);
        pending.set(debouncer.schedule(() -> {
            try {
                if (registry.byId(NOTES_KB_ID) != null) builder.start(NOTES_KB_ID);
            } catch (Exception e) {
                log.warn("笔记库增量构建触发失败（自动重扫会兜底）: {}", e.toString());
            }
        }, 30, TimeUnit.SECONDS));
    }

    @PreDestroy
    public void shutdown() {
        debouncer.shutdownNow();
    }

    public synchronized List<Note> list() {
        List<Note> out = new ArrayList<>(notes);
        out.sort((a, b) -> b.updatedAt().compareTo(a.updatedAt()));
        return out;
    }

    public synchronized Note byId(String id) {
        return notes.stream().filter((n) -> n.id().equals(id)).findFirst().orElse(null);
    }

    /** 文件名 = sanitize 后的标题；与标题一致，检索结果标题直接可读。 */
    private Path noteDir(Note n) {
        String folder = sanitizeFolder(n.folderOrRoot());
        return folder.isEmpty() ? notesDir() : notesDir().resolve(folder);
    }

    private Path noteFile(Note n) {
        String base = sanitizeFileName(n.title());
        Path f = noteDir(n).resolve(base + "." + n.type());
        // 不同标题 sanitize 后撞名（如 a/b 与 a:b）：追加序号保证各归各位
        if (Files.exists(f)) {
            int i = 2;
            while (Files.exists(notesDir().resolve(base + " (" + i + ")." + n.type()))) i++;
            f = notesDir().resolve(base + " (" + i + ")." + n.type());
        }
        return f;
    }

    private static String sanitizeFileName(String title) {
        String s = title == null ? "" : title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (s.isEmpty()) s = "未命名笔记";
        return s.length() > 60 ? s.substring(0, 60) : s;
    }

    public synchronized String content(String id) {
        Note n = byId(id);
        if (n == null) return null;
        Path f = fileOf(n);
        // 自愈：主文件缺失或被清空，但历史快照有内容 → 从快照恢复，避免用户看到「空笔记」
        try {
            boolean empty = !Files.exists(f) || Files.size(f) == 0;
            Path snap = nonEmptySnapshot(n);
            if (empty && Files.exists(snap) && Files.size(snap) > 0) {
                Files.createDirectories(f.getParent());
                Files.copy(snap, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                f = fileOf(n);
                log.warn("笔记「{}」正文缺失/为空，已从历史快照恢复（{} 字节）", n.title(), Files.size(f));
            }
        } catch (IOException ignored) { }
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

    /** 笔记当前实际文件（按标题推导；找不到时按旧命名兜底，兼容异常情况）。 */
    private Path fileOf(Note n) {
        Path f = noteDir(n).resolve(sanitizeFileName(n.title()) + "." + n.type());
        if (Files.exists(f)) return f;
        // 兜底：根目录同名（归属调整中/迁移中）
        Path legacy = notesDir().resolve(sanitizeFileName(n.title()) + "." + n.type());
        if (Files.exists(legacy)) return legacy;
        Path oldId = notesDir().resolve(n.id() + "." + n.type());
        return Files.exists(oldId) ? oldId : f;
    }

    public synchronized Note create(String title, String type, String content, String folder) {
        String t = "doc".equals(type) ? "docx" : type;
        if (!List.of("md", "txt", "docx").contains(t)) throw new IllegalArgumentException("不支持的笔记类型: " + type);
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String now = java.time.LocalDateTime.now().withNano(0).toString();
        String base = title == null || title.isBlank() ? "未命名笔记" : title.trim();
        String f = sanitizeFolder(folder);
        Note n = new Note(id, uniqueTitle(base, null), t, now, now, NOTES_KB_ID, f);
        writeFile(n, content == null ? "" : content);
        notes.add(n);
        save();
        scheduleRebuild();
        return n;
    }

    /** 移动笔记到另一个文件夹（文件夹不存在自动创建）；返回更新后的笔记。 */
    public synchronized Note moveTo(String id, String folder) {
        Note n = byId(id);
        if (n == null) throw new IllegalArgumentException("笔记不存在");
        String f = sanitizeFolder(folder);
        if (f.equals(n.folderOrRoot())) return n;
        Path from = fileOf(n);
        Note nn = new Note(n.id(), n.title(), n.type(), n.createdAt(),
                java.time.LocalDateTime.now().withNano(0).toString(), n.kbId(), f);
        try {
            Files.createDirectories(noteDir(nn));
            Files.move(from, noteFile(nn));
            // 原目录空了就清掉，避免留空壳
            Path oldParent = from.getParent();
            if (!oldParent.equals(notesDir())) {
                try (var st = Files.list(oldParent)) {
                    if (st.findAny().isEmpty()) deleteTree(oldParent);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("移动笔记文件失败: " + e.getMessage(), e);
        }
        notes.removeIf((x) -> x.id().equals(id));
        notes.add(nn);
        save();
        scheduleRebuild();
        return nn;
    }

    /** 现有文件夹列表（注册表 folder 字段 ∪ 物理一级子目录），根目录在前。 */
    public synchronized List<String> folders() {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (Note n : notes) {
            String f = n.folderOrRoot();
            if (!f.isEmpty()) out.add(f);
        }
        try (var st = Files.list(notesDir())) {
            st.filter(Files::isDirectory).forEach((d) -> {
                String rel = notesDir().relativize(d).toString().replace('\\', '/');
                if (!rel.isBlank()) out.add(rel);
            });
        } catch (IOException ignored) { }
        List<String> list = new ArrayList<>(out);
        list.sort(String::compareTo);
        return list;
    }

    /** 新建文件夹（物理目录，可多级）。已存在时静默成功。 */
    public synchronized void createFolder(String folder) {
        String f = sanitizeFolder(folder);
        if (f.isEmpty()) throw new IllegalArgumentException("文件夹名不合法");
        try {
            Files.createDirectories(notesDir().resolve(f));
        } catch (IOException e) {
            throw new IllegalStateException("创建文件夹失败: " + e.getMessage(), e);
        }
    }

    /**
     * 重命名文件夹（含子路径前缀一起改）：移动物理目录 + 更新相关笔记的 folder。
     * 目标已存在时拒绝，避免合并出意外。
     */
    public synchronized void renameFolder(String from, String toName) {
        String a = sanitizeFolder(from);
        String b = sanitizeFolder(toName);
        if (a.isEmpty() || b.isEmpty()) throw new IllegalArgumentException("文件夹名不合法");
        if (a.equals(b)) return;
        Path src = notesDir().resolve(a);
        Path dst = notesDir().resolve(b);
        if (!Files.isDirectory(src)) throw new IllegalArgumentException("文件夹不存在");
        if (Files.exists(dst)) throw new IllegalArgumentException("已存在同名文件夹「" + b + "」");
        try {
            Files.createDirectories(dst.getParent());
            Files.move(src, dst);
        } catch (IOException e) {
            throw new IllegalStateException("重命名失败: " + e.getMessage(), e);
        }
        List<Note> updated = new ArrayList<>();
        for (Note n : notes) {
            String f = n.folderOrRoot();
            if (f.equals(a)) {
                updated.add(new Note(n.id(), n.title(), n.type(), n.createdAt(), n.updatedAt(), n.kbId(), b));
            } else if (f.startsWith(a + "/")) {
                updated.add(new Note(n.id(), n.title(), n.type(), n.createdAt(), n.updatedAt(), n.kbId(),
                        b + f.substring(a.length())));
            } else {
                updated.add(n);
            }
        }
        notes = updated;
        save();
        scheduleRebuild();
    }

    /**
     * 删除文件夹：其中的笔记（含子目录）一律移到根目录，不删内容；
     * 物理目录整棵删除。返回被移动的笔记数。
     */
    public synchronized int deleteFolder(String folder) {
        String f = sanitizeFolder(folder);
        if (f.isEmpty()) throw new IllegalArgumentException("根目录不可删除");
        Path dir = notesDir().resolve(f);
        int moved = 0;
        List<Note> updated = new ArrayList<>();
        for (Note n : notes) {
            String nf = n.folderOrRoot();
            if (nf.equals(f) || nf.startsWith(f + "/")) {
                Note toRoot = new Note(n.id(), n.title(), n.type(), n.createdAt(),
                        java.time.LocalDateTime.now().withNano(0).toString(), n.kbId(), "");
                Path from = fileOf(n);
                try {
                    Files.createDirectories(notesDir());
                    Path target = noteFile(toRoot);
                    if (Files.exists(from)) Files.move(from, target);
                } catch (IOException e) {
                    throw new IllegalStateException("移动笔记到根目录失败: " + e.getMessage(), e);
                }
                updated.add(toRoot);
                moved++;
            } else {
                updated.add(n);
            }
        }
        notes = updated;
        if (Files.isDirectory(dir)) {
            try {
                deleteTree(dir);
            } catch (IOException e) {
                throw new IllegalStateException("删除文件夹失败: " + e.getMessage(), e);
            }
        }
        save();
        scheduleRebuild();
        return moved;
    }

    /**
     * 只改文件名（标题），不动正文与类型。文件名唯一，重名直接报错让用户改。
     * 标题变了要同步移动物理文件（索引在下次构建时自动跟随）。
     */
    public synchronized Note rename(String id, String title) {
        Note n = byId(id);
        if (n == null) throw new IllegalArgumentException("笔记不存在");
        String t = title == null || title.isBlank() ? n.title() : title.trim();
        if (t.equals(n.title())) return n;
        if (notes.stream().anyMatch((x) -> !x.id().equals(id) && x.title().equals(t))) {
            throw new IllegalArgumentException("已存在同名笔记「" + t + "」");
        }
        Path oldFile = fileOf(n);
        Note nn = new Note(n.id(), t, n.type(), n.createdAt(), java.time.LocalDateTime.now().withNano(0).toString(), n.kbId(), n.folderOrRoot());
        try {
            Files.move(oldFile, noteFile(nn));
        } catch (IOException e) {
            throw new IllegalStateException("移动笔记文件失败: " + e.getMessage(), e);
        }
        notes.removeIf((x) -> x.id().equals(id));
        notes.add(nn);
        save();
        scheduleRebuild();
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
        String t = title == null || title.isBlank() ? n.title() : title.trim();
        boolean renamed = !t.equals(n.title());
        if (renamed && notes.stream().anyMatch((x) -> !x.id().equals(id) && x.title().equals(t))) {
            throw new IllegalArgumentException("已存在同名笔记「" + t + "」");
        }
        // ── 数据保护：禁止用空内容覆盖已有的非空笔记 ──
        // 前端在内容未加载完成（或路径不同步读不到文件）时触发自动保存，曾导致笔记被清空。
        // 判定依据：① 当前文件非空  ② 历史快照非空（文件已被清空但曾有过内容，同样拦截）
        String newContent = content == null ? "" : content;
        if (newContent.isBlank()) {
            Path cur = fileOf(n);
            long curSize = 0;
            try { if (Files.exists(cur)) curSize = Files.size(cur); } catch (IOException ignored) { }
            Path snap = nonEmptySnapshot(n);
            long snapSize = 0;
            try { if (Files.exists(snap)) snapSize = Files.size(snap); } catch (IOException ignored) { }
            if (curSize > 0 || snapSize > 0) {
                long known = Math.max(curSize, snapSize);
                throw new IllegalArgumentException(
                        "内容为空，已阻止覆盖（该笔记原有 " + known + " 字节内容）。请刷新页面确认内容已加载后再编辑；"
                                + "确要清空请先输入一个空格再保存");
            }
        }
        Path oldFile = fileOf(n);
        Note nn = new Note(n.id(), t, n.type(), n.createdAt(), java.time.LocalDateTime.now().withNano(0).toString(), n.kbId(), n.folderOrRoot());
        notes.removeIf((x) -> x.id().equals(id));
        notes.add(nn);
        if (renamed) {
            try {
                Files.move(oldFile, noteFile(nn));
            } catch (IOException e) {
                throw new IllegalStateException("移动笔记文件失败: " + e.getMessage(), e);
            }
        }
        writeFile(nn, newContent);
        save();
        scheduleRebuild();
    }

    public synchronized void remove(String id) {
        Note n = byId(id);
        if (n != null) {
            try { Files.deleteIfExists(fileOf(n)); } catch (IOException ignored) { }
            notes.removeIf((x) -> x.id().equals(id));
            save();
            scheduleRebuild();
        }
    }

    /** 历史快照：记录该笔记「最近一次非空内容」，用于判断空覆盖是否安全。 */
    private Path nonEmptySnapshot(Note n) {
        return paths.dataDir().resolve("notes-history").resolve(n.id()).resolve(".nonempty." + n.type());
    }

    /** 覆盖前把旧内容留一份历史版本（每篇保留最近 20 份），防止误覆盖丢数据。 */
    private void backupOld(Note n, Path target) {
        try {
            if (!Files.exists(target) || Files.size(target) == 0) return;
            Path hist = paths.dataDir().resolve("notes-history").resolve(n.id());
            Files.createDirectories(hist);
            // 最近一次非空内容（供空覆盖拦截判定，不参与 20 份轮转）
            Files.copy(target, nonEmptySnapshot(n), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            String stamp = java.time.LocalDateTime.now().toString().replace(":", "-").replace(".", "-");
            Files.copy(target, hist.resolve(stamp + "." + n.type()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            // 只保留最近 20 份
            try (var st = Files.list(hist)) {
                List<Path> olds = st.filter((p) -> !p.getFileName().toString().startsWith(".nonempty"))
                        .sorted(java.util.Comparator.comparingLong((Path p) -> p.toFile().lastModified()))
                        .toList();
                for (int i = 0; i < olds.size() - 20; i++) {
                    try { Files.delete(olds.get(i)); } catch (IOException ignored) { }
                }
            }
        } catch (Exception e) {
            log.warn("笔记历史备份失败（不影响保存）: {}", e.toString());
        }
    }

    private void writeFile(Note n, String content) {
        try {
            Files.createDirectories(noteDir(n));
            // 同名文件先删（重命名场景下 move 之后旧名已不在，这里覆盖正文即可）
            Path f = noteDir(n).resolve(sanitizeFileName(n.title()) + "." + n.type());
            backupOld(n, f);   // 覆盖前留历史版本
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
            // 记下最近一次非空内容（新建笔记也要记，否则后续空写入无从判断）
            if (!content.isBlank()) {
                try {
                    Files.createDirectories(nonEmptySnapshot(n).getParent());
                    Files.copy(f, nonEmptySnapshot(n), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) { }
            }
        } catch (IOException e) {
            throw new IllegalStateException("写入笔记文件失败", e);
        }
    }
}
