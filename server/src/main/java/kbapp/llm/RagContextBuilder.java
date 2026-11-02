package kbapp.llm;

import kbapp.retrieve.SearchHit;

import java.util.List;

/** 把检索命中拼成 LLM 上下文（设计文档 8）。 */
public final class RagContextBuilder {

    public static final String SYSTEM_PROMPT = """
            你是"知识库"的企业知识助手。回答规则：
            1. 只依据下面提供的知识库片段回答；每个事实性陈述在句末标注对应片段序号，如 [1]。
            2. 片段不足以回答时，明确说明"知识库中未找到"，不要编造。
            3. 用简体中文回答，条理清晰，重要名词可加粗。
            4. 引用片段时保留其原有术语与数字，不要改写数值。
            """;

    /** 智能体自定义角色：customPrompt 为空时用默认规则。 */
    public static String systemPrompt(String agentName, String description, String customPrompt) {
        String role = "你是\"知识库\"智能体「" + agentName + "」"
                + (description == null || description.isBlank() ? "" : "（" + description + "）") + "。";
        String custom = customPrompt == null || customPrompt.isBlank() ? "" : customPrompt.trim() + "\n";
        return role + custom + "\n回答规则：\n"
                + "1. 只依据下面提供的知识库片段回答；每个事实性陈述在句末标注对应片段序号，如 [1]。\n"
                + "2. 片段不足以回答时，明确说明\"知识库中未找到\"，不要编造。\n"
                + "3. 用简体中文回答，条理清晰，重要名词可加粗。\n"
                + "4. 引用片段时保留其原有术语与数字，不要改写数值。";
    }

    /** 输入框下方的「回答风格」：brief 简洁 / detail 详细 / 其它为标准。 */
    public static String styleClause(String style) {
        if (style == null) return "";
        return switch (style) {
            case "brief" -> "\n5. 回答尽量简短：先给结论，再用不超过 3 条要点展开，总长度控制在 200 字以内。";
            case "detail" -> "\n5. 回答可以详细展开：先给结论，再分点说明依据、执行步骤与注意事项。";
            default -> "";
        };
    }

    private RagContextBuilder() {}

    public static String buildContext(List<SearchHit> hits) {
        StringBuilder sb = new StringBuilder();
        int n = 1;
        for (SearchHit h : hits) {
            sb.append('[').append(n).append("] ")
              .append(h.docTitle()).append(" · ").append(h.docPath());
            if (h.heading() != null && !h.heading().isBlank()) {
                sb.append(" · ").append(h.heading());
            }
            sb.append("（块 ").append(h.seq()).append("）\n");
            // 父子分块模式：contextText 是父块（小节）全文，比 180 字摘要完整得多
            String body = h.contextText() != null && !h.contextText().isBlank()
                    ? h.contextText() : stripMarks(h.snippetHtml());
            sb.append(body).append("\n\n");
            n++;
        }
        return sb.toString().strip();
    }

    private static String stripMarks(String s) {
        return s == null ? "" : s.replace("<mark>", "").replace("</mark>", "");
    }
}
