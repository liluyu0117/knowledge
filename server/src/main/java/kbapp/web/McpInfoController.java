package kbapp.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import kbapp.config.AppConfig;
import kbapp.config.ConfigService;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 自检信息：设置页用它渲染「实际注册了哪些工具、参数是什么」，
 * 免得 UI 里手写一份工具说明，跟代码漂移。
 */
@RestController
@RequestMapping("/api/mcp")
public class McpInfoController {

    private static final ObjectMapper M = new ObjectMapper();

    private final ConfigService config;
    private final ToolCallbackProvider toolProvider;
    private final int appPort;

    public McpInfoController(ConfigService config, ToolCallbackProvider toolProvider,
                             @Value("${local.server.port:${server.port:39217}}") int appPort) {
        this.config = config;
        this.toolProvider = toolProvider;
        this.appPort = appPort;
    }

    @GetMapping("/info")
    public Map<String, Object> info(HttpServletRequest req) {
        AppConfig c = config.get();
        String base = baseUrl(req);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", c.getMcp().isEnabled());
        m.put("name", "kb");
        m.put("version", "0.2.0");
        m.put("sseUrl", base + "/sse");
        m.put("messageEndpoint", base + "/mcp/message");
        m.put("toolCount", 0);
        List<Map<String, Object>> tools = tools();
        m.put("toolCount", tools.size());
        m.put("tools", tools);
        m.put("clients", clients(base + "/sse"));
        return m;
    }

    /**
     * 对外可达的基地址：前端 dev server 经代理访问时 Host 带的是 5173，
     * 必须换回后端真实端口；反代场景靠 X-Forwarded-* （已开 forward-headers-strategy）。
     */
    private String baseUrl(HttpServletRequest req) {
        String scheme = req.getScheme() == null ? "http" : req.getScheme();
        String host = req.getServerName();
        if (host == null || host.isBlank() || "0.0.0.0".equals(host) || "::".equals(host)) host = "localhost";
        int reqPort = req.getServerPort();
        int port = (reqPort == appPort || "https".equals(scheme)) ? reqPort : appPort;
        boolean def = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
        return scheme + "://" + host + (def ? "" : ":" + port);
    }

    /** 各客户端现成的接入片段，前端只负责复制，不再自己拼字符串。 */
    private Map<String, Object> clients(String sse) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("claudeCode", "claude mcp add --transport sse kb " + sse);
        out.put("json", "{\n  \"mcpServers\": {\n    \"kb\": {\n      \"url\": \"" + sse + "\"\n    }\n  }\n}");
        out.put("bridge", "{\n  \"mcpServers\": {\n    \"kb\": {\n      \"command\": \"npx\",\n"
                + "      \"args\": [\"-y\", \"mcp-remote\", \"" + sse + "\"]\n    }\n  }\n}");
        return out;
    }

    /** 从注册好的 ToolCallback 里读名字/描述/参数，不维护第二份清单。 */
    private List<Map<String, Object>> tools() {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            for (ToolCallback cb : toolProvider.getToolCallbacks()) {
                ToolDefinition def = cb.getToolDefinition();
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("name", def.name());
                t.put("description", def.description());
                t.put("params", params(def.inputSchema()));
                out.add(t);
            }
        } catch (Exception ignored) {
            // 拿不到就返回空列表，页面退化成「暂无工具信息」
        }
        return out;
    }

    /** JSON Schema → [{name, type, required, description}]，前端只渲染这一层。 */
    private List<Map<String, Object>> params(String schema) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (schema == null || schema.isBlank()) return out;
        try {
            JsonNode root = M.readTree(schema);
            JsonNode props = root.path("properties");
            List<String> required = new ArrayList<>();
            JsonNode req = root.path("required");
            if (req.isArray()) {
                for (JsonNode r : req) required.add(r.asText());
            }
            for (Iterator<Map.Entry<String, JsonNode>> it = props.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                JsonNode v = e.getValue();
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("name", e.getKey());
                p.put("type", v.path("type").asText("string"));
                p.put("required", required.contains(e.getKey()));
                p.put("description", v.path("description").asText(""));
                out.add(p);
            }
        } catch (Exception ignored) { }
        return out;
    }
}
