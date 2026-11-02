package kbapp.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

/** 启动前读 .kb/app.json 的 mcp.enabled，桥接为 spring.ai.mcp.server.enabled。 */
public class McpSwitchInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final ObjectMapper M = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public void initialize(ConfigurableApplicationContext ctx) {
        try {
            var f = kbapp.config.AppPaths.detectRootStatic().resolve(".kb").resolve("app.json");
            if (Files.exists(f)) {
                Map<?, ?> cfg = M.readValue(f.toFile(), Map.class);
                if (cfg.get("mcp") instanceof Map<?, ?> m && m.get("enabled") instanceof Boolean b && !b) {
                    ctx.getEnvironment().getSystemProperties()
                            .put("spring.ai.mcp.server.enabled", "false");
                }
            }
        } catch (Exception ignored) {
            // 配置缺失时默认启用 MCP
        }
    }
}
