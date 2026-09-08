package com.payroll.demo.util;

import com.payroll.demo.exception.ApiException;
import org.slf4j.Logger;
import org.springframework.dao.DataAccessException;

import java.util.function.Supplier;

/**
 * Wraps a service operation with its logging and its error handling.
 *
 * <p>Every public service method runs through here, so all of them log the same way and
 * translate failures the same way. Written once rather than as the same eight-line
 * try/catch in a dozen methods.
 *
 * <p>What comes out is always a {@link ApiException} carrying a status the web layer
 * can use directly. Nothing raw escapes the service tier:
 *
 * <ul>
 *   <li>{@link ApiException} — already meaningful, passed through untouched.</li>
 *   <li>{@link DataAccessException} — the database could not answer, so 503. The request
 *       was fine and retrying later is the right move.</li>
 *   <li>{@link NullPointerException}, {@link IllegalArgumentException} and the rest of the
 *       bad-argument family — turned into a 400 so a malformed call never surfaces as a
 *       5xx. The full stack trace is logged at ERROR, because a caller seeing a tidy 400
 *       must not mean an operator loses the detail.</li>
 * </ul>
 */
public final class ServiceGuard {

    private ServiceGuard() {
    }

    /**
     * Runs {@code work}, logging the operation and normalising any failure.
     *
     * @param operation short description used in every log line, e.g.
     *                  {@code "list payslips for E-1001"}
     */
    public static <T> T call(Logger log, String operation, Supplier<T> work) {
        log.info("START  {}", operation);
        long startedAt = System.nanoTime();

        try {
            T result = work.get();
            log.info("DONE   {} in {} ms", operation, elapsedMs(startedAt));
            return result;

        } catch (ApiException ex) {
            // Expected and already described: a missing employee, a bad date range.
            // Logged at WARN because it is the caller's situation, not a fault here.
            log.warn("REJECT {} after {} ms -> {} {}",
                    operation, elapsedMs(startedAt), ex.getStatus().value(), ex.getMessage());
            throw ex;

        } catch (DataAccessException ex) {
            log.error("FAILED {} after {} ms: the database could not answer",
                    operation, elapsedMs(startedAt), ex);
            throw ApiException.unavailable("The payroll database is not reachable right now; " + operation + " could not be completed. Please retry shortly.", ex);

        } catch (NullPointerException | IllegalArgumentException | IllegalStateException
                 | ClassCastException | IndexOutOfBoundsException | ArithmeticException ex) {
            // A caller should never see a 5xx for one of these, but an operator must still
            // see exactly what happened, hence the stack trace at ERROR.
            log.error("FAILED {} after {} ms: {} - reported to the caller as a bad request",
                    operation, elapsedMs(startedAt), ex.getClass().getSimpleName(), ex);
            throw ApiException.badRequest(
                    "The request could not be processed: " + describe(ex), ex);

        } catch (RuntimeException ex) {
            log.error("FAILED {} after {} ms: unexpected {}",
                    operation, elapsedMs(startedAt), ex.getClass().getSimpleName(), ex);
            throw ex;
        }
    }

    /** Void form, for operations that return nothing. */
    public static void run(Logger log, String operation, Runnable work) {
        call(log, operation, () -> {
            work.run();
            return null;
        });
    }

    /**
     * A caller-safe description.
     *
     * <p>An exception message can carry internals — a SQL fragment, a class name, a file
     * path — so only the type is echoed for the ones that tend to. {@link IllegalArgumentException}
     * is the exception: it is normally thrown with a message written to be read.
     */
    private static String describe(RuntimeException ex) {
        if (ex instanceof IllegalArgumentException && ex.getMessage() != null) {
            return ex.getMessage();
        }
        return switch (ex) {
            case NullPointerException ignored -> "a required value was missing";
            case ArithmeticException ignored -> "an amount could not be represented exactly";
            case ClassCastException ignored -> "a value had an unexpected type";
            case IndexOutOfBoundsException ignored -> "a value was out of range";
            default -> "the request was not valid";
        };
    }

    private static long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }
}
