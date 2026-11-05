package kbapp.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 数据目录解析。
 *
 * 优先级（第一个命中即用）：
 * <ol>
 *   <li>启动参数 {@code -Dkb.home=D:\某目录}（该目录下放 .kb）</li>
 *   <li>环境变量 {@code KB_HOME}</li>
 *   <li>程序目录 / 当前目录下的 {@code kb-home.txt}（首行路径，# 开头为注释）</li>
 *   <li>旧逻辑：从工作目录向上找含 .kb 或语料目录「新炬」的目录（兼容 mvn / bat 启动）</li>
 *   <li>兜底：程序目录下 {@code data}（可写时），否则 {@code %APPDATA%\KnowledgeBase}</li>
 * </ol>
 *
 * 打包成桌面应用后工作目录往往不是项目根，第 1~3 条保证仍能指向原有数据，
 * 否则会在安装目录新建一个空库、看起来像「数据全丢了」。
 */
@Component
public class AppPaths {

    private static final Logger log = LoggerFactory.getLogger(AppPaths.class);

    private final Path root;

    public AppPaths() {
        this.root = detectRoot();
        log.info("数据目录: {}（数据文件位于 {}）", root, dataDirStatic(root));
    }

    /** 供 Spring 上下文初始化器等非 Bean 场景使用。 */
    public static Path detectRootStatic() {
        return detectRoot();
    }

    private static Path detectRoot() {
        // 1) -Dkb.home
        Path explicit = existingDir(System.getProperty("kb.home"));
        if (explicit != null) return explicit;

        // 2) 环境变量
        explicit = existingDir(System.getenv("KB_HOME"));
        if (explicit != null) return explicit;

        // 3) kb-home.txt（程序目录优先，其次当前目录）
        for (Path base : List.of(appDir(), Paths.get("").toAbsolutePath())) {
            Path fromFile = readHomeFile(base);
            if (fromFile != null) return fromFile;
        }

        // 4) 旧逻辑：向上找 .kb / 新炬
        Path cur = Paths.get("").toAbsolutePath().normalize();
        for (int i = 0; i < 4 && cur != null; i++) {
            if (Files.isDirectory(cur.resolve(".kb")) || Files.isDirectory(cur.resolve("新炬"))) {
                return cur;
            }
            cur = cur.getParent();
        }

        // 5) 兜底：程序目录/data（可写）→ %APPDATA%\KnowledgeBase
        Path data = appDir().resolve("data");
        try {
            Files.createDirectories(data);
            return data;
        } catch (IOException ignored) { }

        String appData = System.getenv("APPDATA");
        Path fallback = (appData == null || appData.isBlank())
                ? Paths.get(System.getProperty("user.home", "."), "KnowledgeBase")
                : Paths.get(appData, "KnowledgeBase");
        try {
            Files.createDirectories(fallback);
        } catch (IOException ignored) { }
        return fallback;
    }

    private static Path dataDirStatic(Path root) {
        return root.resolve(".kb");
    }

    private static Path existingDir(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            Path p = Paths.get(raw.trim()).toAbsolutePath().normalize();
            if (Files.isDirectory(p)) return p;
            // 指到了 .kb 本身：退回它的父目录
            if (p.getFileName() != null && ".kb".equals(p.getFileName().toString())) {
                Path parent = p.getParent();
                if (parent != null && Files.isDirectory(parent)) return parent;
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static Path readHomeFile(Path base) {
        Path f = base.resolve("kb-home.txt");
        if (!Files.isRegularFile(f)) return null;
        try {
            for (String line : Files.readAllLines(f)) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) continue;
                Path p = existingDir(s);
                if (p != null) return p;
                log.warn("kb-home.txt 中的路径不存在或不可访问: {}", s);
                return null;
            }
        } catch (IOException e) {
            log.warn("读取 kb-home.txt 失败: {}", e.toString());
        }
        return null;
    }

    /** 程序目录：优先从 java 可执行文件反推（桌面版为 <程序>/resources/jre/bin/java.exe）。 */
    public static Path appDir() {
        try {
            Path cmd = ProcessHandle.current().info().command().map(Paths::get).orElse(null);
            if (cmd != null) {
                Path bin = cmd.getParent();          // .../bin
                if (bin != null && "bin".equals(bin.getFileName() == null ? "" : bin.getFileName().toString())) {
                    Path rt = bin.getParent();        // .../jre
                    if (rt != null) {
                        Path app = rt.getParent();
                        if (app != null) return app;
                    }
                }
            }
        } catch (Exception ignored) { }
        return Paths.get("").toAbsolutePath().normalize();
    }

    public Path root() { return root; }

    public Path dataDir() { return root.resolve(".kb"); }

    public Path luceneDir() { return dataDir().resolve("lucene"); }

    public Path chatDb() { return dataDir().resolve("chat.db"); }

    public Path appJson() { return dataDir().resolve("app.json"); }
}
