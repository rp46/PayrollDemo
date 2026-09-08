package com.payroll.demo.service;

import com.payroll.demo.config.HrmsProperties;
import com.payroll.demo.dto.HrmsDtos.HrmsPage;
import com.payroll.demo.dto.HrmsDtos.HrmsWorker;
import com.payroll.demo.hrms.HrmsApiException;
import com.payroll.demo.hrms.HrmsClient;
import com.payroll.demo.hrms.HrmsFailureKind;
import com.payroll.demo.hrms.HrmsRetryExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The pagination loop shared by the workers and payslips feeds.
 */
class HrmsPageReaderTest {

    private HrmsClient client;
    private HrmsProperties properties;
    private HrmsPageReader reader;
    private final AtomicInteger attempts = new AtomicInteger();

    @BeforeEach
    void setUp() {
        client = mock(HrmsClient.class);
        properties = new HrmsProperties();
        properties.setPageSize(2);
        properties.setMaxPages(5);
        properties.getRetry().setMaxAttempts(3);

        reader = new HrmsPageReader(client,
                new HrmsRetryExecutor(properties.getRetry(), delay -> {
                }), properties);
    }

    private static HrmsWorker worker(String id) {
        return new HrmsWorker(id, "A", "B", id + "@example.com",
                LocalDate.of(2020, 1, 1), null, "ACTIVE", "ENG", "Engineering", BigDecimal.TEN);
    }

    private static HrmsPage<HrmsWorker> page(List<HrmsWorker> items, boolean hasMore) {
        return new HrmsPage<>(items, 0, items.size(), (long) items.size(), hasMore);
    }

    @Test
    @DisplayName("pages are followed until the feed says there are no more")
    void followsPagesUntilExhausted() {
        when(client.fetchWorkers(eq(0), anyInt(), isNull()))
                .thenReturn(page(List.of(worker("E-1"), worker("E-2")), true));
        when(client.fetchWorkers(eq(1), anyInt(), isNull()))
                .thenReturn(page(List.of(worker("E-3")), false));

        List<HrmsWorker> collected = reader.collectWorkers(null, attempts::incrementAndGet);

        assertThat(collected).extracting(HrmsWorker::workerId).containsExactly("E-1", "E-2", "E-3");
        assertThat(attempts).hasValue(2);
    }

    @Test
    @DisplayName("items from every page end up in one list, in feed order")
    void concatenatesPagesInOrder() {
        when(client.fetchWorkers(eq(0), anyInt(), isNull()))
                .thenReturn(page(List.of(worker("E-1"), worker("E-2")), true));
        when(client.fetchWorkers(eq(1), anyInt(), isNull()))
                .thenReturn(page(List.of(worker("E-3")), false));

        assertThat(reader.collectWorkers(null, attempts::incrementAndGet))
                .extracting(HrmsWorker::workerId)
                .containsExactly("E-1", "E-2", "E-3");
    }

    @Test
    @DisplayName("a broken hasMore upstream cannot spin past the page cap")
    void stopsAtThePageCap() {
        when(client.fetchWorkers(anyInt(), anyInt(), isNull()))
                .thenReturn(page(List.of(worker("E-1"), worker("E-2")), true));

        List<HrmsWorker> collected = reader.collectWorkers(null, attempts::incrementAndGet);

        verify(client, times(5)).fetchWorkers(anyInt(), anyInt(), isNull());
        assertThat(collected).hasSize(10);
    }

    @Test
    @DisplayName("a retryable failure is absorbed and paging continues")
    void retriesARetryablePage() {
        when(client.fetchWorkers(eq(0), anyInt(), isNull()))
                .thenThrow(new HrmsApiException(HrmsFailureKind.SERVER_ERROR, "HTTP 503"))
                .thenReturn(page(List.of(worker("E-1")), false));

        List<HrmsWorker> collected = reader.collectWorkers(null, attempts::incrementAndGet);

        assertThat(collected).hasSize(1);
        assertThat(attempts).as("the failed attempt is still counted").hasValue(2);
    }

    @Test
    @DisplayName("a permanent failure propagates so the run can be marked failed")
    void permanentFailurePropagates() {
        when(client.fetchWorkers(anyInt(), anyInt(), isNull()))
                .thenThrow(new HrmsApiException(HrmsFailureKind.BAD_REQUEST, "HTTP 400"));

        assertThatThrownBy(() -> reader.collectWorkers(null, attempts::incrementAndGet))
                .isInstanceOf(HrmsApiException.class);

        assertThat(attempts).as("400 is not retried").hasValue(1);
    }

    @Test
    @DisplayName("payslip paging passes the period through on every page")
    void payslipPagingPassesTheFilter() {
        LocalDate start = LocalDate.of(2026, 8, 1);
        LocalDate end = LocalDate.of(2026, 8, 31);
        when(client.fetchPayslips(any(), any(), anyInt(), anyInt()))
                .thenReturn(new HrmsPage<>(List.of(), 0, 0, 0L, false));

        reader.collectPayslips(start, end, attempts::incrementAndGet);

        verify(client).fetchPayslips(eq(start), eq(end), eq(0), eq(2));
    }
}
