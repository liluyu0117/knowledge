package kbapp.web;

import kbapp.retrieve.GlossaryStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 业务术语表：内部黑话/缩写的同义说法，用于检索时的关键词扩展。 */
@RestController
@RequestMapping("/api/glossary")
public class GlossaryController {

    private final GlossaryStore store;

    public GlossaryController(GlossaryStore store) {
        this.store = store;
    }

    @GetMapping
    public List<GlossaryStore.Term> list() {
        return store.list();
    }

    /** body 形如 {"terms":[{"term":"运管","aliases":["运营管理"]}]}；也接受直接数组。 */
    @PutMapping
    public Map<String, Object> save(@RequestBody Object body) {
        List<GlossaryStore.Term> next = new ArrayList<>();
        Object raw = body;
        if (body instanceof Map<?, ?> m && m.get("terms") != null) raw = m.get("terms");
        if (raw instanceof List<?> arr) {
            for (Object o : arr) {
                if (!(o instanceof Map<?, ?> t)) continue;
                String term = t.get("term") == null ? "" : String.valueOf(t.get("term"));
                List<String> aliases = new ArrayList<>();
                if (t.get("aliases") instanceof List<?> as) {
                    for (Object a : as) if (a != null) aliases.add(String.valueOf(a));
                }
                next.add(new GlossaryStore.Term(term, aliases));
            }
        }
        store.save(next);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true);
        res.put("count", store.list().size());
        return res;
    }
}
