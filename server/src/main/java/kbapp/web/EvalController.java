package kbapp.web;

import kbapp.config.AppPaths;
import kbapp.retrieve.HybridSearchService;
import kbapp.retrieve.SearchHit;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 检索回归评估（Group A）：固定问题集 × 当前检索配置 → 命中率 / MRR。
 * 题集存 .kb/eval.json（首次自动生成默认 20 题），可手工编辑增删。
 */
@RestController
@RequestMapping("/api/eval")
public class EvalController {

    private final HybridSearchService search;
    private final AppPaths paths;

    public EvalController(HybridSearchService search, AppPaths paths) {
        this.search = search;
        this.paths = paths;
    }

    public record EvalCase(String q, List<String> any) {}

    private static final String DEFAULT_EVAL = """
            [
              {"q":"上线流程","any":["上线"]},
              {"q":"华为云 域名切换","any":["域名切换","域名"]},
              {"q":"客服工单 状态同步接口","any":["客服工单","同步工单状态"]},
              {"q":"Nacos 配置修改","any":["nacos"]},
              {"q":"需求文档变更流程 稽核","any":["需求文档变更"]},
              {"q":"邮件组件 附件大小","any":["邮件组件"]},
              {"q":"comp_system 表结构","any":["comp_system"]},
              {"q":"版本全流程 环节","any":["版本全流程"]},
              {"q":"测试报告","any":["测试报告"]},
              {"q":"工作量 三季度","any":["工作量"]},
              {"q":"关联扫描任务","any":["关联扫描"]},
              {"q":"单点登录 集成指南","any":["sso","单点登录"]},
              {"q":"应急切换 域名一键切换","any":["域名切换","应急切换"]},
              {"q":"台账接口","any":["台账"]},
              {"q":"安全漏洞 派单","any":["安全漏洞"]},
              {"q":"MOA 上线文档","any":["moa"]},
              {"q":"事件工单 流程","any":["事件工单"]},
              {"q":"上线检查清单","any":["检查清单"]},
              {"q":"算法发布","any":["算法发布"]},
              {"q":"部署文档","any":["部署"]}
            ]
            """;

    private List<EvalCase> loadCases() {
        Path f = paths.dataDir().resolve("eval.json");
        try {
            if (!Files.exists(f)) {
                Files.createDirectories(f.getParent());
                Files.writeString(f, DEFAULT_EVAL);
            }
            com.fasterxml.jackson.databind.ObjectMapper M = new com.fasterxml.jackson.databind.ObjectMapper();
            return List.of(M.readValue(f.toFile(), EvalCase[].class));
        } catch (Exception e) {
            throw new IllegalStateException("读取评估题集失败: " + f, e);
        }
    }

    /** 运行评估：题集逐条检索，按关键词命中判定排名（hit@k / MRR）。 */
    @GetMapping("/run")
    public Map<String, Object> run(@RequestParam(defaultValue = "5") int topK,
                                   @RequestParam(defaultValue = "true") boolean vector,
                                   @RequestParam(defaultValue = "default") String kbId) {
        List<EvalCase> cases = loadCases();
        List<Map<String, Object>> items = new ArrayList<>();
        int hits = 0;
        double mrrSum = 0;
        for (EvalCase c : cases) {
            HybridSearchService.Result r;
            try {
                r = search.search(c.q(), topK, null, null, vector, kbId);
            } catch (Exception e) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("q", c.q());
                m.put("hit", false);
                m.put("error", String.valueOf(e.getMessage()));
                items.add(m);
                continue;
            }
            int rank = 0;
            String matchedDoc = "";
            int idx = 0;
            for (SearchHit h : r.hits()) {
                idx++;
                String hay = (h.docPath() + " " + h.docTitle() + " " + h.heading() + " "
                        + h.snippetHtml().replace("<mark>", "").replace("</mark>", ""))
                        .toLowerCase(Locale.ROOT);
                boolean match = c.any() != null && c.any().stream()
                        .anyMatch((k) -> hay.contains(k.toLowerCase(Locale.ROOT)));
                if (match && rank == 0) {
                    rank = idx;
                    matchedDoc = h.docPath();
                    break;
                }
            }
            if (rank > 0) hits++;
            if (rank > 0) mrrSum += 1.0 / rank;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("q", c.q());
            m.put("rank", rank);
            m.put("hit", rank > 0);
            m.put("doc", matchedDoc);
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", cases.size());
        out.put("hitAtK", cases.isEmpty() ? 0 : (double) hits / cases.size());
        out.put("mrr", cases.isEmpty() ? 0 : mrrSum / cases.size());
        out.put("topK", topK);
        out.put("vectorUsed", vector);
        out.put("items", items);
        return out;
    }
}
