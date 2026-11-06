package kbapp.sync;

import kbapp.config.AppConfig;
import kbapp.config.ConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 坚果云备份/恢复/多端同步 —— 增量 + 分片：
 *   objects/{sha256}[.pN]   内容对象（小文件单对象，大文件 8MB 分片，去重 + 断点续传）
 *   manifests/m-{ts}.json   清单快照（path → hash/size/parts），保留最近 KEEP 份
 * 每个请求 ≤8MB，规避坚果云对大请求/高频请求的 403 限制。
 * 不含 lucene/ 索引（每机本地重建）与 API Key（不出本机）。
 */
@Service
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);
    private static final String REMOTE_DIR = "kb-backup";
    private static final String MANIFEST_DIR = "kb-backup/manifests";
    private static final String OBJECT_DIR = "kb-backup/objects";
    private static final int KEEP = 10;
    private static final int PART_SIZE = 8 * 1024 * 1024; // 8MB 分片，坚果云 403 阈值内
    private static final long OBJECT_TIMEOUT_MIN = 10;    // 单对象请求超时（分钟）

    private final ConfigService config;
    private final WebDavClient dav;
    private final kbapp.chat.ChatStore chatStore;
    private static final ObjectMapper M = new ObjectMapper();

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sync-worker");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);
    /** 清单元数据缓存：name -> {文件数, 字节数}。清单一旦生成内容不再变化，可长期缓存。 */
    private final Map<String, long[]> manifestMeta = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicReference<Progress> progress =
            new AtomicReference<>(Progress.idle());

    public record Progress(boolean running, String phase, int percent, String error, Long sizeKB,
                           String backupName, String detail, Long startedAt) {
        static Progress idle() { return new Progress(false, "空闲", 0, null, null, null, null, null); }
    }

    /**
     * @param size  备份内容总字节数（zip 旧格式为 0）
     * @param files 清单内文件数（-1 表示不适用）
     * @param auto  true = 恢复前的自动兜底快照（auto- 前缀），不是用户主动做的备份
     */
    public record BackupInfo(String name, long size, long lastModified, int files, boolean auto) {}

    public record SyncCfg(String server, String user, String appPassword, boolean autoDaily) {}

    private record SyncCfgFull(String server, String user, String pass) {}

    private record FileMeta(String rel, String sha256, long size, int parts) {}

    public SyncService(ConfigService config, WebDavClient dav, kbapp.chat.ChatStore chatStore) {
        this.config = config;
        this.dav = dav;
        this.chatStore = chatStore;
    }

    public Progress progress() { return progress.get(); }

    private SyncCfgFull cfg() {
        AppConfig.Sync s = config.get().getSync();
        if (s == null || s.getServer() == null || s.getServer().isBlank()
                || s.getUser() == null || s.getUser().isBlank()
                || s.getAppPassword() == null || s.getAppPassword().isBlank()) {
            throw new IllegalStateException("未配置坚果云 WebDAV（服务器/账号/应用密码）");
        }
        return new SyncCfgFull(s.getServer(), s.getUser(), s.getAppPassword());
    }

    public void test() {
        SyncCfgFull c = cfg();
        try {
            dav.mkcol(c.server(), c.user(), c.pass(), REMOTE_DIR);
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
        }
    }

    public boolean startBackup() {
        if (!running.compareAndSet(false, true)) return false;
        startedAt = System.currentTimeMillis();
        // 先同步置为「运行中」再提交任务：否则前端首次轮询可能拿到旧的「空闲」状态，
        // 刚弹出的进度条会被立刻掐掉。
        progress.set(new Progress(true, "准备备份", 1, null, null, null, null, startedAt));
        worker.submit(() -> {
            try {
                runBackup();
            } catch (Throwable e) {
                log.error("备份失败", e);
                progress.set(new Progress(false, "备份失败", 100,
                        e.getMessage() == null ? e.toString() : e.getMessage(), null, null, null, startedAt));
            } finally {
                // 必须复位：否则失败一次后，后续所有备份/恢复都会因标志未复位而直接返回 false
                running.set(false);
            }
        });
        return true;
    }

    public boolean startRestore(String cloudName) {
        if (!running.compareAndSet(false, true)) return false;
        startedAt = System.currentTimeMillis();
        progress.set(new Progress(true, "准备恢复", 1, null, null, null, null, startedAt));
        worker.submit(() -> {
            try {
                if (cloudName.endsWith(".json")) runIncrementRestore(cloudName);
                else runLegacyZipRestore(cloudName);
            } catch (Throwable e) {
                log.error("恢复失败", e);
                progress.set(new Progress(false, "恢复失败", 100,
                        e.getMessage() == null ? e.toString() : e.getMessage(), null, null, null, startedAt));
            } finally {
                running.set(false);
            }
        });
        return true;
    }

    /** 同步执行一次备份（恢复前的安全兜底也走这里）。 */
    public void backup() throws Exception {
        runBackup();
    }

    private volatile long startedAt;

    /**
     * 确保远端目录存在：MKCOL 之后用 PROPFIND 回读确认。
     * AList 等 WebDAV 网关在「刚建完父目录立刻建子目录」时会返回非 2xx，
     * 而 MKCOL 的响应码默认被吞掉 —— 不校验就会出现「目录不存在 → PUT 404」。
     */
    private void ensureDir(SyncCfgFull c, String path) throws Exception {
        for (int attempt = 1; attempt <= 5; attempt++) {
            if (dirExists(c, path)) return;
            try {
                dav.mkcol(c.server(), c.user(), c.pass(), path);
            } catch (Exception e) {
                log.debug("MKCOL {} 失败（第 {} 次）：{}", path, attempt, e.toString());
            }
            if (dirExists(c, path)) return;
            try { Thread.sleep(300L * attempt); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.warn("远端目录仍未就绪: {}", path);
    }

    private boolean dirExists(SyncCfgFull c, String path) {
        try {
            dav.propfind(c.server(), c.user(), c.pass(), path);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void setP(String phase, int percent) {
        setP(phase, percent, null);
    }

    private void setP(String phase, int percent, String detail) {
        if (startedAt == 0) startedAt = System.currentTimeMillis();
        progress.set(new Progress(true, phase, percent, null, null, null, detail, startedAt));
    }

    /** 同步内容文件清单：kbdata/ + 根部关键 json/db，app.json 脱敏。 */
    private List<Path> syncFiles() {
        Path data = config.dataDir();
        List<Path> files = new ArrayList<>();
        collect(data.resolve("kbdata"), files);
        for (String name : new String[]{"chat.db", "kbs.json", "agents.json", "notes.json",
                "eval.json", "build.json", "app.json"}) {
            Path f = data.resolve(name);
            if (Files.exists(f)) files.add(f);
        }
        return files;
    }


    private List<FileMeta> buildManifest(List<Path> files, Path root) throws Exception {
        List<FileMeta> out = new ArrayList<>();
        int total = Math.max(1, files.size());
        int idx = 0;
        for (Path f : files) {
            idx++;
            if (idx % 20 == 0 || idx == total) {
                setP("计算清单 " + idx + "/" + total, 1 + (int) (idx * 2L / total),
                        root.relativize(f).toString().replace(java.io.File.separatorChar, '/'));
            }
            byte[] bytes = Files.readAllBytes(f);
            String rel = root.relativize(f).toString().replace(java.io.File.separatorChar, '/');
            if (rel.equals("app.json")) {
                String json = new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
                        .replaceAll("\"apiKey\"\\s*:\\s*\"[^\"]*\"", "\"apiKey\": \"\"");
                bytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
            int parts = (int) Math.max(1, (bytes.length + PART_SIZE - 1) / (long) PART_SIZE);
            out.add(new FileMeta(rel, sha256(bytes), bytes.length, parts));
        }
        out.sort((a, b) -> a.rel().compareTo(b.rel()));
        return out;
    }

    private static String objName(String hash, int part, int parts) {
        return parts > 1 ? hash + ".p" + part : hash;
    }

    private Map<String, String> fetchOldManifest(SyncCfgFull c) {
        try {
            List<WebDavClient.Entry> manifests = dav.propfind(c.server(), c.user(), c.pass(), MANIFEST_DIR);
            WebDavClient.Entry latest = manifests.stream()
                    .filter((e) -> !e.directory() && e.name().endsWith(".json"))
                    .sorted((a, b) -> Long.compare(b.lastModified(), a.lastModified()))
                    .findFirst().orElse(null);
            if (latest == null) return Map.of();
            byte[] json = dav.get(c.server(), c.user(), c.pass(), MANIFEST_DIR + "/" + latest.name());
            Map<String, Object> parsed = M.readValue(new String(json, java.nio.charset.StandardCharsets.UTF_8), Map.class);
            Map<String, String> out = new HashMap<>();
            if (parsed.get("entries") instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> e) {
                        out.put(String.valueOf(e.get("rel")), String.valueOf(e.get("sha256")));
                    }
                }
            }
            return out;
        } catch (Exception e) {
            log.info("无旧清单，本次全量上传: {}", e.toString());
            return Map.of();
        }
    }

    private void runBackup() throws Exception {
        runBackup(false);
    }

    /**
     * @param autoSnapshot true = 恢复前的自动兜底快照。
     *   兜底快照用 auto- 前缀单独标记：数据往往与「用户真正想恢复的那份」无关（甚至是空的），
     *   不能混在正常备份里冒充「最新备份」，否则恢复永远只能选中它。
     */
    private void runBackup(boolean autoSnapshot) throws Exception {
        long t0 = System.currentTimeMillis();
        startedAt = t0;
        // 会话库是 WAL 模式，先合并回主库再收集文件，否则备份里的 chat.db 是空的
        chatStore.checkpoint();
        SyncCfgFull c = cfg();
        ensureDir(c, REMOTE_DIR);
        ensureDir(c, MANIFEST_DIR);
        ensureDir(c, OBJECT_DIR);

        List<Path> files = syncFiles();
        Path data = config.dataDir();
        setP("正在计算清单", 3);
        List<FileMeta> manifest = buildManifest(files, data);

        // 断点续传：远端已有对象集合（含分片名）
        Set<String> knownObjects = new HashSet<>();
        knownObjectsCache.clear();
        try {
            for (WebDavClient.Entry e : dav.propfind(c.server(), c.user(), c.pass(), OBJECT_DIR)) {
                if (!e.directory()) knownObjects.add(e.name());
            }
            // 关键：把远端已有对象灌进缓存。之前这里漏了，导致 isPartKnown 恒为 false →
            // 每个分片都被判定失败并重传 3 次（中间还 sleep 10s、20s），1.3G 数据要跑一个多小时。
            knownObjectsCache.addAll(knownObjects);
        } catch (Exception e) {
            log.warn("读取远端对象列表失败（将按全量上传处理）：{}", e.toString());
        }

        // 待上传分片（按文件内字节数估算流量）
        Set<String> failedHashes = new HashSet<>();
        List<FileMeta> changed = new ArrayList<>();
        long changedBytes = 0;
        for (FileMeta fm : manifest) {
            boolean allKnown = true;
            for (int pi = 0; pi < fm.parts() && allKnown; pi++) {
                if (!knownObjects.contains(objName(fm.sha256(), pi, fm.parts()))) allKnown = false;
            }
            if (allKnown) continue;
            changed.add(fm);
            changedBytes += fm.size();
        }

        setP("待上传 " + changed.size() + "/" + files.size() + " 个文件", 3);

        // 上传变化文件的分片（0-85%）
        ensureDir(c, OBJECT_DIR);
        int done = 0;
        long sent = 0;
        for (FileMeta fm : changed) {
            Path f = data.resolve(fm.rel());
            boolean uploaded = true;
            int parts = Math.max(1, fm.parts());
            try (var ch = java.nio.channels.FileChannel.open(f, java.nio.file.StandardOpenOption.READ)) {
                for (int pi = 0; pi < parts && uploaded; pi++) {
                    long st = (long) pi * PART_SIZE;
                    int len = (int) Math.min(PART_SIZE, ch.size() - st);
                    java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(len);
                    ch.position(st);
                    while (buf.hasRemaining() && ch.read(buf) > 0) { /* 读满一片 */ }
                    byte[] chunkData = buf.array();
                    String obj = objName(fm.sha256(), pi, parts);
                    if (knownObjectsCache.contains(obj)) {          // 断点续传：远端已有就跳过
                        sent += chunkData.length;
                        continue;
                    }
                    boolean ok = false;
                    for (int attempt = 1; attempt <= 3 && !ok; attempt++) {
                        try {
                            dav.put(c.server(), c.user(), c.pass(), OBJECT_DIR + "/" + obj, chunkData,
                                    Duration.ofMinutes(15));
                            knownObjectsCache.add(obj);              // 成功即入缓存，避免被误判失败
                            ok = true;
                        } catch (Exception e) {
                            log.warn("分片上传失败（第 {} 次）{}：{}", attempt,
                                    obj.substring(0, Math.min(12, obj.length())), e.toString());
                            if (attempt < 3) {
                                try { Thread.sleep(3000L * attempt); } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                        }
                    }
                    if (!ok) uploaded = false;
                    sent += chunkData.length;
                    int pct = 3 + (int) (sent * 82 / Math.max(1, changedBytes));
                    setP("上传 " + done + "/" + changed.size(), Math.min(85, pct),
                            fm.rel() + (parts > 1 ? "（分片 " + (pi + 1) + "/" + parts + "）" : ""));
                }
            }
            if (isPartKnown(fm.sha256(), parts)) done++;
            else failedHashes.add(fm.sha256());
            setP("上传 " + done + "/" + changed.size(), Math.min(85, 3 + (int) (sent * 82 / Math.max(1, changedBytes))),
                    fm.rel());
        }

        // 上传清单快照（85-100%）
        setP("正在上传清单", 90);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("createdAt", LocalDateTime.now().withNano(0).toString());
        List<Map<String, Object>> entryList = new ArrayList<>();
        for (FileMeta fm : manifest) {
            if (failedHashes.contains(fm.sha256())) continue; // 上传失败的对象不进清单（下次重试）
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("rel", fm.rel());
            e.put("sha256", fm.sha256());
            e.put("size", fm.size());
            e.put("parts", fm.parts());
            entryList.add(e);
        }
        snapshot.put("entries", entryList);
        // 文件名携带元数据（文件数 + MB），列备份时无需下载 600KB 清单即可展示，
        // 形如 m-20261105T113326-2486-1009mb.json
        long manifestBytes = 0;
        for (Map<String, Object> e : entryList) {
            if (e.get("size") instanceof Number n) manifestBytes += n.longValue();
        }
        String manifestName = (autoSnapshot ? "auto-" : "m-")
                + LocalDateTime.now().withNano(0).toString().replace(":", "").replace("-", "")
                + "-" + entryList.size() + "-" + (manifestBytes / 1048576) + "mb.json";
        dav.put(c.server(), c.user(), c.pass(), MANIFEST_DIR + "/" + manifestName,
                M.writerWithDefaultPrettyPrinter().writeValueAsBytes(snapshot), Duration.ofMinutes(10));

        pruneManifests(c);
        long totalBytes = 0;
        for (Map<String, Object> e : entryList) {
            Object sz = e.get("size");
            if (sz instanceof Number n) totalBytes += n.longValue();
        }
        progress.set(new Progress(false, "备份完成", 100, null, totalBytes / 1024, manifestName,
                "共 " + entryList.size() + " 个文件（本次上传 " + changed.size() + " 个），耗时 "
                        + (System.currentTimeMillis() - t0) / 1000 + "s", startedAt));
        log.info("增量备份完成: 变更 {} / 共 {} 文件，失败 {}，耗时 {}ms",
                changed.size(), manifest.size(), failedHashes.size(), System.currentTimeMillis() - t0);
    }

    private long manifestBytesGuard() { return 0; }

    private boolean isPartKnown(String objName) {
        // 由 runBackup 传入的 knownObjects 检查 —— 简化：重建时重新查询代价高，
        // 这里通过成功 PUT 后加入 knownObjects 保证幂等（见 startBackup 流程）
        return knownObjectsCache.contains(objName);
    }

    private boolean isPartKnown(String hash, int parts) {
        for (int pi = 0; pi < parts; pi++) {
            if (!knownObjectsCache.contains(objName(hash, pi, parts))) return false;
        }
        return true;
    }

    private final Set<String> knownObjectsCache = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private void pruneManifests(SyncCfgFull c) {
        try {
            List<WebDavClient.Entry> manifests = dav.propfind(c.server(), c.user(), c.pass(), MANIFEST_DIR);
            List<WebDavClient.Entry> snaps = manifests.stream()
                    .filter((e) -> !e.directory() && e.name().endsWith(".json"))
                    .sorted((a, b) -> Long.compare(b.lastModified(), a.lastModified()))
                    .toList();
            // 自动兜底快照（auto-）单独计数，只保留最近 3 个；
            // 否则每次恢复都产生一个快照，会把用户真正的备份挤出保留窗口。
            List<WebDavClient.Entry> autos = snaps.stream()
                    .filter((e) -> e.name().startsWith("auto-")).toList();
            for (int i = 3; i < autos.size(); i++) {
                try {
                    var req = java.net.http.HttpRequest.newBuilder(davUri(c.server(), MANIFEST_DIR + "/" + autos.get(i).name()))
                            .header("Authorization", basic(c.user(), c.pass()))
                            .method("DELETE", java.net.http.HttpRequest.BodyPublishers.noBody())
                            .build();
                    java.net.http.HttpClient.newHttpClient().send(req, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
                } catch (Exception ignored) { }
            }
            snaps = snaps.stream().filter((e) -> !e.name().startsWith("auto-")).toList();
            for (int i = KEEP; i < snaps.size(); i++) {
                try {
                    var req = java.net.http.HttpRequest.newBuilder(davUri(c.server(), MANIFEST_DIR + "/" + snaps.get(i).name()))
                            .header("Authorization", basic(c.user(), c.pass()))
                            .method("DELETE", java.net.http.HttpRequest.BodyPublishers.noBody())
                            .build();
                    java.net.http.HttpClient.newHttpClient().send(req, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
    }

    private void runIncrementRestore(String manifestName) throws Exception {
        SyncCfgFull c = cfg();
        runBackup(true); // 恢复前安全兜底（当前状态先推一份，用 auto- 前缀标记）
        setP("正在下载清单", 5);
        byte[] json = dav.get(c.server(), c.user(), c.pass(), MANIFEST_DIR + "/" + manifestName);
        Map<String, Object> parsed = M.readValue(new String(json, java.nio.charset.StandardCharsets.UTF_8), Map.class);
        List<Map<String, Object>> entries = (List<Map<String, Object>>) (Object) parsed.get("entries");
        Path data = config.dataDir();
        int total = entries.size(), done = 0;
        // 批量下载需防止网关/云盘限流，恢复期把最小请求间隔放宽到 300ms（默认 450ms 对
        // AList 代理场景偏保守，实测 300ms 稳定）；结束后恢复原值。
        dav.wideThrottle(300);
        List<String> failed = new ArrayList<>();
        try {
            for (Map<String, Object> e : entries) {
                String rel = String.valueOf(e.get("rel"));
                String hash = String.valueOf(e.get("sha256"));
                int parts = e.get("parts") instanceof Number n ? n.intValue() : 1;
                Path target = data.resolve(rel).normalize();
                if (!target.startsWith(data)) { done++; continue; }
                // 断点续传：本地已存在且内容一致的文件直接跳过。
                // 恢复中途因网络/风控失败后重试，只需补下缺失部分，不必重下整包。
                long cloudSize = e.get("size") instanceof Number sn ? sn.longValue() : -1;
                if (!rel.equals("app.json") && cloudSize >= 0 && Files.exists(target)
                        && Files.size(target) == cloudSize) {
                    try {
                        if (sha256(Files.readAllBytes(target)).equals(hash)) {
                            done++;
                            setP("校验 " + done + "/" + total + "：" + rel, 10 + done * 88 / Math.max(1, total));
                            continue;
                        }
                    } catch (Exception ignored) { /* 读不了就重下 */ }
                }
                try {
                    Files.createDirectories(target.getParent());
                    java.io.ByteArrayOutputStream content = new java.io.ByteArrayOutputStream();
                    for (int pi = 0; pi < parts; pi++) {
                        String obj = objName(hash, pi, parts);
                        byte[] part = dav.get(c.server(), c.user(), c.pass(), OBJECT_DIR + "/" + obj,
                                Duration.ofMinutes(15));
                        content.writeBytes(part);
                    }
                    // app.json 恢复时保留本地 API Key（云端备份里的 Key 是脱敏空值）
                    if (rel.equals("app.json") && Files.exists(target)) {
                        String cloud = content.toString(java.nio.charset.StandardCharsets.UTF_8);
                        String merged = mergeAppConfig(Files.readString(target, java.nio.charset.StandardCharsets.UTF_8), cloud);
                        Files.writeString(target, merged, java.nio.charset.StandardCharsets.UTF_8);
                    } else {
                        Files.write(target, content.toByteArray());
                    }
                } catch (Exception ex) {
                    // 限流/单文件缺失不拖垮整体：记下来继续，下次恢复会只补这些
                    failed.add(rel);
                    log.warn("恢复跳过 {}：{}", rel, ex.getMessage());
                }
                done++;
                setP("恢复 " + done + "/" + total + "：" + rel, 10 + done * 88 / Math.max(1, total));
            }
        } finally {
            dav.wideThrottle(0);
        }
        int okCount = total - failed.size();
        String summary = failed.isEmpty()
                ? "已恢复 " + total + " 项，API Key 保留本地"
                : "已恢复 " + okCount + " 项，" + failed.size() + " 项因限流/缺失跳过；再点一次「从云端恢复」可续传补齐";
        progress.set(new Progress(false, failed.isEmpty() ? "恢复完成" : "恢复部分完成（可续传）",
                100, null, (long) json.length / 1024, manifestName, summary, startedAt));
        log.info("增量恢复完成: {}（成功 {} / 失败 {}），API Key 保留本地",
                manifestName, okCount, failed.size());
    }

    /**
     * 恢复 app.json 时保留本机特有配置：API Key（云端备份里是脱敏空值）
     * 与 sync 节（坚果云账号/应用密码是设备本地属性，不应被云端覆盖）。
     */
    private String mergeAppConfig(String localJson, String cloudJson) {
        try {
            var local = M.readTree(localJson);
            var cloud = M.readTree(cloudJson);
            if (local.get("providers") instanceof List<?> lp && cloud.get("providers") instanceof List<?> cp) {
                for (Object o : cp) {
                    if (o instanceof Map<?, ?> ce) {
                        for (Object o2 : lp) {
                            if (o2 instanceof Map<?, ?> le
                                    && String.valueOf(le.get("name")).equals(String.valueOf(ce.get("name")))) {
                                ((Map<String, Object>) o2).put("apiKey", le.get("apiKey"));
                            }
                        }
                    }
                }
            }
            if (local.get("sync") != null) {
                ((Map<String, Object>) cloud).put("sync", local.get("sync"));
            }
            return M.writerWithDefaultPrettyPrinter().writeValueAsString(cloud);
        } catch (Exception e) {
            return cloudJson;
        }
    }

    /** 旧版 zip 备份恢复（兼容历史格式）。 */
    public List<BackupInfo> cloudList() {
        SyncCfgFull c = cfg();
        try {
            List<WebDavClient.Entry> jsonEntries = dav.propfind(c.server(), c.user(), c.pass(), MANIFEST_DIR)
                    .stream().filter((e) -> !e.directory() && e.name().endsWith(".json")).toList();

            List<BackupInfo> out = new ArrayList<>();
            List<WebDavClient.Entry> legacy = new ArrayList<>();
            for (WebDavClient.Entry e : jsonEntries) {
                // 先从文件名取（新格式），取不到再用缓存，最后才考虑异步解析
                var mn = java.util.regex.Pattern
                        .compile("-(\\d+)-(\\d+)mb\\.json$").matcher(e.name());
                int files = -1;
                long bytes = 0;
                if (mn.find()) {
                    files = Integer.parseInt(mn.group(1));
                    bytes = Long.parseLong(mn.group(2)) * 1048576L;
                } else {
                    long[] meta = manifestMeta.get(e.name());
                    if (meta != null) { files = (int) meta[0]; bytes = meta[1]; }
                    else legacy.add(e);
                }
                out.add(new BackupInfo(e.name(), bytes, e.lastModified(), files, e.name().startsWith("auto-")));
            }
            // 老格式清单：后台补解析，本次先返回未知
            if (!legacy.isEmpty()) {
                var bg = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "manifest-meta");
                    t.setDaemon(true);
                    return t;
                });
                bg.submit(() -> {
                    for (WebDavClient.Entry e : legacy) {
                        try {
                            byte[] b = dav.get(cfg().server(), cfg().user(), cfg().pass(),
                                    MANIFEST_DIR + "/" + e.name(), Duration.ofSeconds(60));
                            Map<String, Object> m = M.readValue(new String(b, java.nio.charset.StandardCharsets.UTF_8), Map.class);
                            long f = -1, by = 0;
                            if (m.get("entries") instanceof List<?> list) {
                                f = list.size();
                                for (Object o : list) {
                                    if (o instanceof Map<?, ?> em && em.get("size") instanceof Number n) by += n.longValue();
                                }
                            }
                            manifestMeta.put(e.name(), new long[]{f, by});
                        } catch (Exception ignored) { }
                    }
                });
                bg.shutdown();
            }
            for (WebDavClient.Entry e : dav.propfind(c.server(), c.user(), c.pass(), REMOTE_DIR)) {
                if (!e.directory() && e.name().endsWith(".zip")) {
                    out.add(new BackupInfo(e.name(), 0, e.lastModified(), -1, false));
                }
            }
            // 正常备份排前面（按时间新→旧），自动兜底快照排最后
            out.sort((a, b) -> {
                if (a.auto() != b.auto()) return a.auto() ? 1 : -1;
                return Long.compare(b.lastModified(), a.lastModified());
            });
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("列出云端备份失败: " + e.getMessage(), e);
        }
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            SyncCfgFull c = cfg();
            WebDavClient.Entry latest = null;
            for (WebDavClient.Entry e : dav.propfind(c.server(), c.user(), c.pass(), MANIFEST_DIR)) {
                if (!e.directory() && e.name().endsWith(".json")
                        && (latest == null || e.lastModified() > latest.lastModified())) latest = e;
            }
            m.put("cloudBackupAt", latest == null ? 0 : latest.lastModified());
            m.put("cloudBackupName", latest == null ? "" : latest.name());
            Path chat = config.dataDir().resolve("chat.db");
            m.put("localVersionAt", Files.exists(chat) ? Files.getLastModifiedTime(chat).toMillis() : 0);
            m.put("ok", true);
        } catch (Exception e) {
            m.put("ok", false);
            m.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
        }
        return m;
    }

    private void runLegacyZipRestore(String cloudName) throws Exception {
        SyncCfgFull c = cfg();
        setP("正在下载 " + cloudName, 10);
        byte[] zip = dav.get(c.server(), c.user(), c.pass(), REMOTE_DIR + "/" + cloudName,
                Duration.ofMinutes(30));
        setP("正在解包恢复", 60);
        Path data = config.dataDir();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                String rel = entry.getName();
                if (rel.endsWith("/") || rel.startsWith("lucene/") || rel.equals("app.json")) continue;
                Path target = data.resolve(rel).normalize();
                if (!target.startsWith(data)) continue;
                Files.createDirectories(target.getParent());
                Files.copy(zin, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        progress.set(new Progress(false, "恢复完成", 100, null, (long) zip.length / 1024, cloudName,
                "已从整包恢复，索引将自动重建", startedAt));
    }

    private String sha256(byte[] data) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String basic(String user, String pass) {
        return "Basic " + java.util.Base64.getEncoder().encodeToString(
                (user + ":" + pass).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private java.net.URI davUri(String server, String path) {
        String base = server.endsWith("/") ? server : server + "/";
        String p = path.startsWith("/") ? path.substring(1) : path;
        return java.net.URI.create(base + p);
    }

    private void collect(Path dir, List<Path> out) {
        if (dir == null || !Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (Files.isRegularFile(p)) out.add(p);
            }
        } catch (IOException ignored) { }
    }

    /** 每日 03:05 自动备份（已配置且允许时）。 */
    @Scheduled(cron = "0 5 3 * * ?")
    public void autoBackup() {
        try {
            AppConfig.Sync s = config.get().getSync();
            if (s == null || !s.isAutoDaily()) return;
            startBackup();
        } catch (Exception e) {
            log.warn("自动备份失败: {}", e.getMessage());
        }
    }
}
