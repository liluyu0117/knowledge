package kbapp.web;

import kbapp.chat.ChatStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final ChatStore chat;

    public SessionController(ChatStore chat) {
        this.chat = chat;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return chat.listSessions();
    }

    @GetMapping("/{id}")
    public List<Map<String, Object>> messages(@PathVariable String id) {
        return chat.getMessages(id);
    }

    @PostMapping
    public Map<String, Object> create(@RequestParam(defaultValue = "新会话") String title) {
        ChatStore.Session s = chat.createSession(title);
        return Map.of("id", s.id(), "title", s.title(), "createdAt", s.createdAt());
    }

    /** 归档 / 取消归档。 */
    @PostMapping("/{id}/archive")
    public Map<String, Object> archive(@PathVariable String id,
                                       @RequestParam(defaultValue = "true") boolean value) {
        chat.setArchived(id, value);
        return Map.of("ok", true);
    }

    /** 重命名会话。 */
    @PostMapping("/{id}/rename")
    public Map<String, Object> rename(@PathVariable String id, @RequestParam String title) {
        chat.renameSession(id, title.isBlank() ? "未命名会话" : title.trim());
        return Map.of("ok", true);
    }

    /** 删除会话及其全部消息。 */
    @org.springframework.web.bind.annotation.DeleteMapping("/{id}")
    public Map<String, Object> remove(@PathVariable String id) {
        chat.removeSession(id);
        return Map.of("ok", true);
    }
}
