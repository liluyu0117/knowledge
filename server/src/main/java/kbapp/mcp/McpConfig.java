package kbapp.mcp;

import kbapp.config.ConfigService;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** MCP 工具装配；服务开关由 McpSwitchInitializer 按 .kb/app.json 的 mcp.enabled 桥接到 Spring AI。 */
@Configuration
public class McpConfig {

    @Bean
    public ToolCallbackProvider kbToolCallbackProvider(KbSearchTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
