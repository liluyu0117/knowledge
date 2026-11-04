package kbapp.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.springframework.stereotype.Service;

import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 会话与消息存储：.kb/chat.db（SQLite）。单用户，写操作串行化。 */
@Service
public class ChatStore {

    public record Session(String id, String title, String createdAt, String updatedAt) {}
    public record Message(String id, String sessionId, String role, String content,
                          String sourcesJson, String model, String createdAt) {}

    private final Connection conn;
    private final ObjectMapper om = new ObjectMapper();

    public ChatStore(kbapp.config.AppPaths paths) {
        try {
            Class.forName("org.sqlite.JDBC");
            java.nio.file.Files.createDirectories(paths.dataDir());
            this.conn = DriverManager.getConnection("jdbc:sqlite:" + paths.chatDb());
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS session(
                          id TEXT PRIMARY KEY, title TEXT, created_at TEXT, updated_at TEXT,
                          archived INTEGER DEFAULT 0)""");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS message(
                          id TEXT PRIMARY KEY, session_id TEXT NOT NULL,
                          role TEXT NOT NULL, content TEXT NOT NULL,
                          sources TEXT, model TEXT, created_at TEXT)""");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS feedback(
                          message_id TEXT PRIMARY KEY, session_id TEXT,
                          rating INTEGER, note TEXT, created_at TEXT)""");
                st.execute("CREATE INDEX IF NOT EXISTS idx_msg_session ON message(session_id)");
                // 旧库升级：补列（已存在则忽略）
                for (String ddl : new String[]{
                        "ALTER TABLE session ADD COLUMN archived INTEGER DEFAULT 0",
                        "ALTER TABLE session ADD COLUMN agent_id TEXT"}) {
                    try { st.execute(ddl); } catch (Exception ignore) { /* 列已存在 */ }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("初始化 chat.db 失败", e);
        }
    }

    /** 正在进行流式生成的会话集合（跨请求共享，供会话列表显示「生成中」标记）。 */
    private static final Set<String> ACTIVE_ASKS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static void markAsk(String id, boolean on) {
        if (id == null || id.isBlank()) return;
        if (on) ACTIVE_ASKS.add(id); else ACTIVE_ASKS.remove(id);
    }

    public synchronized Session createSession(String title) { return createSession(title, null); }

    public synchronized Session createSession(String title, String agentId) {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String now = java.time.LocalDateTime.now().withNano(0).toString();
        String base = (title == null || title.isBlank()) ? "新会话" : title.trim();
        String finalTitle = uniquifyTitle(base);
        exec("INSERT INTO session(id,title,created_at,updated_at,agent_id) VALUES(?,?,?,?,?)",
                id, finalTitle, now, now, agentId);
        return new Session(id, finalTitle, now, now);
    }

    /** 同名标题自动追加序号，避免会话列表出现多个一模一样的标题。 */
    private String uniquifyTitle(String base) {
        if (!titleExists(base)) return base;
        for (int n = 2; n < 100; n++) {
            String candidate = base + " (" + n + ")";
            if (!titleExists(candidate)) return candidate;
        }
        return base + " (" + System.currentTimeMillis() % 1000 + ")";
    }

    private boolean titleExists(String title) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM session WHERE title=? LIMIT 1")) {
            ps.setString(1, title);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            return false;
        }
    }

    /** 会话归属的智能体 id；未绑定返回 null。 */
    public synchronized String sessionAgent(String sessionId) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT agent_id FROM session WHERE id=?")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        } catch (Exception e) { /* ignore */ }
        return null;
    }

    /** 绑定会话归属的智能体（旧会话首次用智能体提问时补绑定）。 */
    public synchronized void bindAgent(String sessionId, String agentId) {
        exec("UPDATE session SET agent_id=? WHERE id=?", agentId, sessionId);
    }

    /** 把某个智能体名下的会话整体转给另一个智能体（删除智能体时调用）。 */
    public synchronized int rebindAgent(String fromAgentId, String toAgentId) {
        if (fromAgentId == null || fromAgentId.isBlank()) return 0;
        try (PreparedStatement ps = conn.prepareStatement("UPDATE session SET agent_id=? WHERE agent_id=?")) {
            ps.setString(1, toAgentId);
            ps.setString(2, fromAgentId);
            return ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 把「未绑定」或「绑定了已不存在智能体」的会话改绑到 fallback；返回处理条数。 */
    public synchronized int rebindUnknownAgents(java.util.Set<String> validIds, String fallbackId) {
        if (fallbackId == null || fallbackId.isBlank()) return 0;
        List<String> orphan = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, IFNULL(agent_id,'') FROM session")) {
            while (rs.next()) {
                String a = rs.getString(2);
                if (a.isEmpty() || !validIds.contains(a)) orphan.add(rs.getString(1));
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        for (String id : orphan) exec("UPDATE session SET agent_id=? WHERE id=?", fallbackId, id);
        return orphan.size();
    }

    public synchronized List<Map<String, Object>> listSessions() {        List<Map<String, Object>> out = new ArrayList<>();
        // 活跃会话在前，归档在后，各自按更新时间倒序
        try (ResultSet rs = conn.createStatement().executeQuery(
                "SELECT id,title,created_at,updated_at,IFNULL(archived,0),agent_id FROM session "
                        + "ORDER BY IFNULL(archived,0) ASC, updated_at DESC")) {
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getString(1));
                m.put("title", rs.getString(2));
                m.put("createdAt", rs.getString(3));
                m.put("updatedAt", rs.getString(4));
                m.put("archived", rs.getInt(5) == 1);
                m.put("agentId", rs.getString(6));
                m.put("generating", ACTIVE_ASKS.contains(rs.getString(1)));
                out.add(m);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public synchronized void setArchived(String id, boolean archived) {
        exec("UPDATE session SET archived=? WHERE id=?", archived ? 1 : 0, id);
    }

    public synchronized void renameSession(String id, String title) {
        exec("UPDATE session SET title=? WHERE id=?", title, id);
    }

    public synchronized void removeSession(String id) {
        exec("DELETE FROM message WHERE session_id=?", id);
        exec("DELETE FROM session WHERE id=?", id);
    }

    /** 赞/踩：同一条消息只保留最后一次评价（先删后插）。 */
    public synchronized void setFeedback(String messageId, String sessionId, int rating, String note) {
        exec("DELETE FROM feedback WHERE message_id=?", messageId);
        exec("INSERT INTO feedback(message_id,session_id,rating,note,created_at) VALUES(?,?,?,?,?)",
                messageId, sessionId, rating, note,
                java.time.LocalDateTime.now().withNano(0).toString());
    }

    /** 反馈列表：带上回答正文与会话标题，便于直接看 badcase。 */
    public synchronized List<Map<String, Object>> listFeedback(int limit, Integer rating) {
        List<Map<String, Object>> out = new ArrayList<>();
        String sql = "SELECT f.message_id, f.session_id, f.rating, f.note, f.created_at, "
                + "IFNULL(m.content,''), IFNULL(s.title,'') FROM feedback f "
                + "LEFT JOIN message m ON m.id = f.message_id "
                + "LEFT JOIN session s ON s.id = f.session_id "
                + (rating == null ? "" : "WHERE f.rating = ? ")
                + "ORDER BY f.created_at DESC, f.rowid DESC LIMIT ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            if (rating != null) ps.setInt(i++, rating);
            ps.setInt(i, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("messageId", rs.getString(1));
                    m.put("sessionId", rs.getString(2));
                    m.put("rating", rs.getInt(3));
                    m.put("note", rs.getString(4));
                    m.put("createdAt", rs.getString(5));
                    m.put("content", rs.getString(6));
                    m.put("sessionTitle", rs.getString(7));
                    out.add(m);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public synchronized List<Map<String, Object>> getMessages(String sessionId) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id,role,content,sources,model,created_at FROM message WHERE session_id=? ORDER BY created_at, rowid")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString(1));
                    m.put("role", rs.getString(2));
                    m.put("content", rs.getString(3));
                    m.put("sources", rs.getString(4) == null ? List.of() : om.readValue(rs.getString(4), List.class));
                    m.put("model", rs.getString(5));
                    m.put("createdAt", rs.getString(6));
                    out.add(m);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public synchronized void appendMessage(String sessionId, String role, String content,
                                           Object sources, String model) {
        try {
            String now = java.time.LocalDateTime.now().withNano(0).toString();
            exec("INSERT INTO message(id,session_id,role,content,sources,model,created_at) VALUES(?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                    sessionId, role, content,
                    sources == null ? null : om.writeValueAsString(sources), model, now);
            exec("UPDATE session SET updated_at=? WHERE id=?", now, sessionId);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 最近 N 轮历史，供多轮追问（user/assistant 交替，时间正序）。 */
    public synchronized List<kbapp.llm.ChatMessage> recentMessages(String sessionId, int rounds) {
        List<kbapp.llm.ChatMessage> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT role,content FROM message WHERE session_id=? ORDER BY created_at DESC, rowid DESC LIMIT ?")) {
            ps.setString(1, sessionId);
            ps.setInt(2, rounds * 2);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new kbapp.llm.ChatMessage(rs.getString(1), rs.getString(2)));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        java.util.Collections.reverse(out);
        return out;
    }

    private void exec(String sql, Object... args) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
