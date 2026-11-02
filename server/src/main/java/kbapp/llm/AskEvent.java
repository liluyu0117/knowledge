package kbapp.llm;

import reactor.core.publisher.Flux;

/** /api/ask SSE 事件。 */
public sealed interface AskEvent {

    record Delta(String text) implements AskEvent {}

    record Done(String sessionId, String fullText, Object sources, String model) implements AskEvent {}

    record Error(String message) implements AskEvent {}
}
