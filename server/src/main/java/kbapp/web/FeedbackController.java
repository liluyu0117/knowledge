package kbapp.web;

import kbapp.chat.ChatStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 回答反馈：赞/踩 + 备注，沉淀 badcase 供调参使用。 */
@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {

    private final ChatStore chat;

    public FeedbackController(ChatStore chat) {
        this.chat = chat;
    }

    public record Body(String messageId, String sessionId, Integer rating, String note) {}

    @PostMapping
    public Map<String, Object> save(@RequestBody Body b) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (b.messageId() == null || b.messageId().isBlank()) {
            m.put("ok", false);
            m.put("error", "缺少 messageId");
            return m;
        }
        int rating = b.rating() == null ? 0 : Math.max(-1, Math.min(1, b.rating()));
        chat.setFeedback(b.messageId(), b.sessionId(), rating, b.note());
        m.put("ok", true);
        m.put("rating", rating);
        return m;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "50") int limit,
                                          @RequestParam(required = false) Integer rating) {
        return chat.listFeedback(Math.min(Math.max(limit, 1), 200), rating);
    }
}
