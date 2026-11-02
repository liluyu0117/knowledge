package kbapp.sync;

import kbapp.config.AppConfig;
import kbapp.config.ConfigService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/sync")
public class SyncController {

    private final ConfigService config;
    private final SyncService sync;

    public SyncController(ConfigService config, SyncService sync) {
        this.config = config;
        this.sync = sync;
    }

    @GetMapping("/config")
    public AppConfig.Sync config() {
        AppConfig.Sync s = config.get().getSync();
        return s == null ? new AppConfig.Sync() : s;
    }

    public record SyncCfgBody(String server, String user, String appPassword, Boolean autoDaily) {}

    @PostMapping("/config")
    public AppConfig.Sync save(@RequestBody SyncCfgBody body) {
        AppConfig cfg = config.get();
        AppConfig.Sync s = cfg.getSync() == null ? new AppConfig.Sync() : cfg.getSync();
        if (body.server() != null) s.setServer(body.server());
        if (body.user() != null) s.setUser(body.user());
        // 应用密码为空/掩码时不覆盖
        if (body.appPassword() != null && !body.appPassword().isBlank() && !body.appPassword().contains("••")) {
            s.setAppPassword(body.appPassword().trim());
        }
        if (body.autoDaily() != null) s.setAutoDaily(body.autoDaily());
        config.update(cfg);
        return cfg.getSync();
    }

    @GetMapping("/test")
    public Map<String, Object> test() {
        Map<String, Object> m = new LinkedHashMap<>();
        try { sync.test(); m.put("ok", true); }
        catch (Exception e) { m.put("ok", false); m.put("error", e.getMessage()); }
        return m;
    }

    @PostMapping("/backup")
    public Map<String, Object> backup() {
        boolean started = sync.startBackup();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("started", started);
        if (!started) m.put("progress", sync.progress());
        return m;
    }

    /** 备份/恢复实时进度。 */
    @GetMapping("/progress")
    public SyncService.Progress progress() {
        return sync.progress();
    }

    @GetMapping("/list")
    public Object list() {
        try { return sync.cloudList(); }
        catch (Exception e) { return java.util.List.of(java.util.Map.of("name", "错误: " + e.getMessage())); }
    }

    public record RestoreBody(String name) {}

    @PostMapping("/restore")
    public Map<String, Object> restore(@RequestBody RestoreBody body) {
        boolean started = sync.startRestore(body.name());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("started", started);
        if (!started) m.put("progress", sync.progress());
        return m;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return sync.status();
    }
}
