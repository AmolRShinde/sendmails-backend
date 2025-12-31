package com.sendmail;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SseEmitterService {

    private final ConcurrentHashMap<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    public SseEmitter createEmitter(String jobId) {
        SseEmitter emitter = new SseEmitter(0L); // no timeout
        emitters.put(jobId, emitter);

        emitter.onCompletion(() -> emitters.remove(jobId));
        emitter.onTimeout(() -> emitters.remove(jobId));
        emitter.onError((ex) -> emitters.remove(jobId));

        return emitter;
    }

    public void sendEvent(String jobId, String eventName, Object data) {
        SseEmitter emitter = emitters.get(jobId);
        if (emitter == null) return;
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException e) {
            emitters.remove(jobId);
        }
    }

    public void complete(String jobId) {
        SseEmitter emitter = emitters.remove(jobId);
        if (emitter != null) {
            try { emitter.complete(); } catch (Exception ignored) {}
        }
    }

    public void sendProgress(String jobId, int progress) {
        SseEmitter emitter = emitters.get(jobId);
        if (emitter != null) {
            try { emitter.send(SseEmitter.event().name("progress").data(progress)); } 
            catch (IOException e) { emitters.remove(jobId); }
        }
    }

    public void sendRow(String jobId, JobStatus.RowStatus rowStatus) {
        SseEmitter emitter = emitters.get(jobId);
        if (emitter != null) {
            try { emitter.send(SseEmitter.event().name("row").data(rowStatus)); } 
            catch (IOException e) { emitters.remove(jobId); }
        }
    }

    public void sendComplete(String jobId) {
        SseEmitter emitter = emitters.get(jobId);
        if (emitter != null) {
            try { emitter.send(SseEmitter.event().name("complete").data("Job complete")); } 
            catch (IOException e) {}
            emitters.remove(jobId);
        }
    }

    public void sendError(String jobId, String message) {
        SseEmitter emitter = emitters.get(jobId);
        if (emitter != null) {
            try { emitter.send(SseEmitter.event().name("error").data(message)); } 
            catch (IOException e) {}
            emitters.remove(jobId);
        }
    }

    public void sendControl(String jobId, String state) {
        send(jobId, "control", state);
    }

    public void send(String jobId, String event, String data) {
        SseEmitter emitter = emitters.get(jobId);
        if (emitter == null) return;

        try {
            emitter.send(SseEmitter.event()
                    .name(event)
                    .data(data));
        } catch (Exception e) {
            emitters.remove(jobId);
        }
    }

}
