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

    public record NoteBody(String title, String type, String content, String folder) {}
    public record RenameBody(String title) {}

    @PostMapping
    public Map<String, Object> create(@RequestBody NoteBody body) {
        NotesStore.Note n = store.create(body.title(), body.type(), body.content(), body.folder());
        return view(n);
    }

    /** 移动笔记到另一文件夹（空串 = 根目录）。 */
    @PostMapping("/{id}/move")
    public Map<String, Object> move(@PathVariable String id, @RequestParam("folder") String folder) {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            NotesStore.Note n = store.moveTo(id, folder);
            m.put("ok", true);
            m.put("folder", n.folderOrRoot());
        } catch (Exception e) {
            m.put("ok", false);
            m.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
        }
        return m;
    }

    /** 现有文件夹列表（含物理目录）。 */
    @GetMapping("/folders")
    public List<String> folders() {
        return store.folders();
    }

    /** 新建文件夹（可多级，已存在视为成功）。 */
    @PostMapping("/folders")
    public Map<String, Object> newFolder(@RequestParam("name") String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            store.createFolder(name);
            m.put("ok", true);
            m.put("folders", store.folders());
        } catch (Exception e) {
            m.put("ok", false);
            m.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
        }
        return m;
    }

    /**
     * 按文件名直读笔记内容（预览抽屉用）：笔记频繁修改，索引总是滞后，
     * 预览不能走索引分块，必须读文件本体。path 形如「标题.md」。
     */
    @GetMapping("/by-file")
    public Map<String, Object> byFile(@RequestParam("path") String path) {
        Map<String, Object> m = new LinkedHashMap<>();
        String fn = path == null ? "" : path.replace('\\', '/');
        int slash = fn.lastIndexOf('/');
        if (slash >= 0) fn = fn.substring(slash + 1);
        int dot = fn.lastIndexOf('.');
        String title = dot > 0 ? fn.substring(0, dot) : fn;
        String ext = dot > 0 ? fn.substring(dot + 1) : "";
        String type = "doc".equals(ext) ? "docx" : ext;
        NotesStore.Note hit = store.list().stream()
                .filter((n) -> n.title().equals(title) && n.type().equals(type))
                .findFirst().orElse(null);
        if (hit == null) { m.put("error", "笔记不存在"); return m; }
        m.put("id", hit.id());
        m.put("title", hit.title());
        m.put("type", hit.type());
        m.put("content", store.content(hit.id()));
        return m;
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
        m.put("folder", n.folderOrRoot());
        return m;
    }
}
