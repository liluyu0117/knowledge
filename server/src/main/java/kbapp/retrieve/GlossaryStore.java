package kbapp.retrieve;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import kbapp.config.AppPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 业务术语表（.kb/glossary.json）：内部黑话/缩写 → 同义说法。
 * 只在关键词路上做扩展，向量路仍用原句，避免把语义拉偏。
 */
@Service
public class GlossaryStore {

    private static final Logger log = LoggerFactory.getLogger(GlossaryStore.class);
    private static final ObjectMapper M = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public record Term(String term, List<String> aliases) {
        public Term normalized() {
            String t = term == null ? "" : term.trim();
            List<String> as = new ArrayList<>();
            if (aliases != null) {
                for (String a : aliases) {
                    if (a != null && !a.isBlank() && !as.contains(a.trim())) as.add(a.trim());
                }
            }
            return new Term(t, as);
        }
    }

    private final AppPaths paths;
    private volatile List<Term> terms = List.of();

    public GlossaryStore(AppPaths paths) {
        this.paths = paths;
    }

    private Path file() {
        return paths.dataDir().resolve("glossary.json");
    }

    @PostConstruct
    public synchronized void init() {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            if (!Files.exists(f)) {
                write(List.of());
                return;
            }
            var root = M.readTree(f.toFile());
            var arr = root.isArray() ? root : root.get("terms");
            List<Term> list = new ArrayList<>();
            if (arr != null && arr.isArray()) {
                for (var n : arr) {
                    String t = n.path("term").asText("");
                    List<String> as = new ArrayList<>();
                    var a = n.get("aliases");
                    if (a != null && a.isArray()) for (var x : a) as.add(x.asText(""));
                    if (!t.isBlank()) list.add(new Term(t, as).normalized());
                }
            }
            terms = List.copyOf(list);
        } catch (Exception e) {
            log.warn("读取 glossary.json 失败：{}", e.toString());
        }
    }

    public List<Term> list() {
        return terms;
    }

    public synchronized void save(List<Term> next) {
        List<Term> clean = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Term t : next == null ? List.<Term>of() : next) {
            Term n = t.normalized();
            if (n.term().isBlank() || seen.contains(n.term())) continue;
            seen.add(n.term());
            clean.add(n);
        }
        write(clean);
        terms = List.copyOf(clean);
    }

    private void write(List<Term> list) {
        try {
            var root = M.createObjectNode();
            var arr = root.putArray("terms");
            for (Term t : list) {
                var o = arr.addObject();
                o.put("term", t.term());
                var as = o.putArray("aliases");
                for (String a : t.aliases()) as.add(a);
            }
            M.writerWithDefaultPrettyPrinter().writeValue(file().toFile(), root);
        } catch (IOException e) {
            throw new IllegalStateException("写入 glossary.json 失败", e);
        }
    }

    /**
     * 查询扩展：查句里出现某个术语（或它的别名）时，把其余说法作为补充词返回。
     * 例如术语「运管」别名「运营管理」，查询「运管上线要几步」→ 补充 "运营管理"。
     */
    public List<String> expand(String query) {
        if (query == null || query.isBlank() || terms.isEmpty()) return List.of();
        Set<String> extra = new LinkedHashSet<>();
        for (Term t : terms) {
            List<String> all = new ArrayList<>();
            all.add(t.term());
            all.addAll(t.aliases());
            boolean hit = false;
            for (String w : all) {
                if (w != null && !w.isBlank() && query.contains(w)) { hit = true; break; }
            }
            if (!hit) continue;
            for (String w : all) {
                if (w != null && !w.isBlank() && !query.contains(w)) extra.add(w);
            }
        }
        return new ArrayList<>(extra);
    }
}
