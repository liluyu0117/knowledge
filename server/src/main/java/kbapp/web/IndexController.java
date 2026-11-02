package kbapp.web;

import kbapp.ingest.IndexBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 索引构建：按知识库查询状态、触发构建、确认 / 跳过向量化、取消。
 *
 * <p>状态分两块：live（当前运行中任务的实时进度）与 last（该库最近一次构建结果），
 * 前端据此区分「本库上次结果」与「正在跑的任务」，避免把别的库的数据当成本库的。
 */
@RestController
@RequestMapping("/api/index")
public class IndexController {

    private final IndexBuilder builder;

    public IndexController(IndexBuilder builder) {
        this.builder = builder;
    }

    @GetMapping("/status")
    public Map<String, Object> status(@RequestParam(required = false) String kbId) {
        String id = kbId == null || kbId.isBlank() ? "default" : kbId;
        IndexBuilder.Status s = builder.status();
        boolean running = builder.isRunning();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("running", running);
        m.put("runningKbId", running ? s.kbId() : "");
        m.put("runningKbName", running ? s.kbName() : "");
        m.put("runningPhase", running ? s.phase() : "");
        m.put("runningForThisKb", running && id.equals(s.kbId()));

        Map<String, Object> live = new LinkedHashMap<>();
        live.put("phase", s.phase());
        live.put("kbId", s.kbId());
        live.put("kbName", s.kbName());
        live.put("filesTotal", s.filesTotal());
        live.put("filesDone", s.filesDone());
        live.put("added", s.added());
        live.put("updated", s.updated());
        live.put("removed", s.removed());
        live.put("embedded", s.embedded());
        live.put("needEmbed", s.needEmbed());
        live.put("vectorFailed", s.vectorFailed());
        live.put("errors", s.errors());
        live.put("currentFile", s.currentFile());
        live.put("startedAt", s.startedAt());
        live.put("elapsedMs", s.startedAt() > 0
                ? Math.max(0, (s.updatedAt() > 0 ? s.updatedAt() : System.currentTimeMillis()) - s.startedAt())
                : 0);
        live.put("awaitingCount", s.awaitingCount());
        live.put("awaitingChars", s.awaitingChars());
        live.put("awaitingModel", s.awaitingModel());
        live.put("vectorDim", s.vectorDim());
        live.put("error", s.error());
        m.put("live", live);

        IndexBuilder.BuildInfo last = builder.lastBuild(id);
        if (last == null) {
            m.put("last", null);
        } else {
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("phase", last.phase());
            l.put("kbName", last.kbName());
            l.put("builtAt", last.builtAt());
            l.put("durationMs", last.durationMs());
            l.put("files", last.files());
            l.put("indexed", last.indexed());
            l.put("chunks", last.chunks());
            l.put("added", last.added());
            l.put("updated", last.updated());
            l.put("skipped", last.skipped());
            l.put("removed", last.removed());
            l.put("embedded", last.embedded());
            l.put("vectorFailed", last.vectorFailed());
            l.put("errors", last.errors());
            l.put("vectorDim", last.vectorDim());
            l.put("failed", last.failed() == null ? java.util.List.of() : last.failed());
            l.put("empty", last.empty() == null ? java.util.List.of() : last.empty());
            m.put("last", l);
        }
        m.put("anyBuilt", !builder.lastBuilds().isEmpty());
        m.put("failedCount", builder.failedCount());
        return m;
    }

    @PostMapping("/rebuild")
    public Map<String, Object> rebuild(@RequestParam(defaultValue = "default") String kbId) {
        boolean started = builder.start(kbId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("started", started);
        if (!started) {
            IndexBuilder.Status s = builder.status();
            m.put("runningKbId", s.kbId());
            m.put("runningKbName", s.kbName());
            m.put("runningPhase", s.phase());
        }
        return m;
    }

    /** 确认向量化；vector=false 表示本次跳过向量化，仅建关键词索引。 */
    @PostMapping("/confirm-embed")
    public Map<String, Object> confirmEmbed(@RequestParam(defaultValue = "true") boolean vector) {
        builder.confirmEmbed(vector);
        return Map.of("ok", true, "vector", vector);
    }

    /** 取消当前构建（含卡在等待向量化确认的任务），释放全局构建槽。 */
    @PostMapping("/cancel")
    public Map<String, Object> cancel() {
        return Map.of("cancelled", builder.cancel());
    }
}
