package com.payroll.demo.util;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Runs a task only if no other thread is already running it.
 *
 * <p>Keeps a scheduled sync and a manual trigger from racing to create the same department
 * or pay period. Both ingestion services need exactly this, so the lock and the caveat
 * live here rather than being restated in each.
 *
 * <p><b>Single instance only.</b> This guards one JVM. Running more than one replica needs
 * a shared lock; a Postgres advisory lock held for the duration of the run is the natural
 * fit, and this class is the one place that would change.
 */
public final class SingleFlightGuard {

    private final ReentrantLock lock = new ReentrantLock();

    /**
     * @param onBusy supplies the exception to throw when a run is already under way
     * @return whatever {@code task} returned
     */
    public <T> T run(Supplier<? extends RuntimeException> onBusy, Supplier<T> task) {
        if (!lock.tryLock()) {
            throw onBusy.get();
        }
        try {
            return task.get();
        } finally {
            lock.unlock();
        }
    }

    /** Whether a run is currently in flight. */
    public boolean isBusy() {
        return lock.isLocked();
    }
}
