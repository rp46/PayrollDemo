package com.payroll.demo.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SingleFlightGuardTest {

    @Test
    void runsAndReturnsTheResult() {
        SingleFlightGuard guard = new SingleFlightGuard();
        assertThat(guard.run(IllegalStateException::new, () -> "done")).isEqualTo("done");
        assertThat(guard.isBusy()).isFalse();
    }

    @Test
    @DisplayName("the lock is released even when the task throws")
    void releasesOnFailure() {
        SingleFlightGuard guard = new SingleFlightGuard();

        assertThatThrownBy(() -> guard.run(IllegalStateException::new, () -> {
            throw new IllegalArgumentException("boom");
        })).isInstanceOf(IllegalArgumentException.class);

        assertThat(guard.isBusy()).isFalse();
        assertThat(guard.run(IllegalStateException::new, () -> "second run")).isEqualTo("second run");
    }

    @Test
    @DisplayName("a second thread is turned away while the first is still running")
    void refusesConcurrentRuns() throws Exception {
        SingleFlightGuard guard = new SingleFlightGuard();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Thread first = new Thread(() -> guard.run(IllegalStateException::new, () -> {
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "first";
        }));
        first.start();

        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> guard.run(
                    () -> new IllegalStateException("already running"), () -> "second"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("already running");
        } finally {
            release.countDown();
            first.join(5000);
        }
    }
}
