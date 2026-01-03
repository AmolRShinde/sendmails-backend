package com.sendmail.service;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

import com.sendmail.job.JobStatus;

@Component
public class SseEmitterService {

    private final ConcurrentHashMap<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    public SseEmitter createEmitter(String jobId) {
        SseEmitter emitter = new SseEmitter(0L); // infinite timeout
        emitters.put(jobId, emitter);

        emitter.onCompletion(() -> emitters.remove(jobId));
        emitter.onTimeout(() -> emitters.remove(jobId));
        emitter.onError(e -> emitters.remove(jobId));

        return emitter;
    }

    // ---------------- GENERIC SEND ----------------

    public void sendEvent(String jobId, String eventName, Object data) {
        SseEmitter emitter = emitters.get(jobId);
        if (emitter == null) return;

        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(data));
        } catch (IOException e) {
            emitters.remove(jobId);
        }
    }

    public void send(String jobId, String event, String data) {
        sendEvent(jobId, event, data);
    }

    // ---------------- ROW UPDATES ----------------

    public void sendRow(String jobId, JobStatus.RowStatus rowStatus) {
        sendEvent(jobId, "row", rowStatus);
    }

    // ---------------- CONTROL EVENTS ----------------

    public void sendControl(String jobId, String state) {
        sendEvent(jobId, "control", state);
    }

    // ---------------- COMPLETE / ERROR ----------------

    public void sendError(String jobId, String message) {
        sendEvent(jobId, "error", message);
        complete(jobId);
    }

    public void complete(String jobId) {
        SseEmitter emitter = emitters.remove(jobId);
        if (emitter != null) {
            try {
                emitter.complete();
            } catch (Exception ignored) {}
        }
    }
}
