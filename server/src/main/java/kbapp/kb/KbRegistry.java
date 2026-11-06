package kbapp.kb;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import kbapp.config.AppPaths;
import kbapp.config.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 多知识库注册表（.kb/kbs.json）。
 * default 库的 sourcePath 跟随设置页 sourceRoot；新建库把源目录复制到 kbdata/{id}/。
 */
@Service
public class KbRegistry {

    private static final Logger log = LoggerFactory.getLogger(KbRegistry.class);
    private static final ObjectMapper M = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public record KbEntry(String id, String name, String sourcePath, String createdAt, boolean fixed) {}

    private final AppPaths paths;
    private final ConfigService config;
    private volatile List<KbEntry> entries = new ArrayList<>();

    public KbRegistry(AppPaths paths, ConfigService config) {
        this.paths = paths;
        this.config = config;
    }

    @PostConstruct
    public synchronized void init() {
        Path f = paths.dataDir().resolve("kbs.json");
        try {
            if (Files.exists(f)) {
                entries = new ArrayList<>(List.of(M.readValue(f.toFile(), KbEntry[].class)));
            }
        } catch (IOException e) {
            log.warn("读取 kbs.json 失败，使用默认", e);
        }
        migrateDefault();
        ensureNotesKb();
        save();
    }

    /** 内置「我的笔记」库：笔记正文落在 kbdata/notes/，随库进索引；fixed 不可删。 */
    private void ensureNotesKb() {
        boolean exists = entries.stream().anyMatch((e) -> e.id().equals(kbapp.notes.NotesStore.NOTES_KB_ID));
        if (exists) return;
        entries.add(new KbEntry(kbapp.notes.NotesStore.NOTES_KB_ID, "我的笔记", "",
                java.time.LocalDateTime.now().withNano(0).toString(), true));
        log.info("已注册内置知识库「我的笔记」（笔记正文自动进入检索）");
    }

    /**
     * 一次性迁移：把旧的"默认知识库"（fixed，直读 sourceRoot）转为常规副本知识库。
     * id 保持 "default"（会话/智能体绑定不断），源文档复制到 kbdata/default/，
     * 旧 lucene 索引目录直接挪到 indexes/default（向量保留，无需重嵌）。
     */
    private void migrateDefault() {
        // 迁移只做一次：做过就留标记，避免用户删掉「默认知识库」后每次重启又被复活
        Path marker = paths.dataDir().resolve(".default-migrated");
        if (Files.exists(marker)) return;
        KbEntry def = entries.stream().filter((e) -> e.id().equals("default")).findFirst().orElse(null);
        if (def != null && !def.fixed()) {
            markMigrated(marker);
            return; // 已经是副本型，无需迁移
        }
        String sourceRoot = config.get().getSourceRoot();
        if (sourceRoot == null || sourceRoot.isBlank()) {
            if (def == null) entries.add(0, new KbEntry("default", "默认知识库", "", "2026-10-28T00:00:00", false));
            markMigrated(marker);
            return;
        }
        try {
            Path dest = paths.dataDir().resolve("kbdata").resolve("default");
            if (!Files.exists(dest)) {
                copySource(sourcePathOrDefault(sourceRoot), "default");
            }
            // 旧索引目录平移（向量保留）
            Path oldIdx = paths.dataDir().resolve("lucene");
            Path newIdx = paths.dataDir().resolve("indexes").resolve("default");
            if (Files.exists(oldIdx) && !Files.exists(newIdx)) {
                Files.createDirectories(newIdx.getParent());
                java.nio.file.Files.move(oldIdx, newIdx);
            }
            if (def == null) {
                entries.add(0, new KbEntry("default", "默认知识库", sourceRoot, "2026-10-28T00:00:00", false));
            } else {
                entries.remove(def);
                entries.add(0, new KbEntry("default", def.name(), sourceRoot, def.createdAt(), false));
            }
            log.info("默认知识库已迁移为副本型（kbdata/default）");
            markMigrated(marker);
        } catch (Exception e) {
            log.warn("默认知识库迁移失败，保留原样", e);
            if (def == null) entries.add(0, new KbEntry("default", "默认知识库", sourceRoot, "2026-10-28T00:00:00", false));
        }
    }

    private void markMigrated(Path marker) {
        try {
            Files.writeString(marker, java.time.LocalDateTime.now().toString());
        } catch (IOException e) {
            log.warn("写入迁移标记失败: {}", e.toString());
        }
    }

    private String sourcePathOrDefault(String p) { return p; }

    private void save() {
        try {
            Files.createDirectories(paths.dataDir());
            M.writerWithDefaultPrettyPrinter()
                    .writeValue(paths.dataDir().resolve("kbs.json").toFile(), entries);
        } catch (IOException e) {
            throw new IllegalStateException("写入 kbs.json 失败", e);
        }
    }

    public synchronized List<KbEntry> list() { return entries; }

    public synchronized KbEntry byId(String kbId) {
        String id = kbId == null || kbId.isBlank() ? "default" : kbId;
        return entries.stream().filter((k) -> k.id().equals(id)).findFirst().orElse(null);
    }

    /**
     * 统一的「解析知识库」入口：id 或名称都认；空则用第一个库；找不到返回 null。
     * 供 MCP 工具与开放接口共用，避免各处自己拼兜底逻辑。
     */
    public synchronized KbEntry resolve(String kbIdOrName) {
        if (kbIdOrName == null || kbIdOrName.isBlank()) return entries.isEmpty() ? null : entries.get(0);
        String want = kbIdOrName.trim();
        for (KbEntry k : entries) if (k.id().equals(want)) return k;
        for (KbEntry k : entries) if (k.name().equalsIgnoreCase(want) || k.name().contains(want)) return k;
        return null;
    }

    public synchronized KbEntry create(String name, String sourcePath) {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        KbEntry e = new KbEntry(id, name, sourcePath,
                java.time.LocalDateTime.now().withNano(0).toString(), false);
        entries.add(e);
        save();
        return e;
    }

    public synchronized void remove(String id) {
        entries.removeIf((e) -> e.id().equals(id) && !e.fixed());
        save();
        // 磁盘目录不能静默残留：残留既占空间，又会被备份一并带走，
        // 恢复后表现为「已删的知识库自己复活」。这里改为软删除移到 .trash/，
        // 需要时可手工找回；确认无用后自行清理该目录。
        try {
            Path dir = paths.dataDir().resolve("kbdata").resolve(id);
            if (Files.isDirectory(dir)) {
                Path trash = paths.dataDir().resolve(".trash").resolve("kbdata");
                Files.createDirectories(trash);
                String ts = java.time.LocalDateTime.now().withNano(0)
                        .toString().replace(":", "").replace("-", "");
                Files.move(dir, trash.resolve(id + "-" + ts),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                log.info("知识库 {} 的磁盘目录已移入回收站: {}", id, trash);
            }
        } catch (Exception e) {
            log.warn("移动知识库目录到回收站失败 {}: {}", id, e.toString());
        }
    }

    /** 知识库根目录：全部使用复制到工作路径的独立副本。 */
    public Path docsRoot(KbEntry e) {
        return paths.dataDir().resolve("kbdata").resolve(e.id()).normalize();
    }

    public Path indexDir(KbEntry e) {
        return paths.dataDir().resolve("indexes").resolve(e.id());
    }

    /** 把源目录复制为知识库私有副本（遵循扫描过滤规则，保留相对结构）。 */
    public Path copySource(String sourcePath, String kbId) throws IOException {
        Path src = Path.of(sourcePath).toAbsolutePath().normalize();
        if (!Files.isDirectory(src)) throw new IOException("源目录不存在: " + src);
        Path dest = paths.dataDir().resolve("kbdata").resolve(kbId);
        Files.createDirectories(dest);
        copyTree(src, dest, src);
        return dest;
    }

    private void copyTree(Path src, Path dest, Path root) throws IOException {
        Files.createDirectories(dest);
        try (var stream = Files.walk(src)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                Path rel = root.relativize(p);
                boolean skip = false;
                for (int i = 0; i < rel.getNameCount(); i++) {
                    String part = rel.getName(i).toString();
                    if (kbapp.ingest.ScanFilter.isExcludedDir(part)
                            || kbapp.ingest.ScanFilter.isExcludedFile(part)) {
                        skip = true;
                        break;
                    }
                }
                if (skip) continue;
                if (Files.isDirectory(p)) {
                    Files.createDirectories(dest.resolve(rel));
                } else {
                    Files.copy(p, dest.resolve(rel), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
