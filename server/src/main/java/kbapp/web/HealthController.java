package kbapp.web;

import kbapp.config.AppPaths;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查：桌面壳用它判断「服务已就绪」，同时回显真实数据目录，
 * 便于排查「打开是空库」这类数据路径问题。
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final AppPaths paths;

    @Value("${server.port:39217}")
    private int port;

    public HealthController(AppPaths paths) {
        this.paths = paths;
    }

    @GetMapping
    public Map<String, Object> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("version", version());
        m.put("port", port);
        m.put("dataDir", paths.dataDir().toString());
        m.put("root", paths.root().toString());
        return m;
    }

    private String version() {
        String v = HealthController.class.getPackage().getImplementationVersion();
        return (v == null || v.isBlank()) ? "0.1.0" : v;
    }
}
