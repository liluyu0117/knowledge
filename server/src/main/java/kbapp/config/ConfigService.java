package kbapp.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * .kb/app.json 读写：启动时缺省创建，更新时原子替换，volatile 引用发布。
 */
@Service
public class ConfigService {

    private static final Logger log = LoggerFactory.getLogger(ConfigService.class);
    private static final ObjectMapper M = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final AppPaths paths;
    private volatile AppConfig current;

    public ConfigService(AppPaths paths) {
        this.paths = paths;
    }

    @PostConstruct
    public synchronized void init() {
        try {
            Path file = paths.appJson();
            Files.createDirectories(file.getParent());
            if (!Files.exists(file)) {
                AppConfig def = AppConfig.defaults();
                M.writeValue(file.toFile(), def);
                log.info("已创建默认配置 {}", file);
            }
            current = M.readValue(file.toFile(), AppConfig.class);
        } catch (IOException e) {
            throw new IllegalStateException("读取配置失败: " + paths.appJson(), e);
        }
    }

    public AppConfig get() {
        if (current == null) init();
        return current;
    }

    public synchronized void update(AppConfig next) {
        try {
            Path file = paths.appJson();
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            M.writeValue(tmp.toFile(), next);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            current = next;
        } catch (IOException e) {
            throw new IllegalStateException("写入配置失败: " + paths.appJson(), e);
        }
    }

    public Path dataDir() {
        return paths.dataDir();
    }
}
