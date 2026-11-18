package com.mobstands.stand;

import com.mobstands.MobStands;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Tiny server-side delayed task queue (used for barrages, delayed explosions, cobweb cleanup...). */
public final class TickScheduler {
    private record Task(long at, Runnable run) {}

    private static final List<Task> TASKS = new ArrayList<>();
    private static long now;

    private TickScheduler() {}

    public static void later(int delayTicks, Runnable run) {
        TASKS.add(new Task(now + Math.max(1, delayTicks), run));
    }

    public static void tick() {
        now++;
        if (TASKS.isEmpty()) return;
        List<Task> due = new ArrayList<>();
        Iterator<Task> it = TASKS.iterator();
        while (it.hasNext()) {
            Task t = it.next();
            if (t.at() <= now) {
                due.add(t);
                it.remove();
            }
        }
        for (Task t : due) {
            try {
                t.run().run();
            } catch (RuntimeException ex) {
                MobStands.LOGGER.error("Scheduled Stand task failed", ex);
            }
        }
    }

    public static void clear() {
        TASKS.clear();
    }
}
