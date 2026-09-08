package com.payroll.demo.service;

import com.payroll.demo.config.HrmsProperties;
import com.payroll.demo.dto.HrmsDtos.HrmsPage;
import com.payroll.demo.dto.HrmsDtos.HrmsPayslip;
import com.payroll.demo.dto.HrmsDtos.HrmsWorker;
import com.payroll.demo.hrms.HrmsApiException;
import com.payroll.demo.hrms.HrmsClient;
import com.payroll.demo.hrms.HrmsRetryExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Walks a paginated HRMS collection, retrying each page.
 *
 * <p>One loop, shared by the workers and payslips feeds: request a page, retry it on a
 * retryable failure, count the attempt, stop when the feed says there is no more, and
 * refuse to loop past {@code max-pages} if it never says so.
 *
 * <p>The whole collection is returned rather than streamed page by page, because the
 * caller validates the entire feed before opening a transaction — see
 * {@link BulkPayrollService}.
 */
@Component
public class HrmsPageReader {

    private static final Logger log = LoggerFactory.getLogger(HrmsPageReader.class);

    private final HrmsClient client;
    private final HrmsRetryExecutor retry;
    private final HrmsProperties properties;

    public HrmsPageReader(HrmsClient client, HrmsRetryExecutor retry, HrmsProperties properties) {
        this.client = client;
        this.retry = retry;
        this.properties = properties;
    }

    /**
     * Reads every page of workers.
     *
     * @param attemptCounter notified once per HTTP attempt, retries included
     * @throws HrmsApiException if a page cannot be read after its retries
     */
    public List<HrmsWorker> collectWorkers(LocalDate updatedSince, Runnable attemptCounter) {
        return readAll("/workers",
                (page, size) -> client.fetchWorkers(page, size, updatedSince),
                attemptCounter);
    }

    /** Reads every page of payslips for a period. */
    public List<HrmsPayslip> collectPayslips(LocalDate periodStart, LocalDate periodEnd,
                                             Runnable attemptCounter) {
        return readAll("/payslips",
                (page, size) -> client.fetchPayslips(periodStart, periodEnd, page, size),
                attemptCounter);
    }

    private <T> List<T> readAll(String endpoint,
                                PageSource<T> source,
                                Runnable attemptCounter) {
        List<T> collected = new ArrayList<>();
        int size = properties.getPageSize();
        int maxPages = properties.getMaxPages();

        for (int page = 0; page < maxPages; page++) {
            final int current = page;
            HrmsPage<T> result = retry.execute("GET " + endpoint + " page " + current, () -> {
                attemptCounter.run();
                return source.fetch(current, size);
            });

            collected.addAll(result.safeItems());

            if (!result.moreAvailable(size)) {
                return collected;
            }
        }
        log.warn("Stopped paging {} at the {}-page cap; the feed may be larger than expected "
                + "or hasMore may be wrong upstream", endpoint, maxPages);
        return collected;
    }

    /** One page request, parameterised by the endpoint being read. */
    @FunctionalInterface
    private interface PageSource<T> {
        HrmsPage<T> fetch(int page, int size) throws HrmsApiException;
    }
}
