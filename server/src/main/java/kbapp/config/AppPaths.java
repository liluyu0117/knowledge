package kbapp.config;

import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 数据目录解析：从工作目录向上找项目根（含 .kb 或语料目录"新炬"），
 * 保证 mvn spring-boot:run（CWD=server/）与直接运行行为一致。
 */
@Component
public class AppPaths {

    private final Path root;

    public AppPaths() {
        this.root = detectRoot();
    }

    /** 供 Spring 上下文初始化器等非 Bean 场景使用。 */
    public static Path detectRootStatic() {
        return detectRoot();
    }

    private static Path detectRoot() {
        Path cur = Paths.get("").toAbsolutePath().normalize();
        for (int i = 0; i < 4 && cur != null; i++) {
            if (Files.isDirectory(cur.resolve(".kb")) || Files.isDirectory(cur.resolve("新炬"))) {
                return cur;
            }
            cur = cur.getParent();
        }
        return Paths.get("").toAbsolutePath().normalize();
    }

    public Path root() { return root; }

    public Path dataDir() { return root.resolve(".kb"); }

    public Path luceneDir() { return dataDir().resolve("lucene"); }

    public Path chatDb() { return dataDir().resolve("chat.db"); }

    public Path appJson() { return dataDir().resolve("app.json"); }
}
