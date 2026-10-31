package io.github.maurdekye.reelsaver;

import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Tiny in-process status feed so the activity can show what the service is doing. */
final class StatusBus {

    interface Listener {
        void onStatus(String message);
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile String latest = "";

    static void register(Listener listener) {
        LISTENERS.add(listener);
        if (!latest.isEmpty()) {
            String snapshot = latest;
            MAIN.post(() -> listener.onStatus(snapshot));
        }
    }

    static void unregister(Listener listener) {
        LISTENERS.remove(listener);
    }

    static void post(String message) {
        latest = message;
        for (Listener listener : LISTENERS) {
            MAIN.post(() -> listener.onStatus(message));
        }
    }

    private StatusBus() {}
}
