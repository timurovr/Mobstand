package com.mobstands.stand;

import com.mobstands.MobStands;

import java.util.PriorityQueue;

/**
 * Tiny server-side delayed task queue (used for barrages, delayed explosions, cobweb cleanup...).
 * A priority queue ordered by due time: each tick only looks at the tasks that are actually due, no allocations.
 */
public final class TickScheduler {
    private record Task(long at, long seq, Runnable run) {}

    /** Earliest first; tasks due on the same tick run in the order they were scheduled. */
    private static final PriorityQueue<Task> TASKS = new PriorityQueue<>((a, b) -> a.at != b.at ? Long.compare(a.at, b.at) : Long.compare(a.seq, b.seq));
    private static long now;
    private static long seq;

    private TickScheduler() {}

    public static void later(int delayTicks, Runnable run) {
        TASKS.add(new Task(now + Math.max(1, delayTicks), seq++, run));
    }

    public static void tick() {
        now++;
        // Tasks scheduled while running (for a later tick) are always due after now, so they wait their turn.
        while (!TASKS.isEmpty() && TASKS.peek().at() <= now) {
            Task t = TASKS.poll();
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
