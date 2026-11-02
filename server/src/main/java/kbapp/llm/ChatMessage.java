package kbapp.llm;

/** 一条 LLM 消息。 */
public record ChatMessage(String role, String content) {

    public static ChatMessage system(String c) { return new ChatMessage("system", c); }
    public static ChatMessage user(String c) { return new ChatMessage("user", c); }
    public static ChatMessage assistant(String c) { return new ChatMessage("assistant", c); }
}
