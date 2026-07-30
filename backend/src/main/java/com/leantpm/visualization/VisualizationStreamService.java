package com.leantpm.visualization;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class VisualizationStreamService {
    private final Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(error -> emitters.remove(emitter));
        send(emitter, "connected");
        return emitter;
    }

    @Scheduled(fixedDelayString = "${leantpm.visualization.stream-interval-ms:15000}")
    public void broadcastRefresh() {
        for (SseEmitter emitter : emitters) {
            send(emitter, "refresh");
        }
    }

    private void send(SseEmitter emitter, String name) {
        try {
            emitter.send(SseEmitter.event()
                    .name(name)
                    .data(Instant.now().toString())
                    .reconnectTime(3000));
        } catch (IOException | IllegalStateException exception) {
            emitters.remove(emitter);
            emitter.complete();
        }
    }
}
