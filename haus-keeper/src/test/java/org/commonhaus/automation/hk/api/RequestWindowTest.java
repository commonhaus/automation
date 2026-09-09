package org.commonhaus.automation.hk.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

public class RequestWindowTest {

    @Test
    void admitsUpToLimitThenRejects() {
        RequestWindow window = new RequestWindow(3, 60_000);
        long now = 1_000_000L;

        assertThat(window.tryAcquire(now)).isTrue();
        assertThat(window.tryAcquire(now)).isTrue();
        assertThat(window.tryAcquire(now)).isTrue();
        assertThat(window.tryAcquire(now)).isFalse();
    }

    @Test
    void readmitsAfterWindowAges() {
        RequestWindow window = new RequestWindow(1, 60_000);
        long now = 1_000_000L;

        assertThat(window.tryAcquire(now)).isTrue();
        assertThat(window.tryAcquire(now + 30_000)).isFalse();
        assertThat(window.tryAcquire(now + 60_001)).isTrue();
    }

    @Test
    void independentInstancesDoNotShareState() {
        RequestWindow a = new RequestWindow(1, 60_000);
        RequestWindow b = new RequestWindow(5, 60_000);
        long now = 1_000_000L;

        assertThat(a.tryAcquire(now)).isTrue();
        assertThat(a.tryAcquire(now)).isFalse();

        assertThat(b.tryAcquire(now)).isTrue();
        assertThat(b.tryAcquire(now)).isTrue();
    }

    @Test
    void concurrentCallsNeverExceedLimit() throws InterruptedException {
        int limit = 10;
        RequestWindow window = new RequestWindow(limit, 60_000);
        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger admitted = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (window.tryAcquire(System.currentTimeMillis())) {
                    admitted.incrementAndGet();
                }
            });
        }

        ready.await();
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(admitted.get()).isEqualTo(limit);
    }
}
