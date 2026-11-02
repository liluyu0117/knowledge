package kbapp.web;

import kbapp.llm.AskEvent;
import kbapp.llm.ChatService;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class AskController {

    private final ChatService chat;

    public AskController(ChatService chat) {
        this.chat = chat;
    }

    public record AskBody(String question, String folder, String sessionId, String provider, String agentId) {}

    @PostMapping(value = "/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<AskEvent>> ask(@RequestBody AskBody body) {
        Flux<AskEvent> events = chat.ask(body.question(), body.folder(), body.sessionId(),
                body.provider(), body.agentId());
        return events.map(e -> {
            String type = e instanceof AskEvent.Delta ? "delta"
                    : e instanceof AskEvent.Done ? "done" : "error";
            return ServerSentEvent.<AskEvent>builder(e).event(type).build();
        });
    }
}
