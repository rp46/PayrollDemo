package com.payroll.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Connection, paging and retry settings for the external HRMS API.
 *
 * <p>Bound from {@code payroll.hrms.*}.
 */
@ConfigurationProperties(prefix = "payroll.hrms")
public class HrmsProperties {

    /** Root of the HRMS REST API, e.g. {@code https://wd2-impl-services1.workday.com/ccx/api/v1/acme}. */
    private String baseUrl = "http://localhost:8081/hrms/v1";

    /** Bearer token. Supply via environment, never commit a real one. */
    private String apiToken = "";

    /** Tenant / company identifier sent as a header, if the provider needs one. */
    private String tenant = "";

    /** Time allowed to establish the TCP+TLS connection. */
    private Duration connectTimeout = Duration.ofSeconds(3);

    /** Time allowed for the response after the request is sent. */
    private Duration readTimeout = Duration.ofSeconds(10);

    /** Rows requested per page. */
    private int pageSize = 100;

    /** Hard stop on pagination, so a broken {@code hasMore} upstream cannot loop forever. */
    private int maxPages = 500;

    private final Retry retry = new Retry();

    /** Backoff policy applied to retryable failures only. */
    public static class Retry {

        /**
         * Fewest attempts allowed: the first call plus {@value #MIN_RETRIES} retries.
         *
         * <p>The HRMS is slow and drops requests, so a single attempt is never enough to
         * distinguish a blip from an outage. Configuring below this is treated as a mistake
         * and raised, with a warning, rather than silently honoured.
         */
        public static final int MIN_RETRIES = 3;
        public static final int MIN_ATTEMPTS = MIN_RETRIES + 1;

        /** Total attempts including the first. Clamped up to {@link #MIN_ATTEMPTS}. */
        private int maxAttempts = MIN_ATTEMPTS;

        /** Delay before the second attempt. */
        private Duration initialBackoff = Duration.ofMillis(500);

        /** Ceiling for the computed exponential delay. */
        private Duration maxBackoff = Duration.ofSeconds(8);

        /** Growth factor between attempts. */
        private double multiplier = 2.0;

        /** Randomisation of each delay, as a fraction (0.25 = ±25%), to avoid a retry stampede. */
        private double jitter = 0.25;

        /** Ceiling on an honoured {@code Retry-After}; a hostile value cannot park a thread for an hour. */
        private Duration maxRetryAfter = Duration.ofSeconds(60);

        public int getMaxAttempts() {
            return maxAttempts;
        }

        /** Attempts actually used, never fewer than {@link #MIN_ATTEMPTS}. */
        public int effectiveMaxAttempts() {
            return Math.max(MIN_ATTEMPTS, maxAttempts);
        }

        /** True when the configured value was below the floor and had to be raised. */
        public boolean isBelowMinimum() {
            return maxAttempts < MIN_ATTEMPTS;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Duration getInitialBackoff() {
            return initialBackoff;
        }

        public void setInitialBackoff(Duration initialBackoff) {
            this.initialBackoff = initialBackoff;
        }

        public Duration getMaxBackoff() {
            return maxBackoff;
        }

        public void setMaxBackoff(Duration maxBackoff) {
            this.maxBackoff = maxBackoff;
        }

        public double getMultiplier() {
            return multiplier;
        }

        public void setMultiplier(double multiplier) {
            this.multiplier = multiplier;
        }

        public double getJitter() {
            return jitter;
        }

        public void setJitter(double jitter) {
            this.jitter = jitter;
        }

        public Duration getMaxRetryAfter() {
            return maxRetryAfter;
        }

        public void setMaxRetryAfter(Duration maxRetryAfter) {
            this.maxRetryAfter = maxRetryAfter;
        }
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiToken() {
        return apiToken;
    }

    public void setApiToken(String apiToken) {
        this.apiToken = apiToken;
    }

    public String getTenant() {
        return tenant;
    }

    public void setTenant(String tenant) {
        this.tenant = tenant;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public int getMaxPages() {
        return maxPages;
    }

    public void setMaxPages(int maxPages) {
        this.maxPages = maxPages;
    }

    public Retry getRetry() {
        return retry;
    }
}
