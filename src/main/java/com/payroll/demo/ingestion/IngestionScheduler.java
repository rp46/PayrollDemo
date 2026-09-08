package com.payroll.demo.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Periodic sync, off by default.
 *
 * <p>Enable with {@code payroll.ingestion.scheduled.enabled=true} and set
 * {@code payroll.ingestion.scheduled.cron}. Deliberately opt-in: an ingestion job that
 * starts firing the moment someone runs the app locally is a good way to point a dev
 * machine at a production HRMS.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "payroll.ingestion.scheduled.enabled", havingValue = "true")
public class IngestionScheduler {

    private static final Logger log = LoggerFactory.getLogger(IngestionScheduler.class);

    private final PayrollIngestionService ingestion;
    private final Clock clock;

    public IngestionScheduler(PayrollIngestionService ingestion, Clock clock) {
        this.ingestion = ingestion;
        this.clock = clock;
    }

    /**
     * Syncs the current calendar month.
     *
     * <p>Nothing is rethrown: an unhandled exception from a scheduled method silently kills
     * nothing here, but it does produce a stack trace with no run context. The run record
     * written by {@link PayrollIngestionService} is the better signal, so we log and let the
     * next tick try again.
     */
    @Scheduled(cron = "${payroll.ingestion.scheduled.cron:0 15 2 * * *}",
            zone = "${payroll.ingestion.scheduled.zone:UTC}")
    public void syncCurrentPeriod() {
        YearMonth month = YearMonth.now(clock);
        LocalDate start = month.atDay(1);
        LocalDate end = month.atEndOfMonth();

        try {
            Long runId = ingestion.ingest(IngestionTrigger.SCHEDULED, start, end, null);
            log.info("Scheduled ingestion completed as run {}", runId);
        } catch (IngestionInProgressException ex) {
            log.info("Scheduled ingestion skipped: a run is already in progress");
        } catch (RuntimeException ex) {
            log.error("Scheduled ingestion failed outside the run recorder", ex);
        }
    }
}
