package kbapp.web;

import kbapp.retrieve.ChunkFlagStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 块级屏蔽：解析错的表、过时的段落可以直接禁掉，不必重建索引。 */
@RestController
@RequestMapping("/api/chunk-flags")
public class ChunkFlagController {

    private final ChunkFlagStore store;

    public ChunkFlagController(ChunkFlagStore store) {
        this.store = store;
    }

    public record FlagBody(String kbId, String docPath, Integer seq, Boolean disabled, String note) {}

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam String kbId) {
        return store.list(kbId);
    }

    @PostMapping
    public Map<String, Object> toggle(@RequestBody FlagBody body) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (body.docPath() == null || body.seq() == null) {
            m.put("ok", false);
            m.put("error", "缺少 docPath / seq");
            return m;
        }
        boolean disabled = body.disabled() == null || body.disabled();
        store.set(body.kbId() == null ? "default" : body.kbId(), body.docPath(), body.seq(), disabled, body.note());
        m.put("ok", true);
        m.put("disabled", disabled);
        return m;
    }
}
