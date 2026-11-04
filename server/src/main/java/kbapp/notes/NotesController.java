package kbapp.notes;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notes")
public class NotesController {

    private final NotesStore store;

    public NotesController(NotesStore store) {
        this.store = store;
    }

    @GetMapping
    public Object list() {
        return store.list();
    }

    /**
     * 反向引用：哪些笔记引用了某文档（笔记正文里写了 @doc:该路径）。
     * 笔记量级小，直接扫正文；返回命中的笔记与上下文片段。
     */
    @GetMapping("/backrefs")
    public List<Map<String, Object>> backrefs(@RequestParam("docPath") String docPath) {
        List<Map<String, Object>> out = new ArrayList<>();
        String needle = "@doc:" + docPath;
        for (NotesStore.Note n : store.list()) {
            String c;
            try { c = store.content(n.id()); } catch (Exception e) { continue; }
            if (c == null || !c.contains(needle)) continue;
            int at = c.indexOf(needle);
            int from = Math.max(0, at - 40);
            int to = Math.min(c.length(), at + needle.length() + 60);
            String ctx = c.substring(from, to).replaceAll("\\s+", " ").trim();
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", n.id());
            m.put("title", n.title());
            m.put("snippet", "…" + ctx + "…");
            out.add(m);
        }
        return out;
    }

    public record NoteBody(String title, String type, String content) {}
    public record RenameBody(String title) {}

    @PostMapping
    public Map<String, Object> create(@RequestBody NoteBody body) {
        NotesStore.Note n = store.create(body.title(), body.type(), body.content());
        return view(n);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        NotesStore.Note n = store.byId(id);
        Map<String, Object> m = new LinkedHashMap<>();
        if (n == null) { m.put("error", "笔记不存在"); return m; }
        m.put("id", n.id());
        m.put("title", n.title());
        m.put("type", n.type());
        m.put("content", store.content(id));
        return m;
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable String id, @RequestBody NoteBody body) {
        store.update(id, body.title(), body.content());
        return Map.of("ok", true);
    }

    /** 重命名：只改文件名，不动正文；重名返回 ok=false 让前端就地提示。 */
    @PatchMapping("/{id}/rename")
    public Map<String, Object> rename(@PathVariable String id, @RequestBody RenameBody body) {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            NotesStore.Note n = store.rename(id, body.title());
            m.put("ok", true);
            m.put("id", n.id());
            m.put("title", n.title());
        } catch (IllegalArgumentException e) {
            m.put("ok", false);
            m.put("error", e.getMessage());
        }
        return m;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> remove(@PathVariable String id) {
        store.remove(id);
        return Map.of("ok", true);
    }

    private Map<String, Object> view(NotesStore.Note n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", n.id());
        m.put("title", n.title());
        m.put("type", n.type());
        m.put("updatedAt", n.updatedAt());
        return m;
    }
}
