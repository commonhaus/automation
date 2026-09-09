package org.commonhaus.automation.hk.api;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Tracks request timestamps for one user against one rate-limit budget,
 * admitting a call only if fewer than {@code limit} calls occurred within
 * the trailing {@code windowMillis}.
 */
public class RequestWindow {

    private final int limit;
    private final long windowMillis;
    private final Deque<Long> timestamps = new ArrayDeque<>();

    public RequestWindow(int limit, long windowMillis) {
        this.limit = limit;
        this.windowMillis = windowMillis;
    }

    public synchronized boolean tryAcquire(long nowMillis) {
        long cutoff = nowMillis - windowMillis;
        while (!timestamps.isEmpty() && timestamps.peekFirst() <= cutoff) {
            timestamps.pollFirst();
        }
        if (timestamps.size() < limit) {
            timestamps.addLast(nowMillis);
            return true;
        }
        return false;
    }
}
