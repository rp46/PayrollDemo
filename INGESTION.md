# HRMS payroll ingestion

Pulls workers and payslips from an external HRMS REST API (Workday-shaped) and stores
them in the `payroll` Postgres database.

## Endpoints

| Method | Path                       | Purpose                                                |
|--------|----------------------------|--------------------------------------------------------|
| POST   | `/api/payroll/bulk-upsert` | Trigger the sync; one transaction, commit or roll back |

```bash
curl -X POST http://localhost:8080/api/payroll/bulk-upsert \
  -H 'Content-Type: application/json' \
  -d '{"periodStart":"2026-08-01","periodEnd":"2026-08-31"}'
```

All body fields are optional. Omitting `periodStart`/`periodEnd` syncs workers only.
`updatedSince` is passed through to the HRMS as an incremental-sync watermark.
`strict: true` aborts before writing anything if any record fails validation; the default
commits the valid records and reports the rejections.

A run that fails upstream still returns 200 with the run record — the call to *this*
service succeeded, and `status`, `committed` and `failureKind` say what went wrong. The
only 4xx this API returns is 409, when a run is already in progress.

The response carries every rejected record inline, so a caller sees what was refused
without a second request. Runs are also persisted to `ingestion_runs` / `ingestion_run_errors`
for history, but there is no HTTP endpoint over them — query the tables directly.

## Upstream API expected

```
GET {base-url}/workers?page=&size=&updatedSince=
GET {base-url}/payslips?page=&size=&periodStart=&periodEnd=
```

Both return `{ "items": [...], "page": n, "size": n, "totalItems": n, "hasMore": bool }`.
When `hasMore` is absent, a full page is taken to mean another page follows. Unknown JSON
properties are ignored, so additive upstream changes do not break ingestion.

## Failure handling

Every failure is classified once, in `HrmsClient`, into a `HrmsFailureKind` that carries
whether retrying could help. Retrying is a separate concern in `HrmsRetryExecutor`.

| Upstream condition            | Kind                 | Retried | Effect on the run              |
|-------------------------------|----------------------|---------|--------------------------------|
| 400, 422                      | `BAD_REQUEST`        | no      | `FAILED`, body kept for triage |
| 401, 403                      | `UNAUTHORIZED`       | no      | `FAILED`                       |
| 404, 410                      | `NOT_FOUND`          | no      | `FAILED`                       |
| 409                           | `CONFLICT`           | no      | `FAILED`                       |
| other 4xx                     | `CLIENT_ERROR`       | no      | `FAILED`                       |
| 429                           | `RATE_LIMITED`       | yes     | honours `Retry-After`          |
| 5xx                           | `SERVER_ERROR`       | yes     | `FAILED` once attempts run out |
| 408, connect/read timeout     | `TIMEOUT`            | yes     | `FAILED` once attempts run out |
| refused connection, DNS, reset| `TRANSPORT`          | yes     | `FAILED` once attempts run out |
| 2xx with an unparseable body  | `MALFORMED_RESPONSE` | no      | `FAILED`                       |

Retryable failures back off exponentially with jitter, so a fleet recovering from one
upstream outage does not re-converge into a synchronised burst. A `Retry-After` from the
server overrides the computed delay, capped by `max-retry-after`.

Three levels of failure are kept distinct:

* **Non-retryable API failure** — aborts the run on the first attempt, before the
  transaction opens. Waiting changes nothing, so nothing is waited for.
* **Retryable failure** — retried to `max-attempts`, then aborts the run. The fetch
  happens before any write, so nothing reached the database.
* **Bad individual record** — rejected during transform, recorded in
  `ingestion_run_errors` with the reason, and left out of the batch. The rest still
  commit and the run finishes `PARTIAL`. One malformed payslip does not cost the other
  nine hundred in the same feed — unless `strict` is set, which is what it is for.

Records are validated before they reach the database — required fields, non-negative
money, column widths, `netPay == grossPay - totalDeductions`, and line items summing to
the stated totals. The bias is towards rejecting loudly: a feed that quietly writes a null
salary as zero, or invents a missing pay date, produces wrong money with no trace.

## Idempotency

Writes are upserts on natural keys — `employee_code` for workers, `(employee, pay_period)`
for payslips — applied as one batch in one transaction. Re-running a failed or partial
sync is safe and converges to the same state. Payslip lines are replaced rather than merged, since the HRMS
is authoritative for the breakdown.

## Configuration

`payroll.hrms.*` in `application.properties`; see that file for the full list.

| Property                        | Default | Notes                                       |
|---------------------------------|---------|---------------------------------------------|
| `base-url`                      | —       | `HRMS_BASE_URL`                             |
| `api-token`                     | —       | `HRMS_API_TOKEN`; sent as a bearer token    |
| `connect-timeout`               | `3s`    | explicit — the JDK default is unbounded     |
| `read-timeout`                  | `10s`   | explicit — the JDK default is unbounded     |
| `page-size` / `max-pages`       | 100/500 | `max-pages` caps a broken `hasMore` upstream|
| `retry.max-attempts`            | `4`     | `1` disables retrying                       |
| `retry.initial-backoff`         | `500ms` |                                             |
| `retry.max-backoff`             | `8s`    |                                             |
| `retry.jitter`                  | `0.25`  | ±25% randomisation                          |
| `retry.max-retry-after`         | `60s`   | ceiling on an honoured `Retry-After`        |

Scheduled ingestion is off by default — `payroll.ingestion.scheduled.enabled=true` plus a
`cron` enables a nightly bulk upsert of the current calendar month, recorded with a
`SCHEDULED` trigger. Opt-in on purpose: a job that
starts firing the moment someone runs the app locally is a good way to point a dev machine
at a production HRMS.

## Schema

`V3__ingestion_tracking.sql` adds `ingestion_runs` (status, counts, failure kind and
detail, HTTP attempts) and `ingestion_run_errors` (per-record rejections), plus a
`last_synced_at` column on `employees` and `payslips`.

These tables are write-only from the application's side. To read the history:

```sql
SELECT id, status, trigger_type, failure_kind, employees_written, payslips_written,
       records_rejected, started_at
  FROM ingestion_runs ORDER BY started_at DESC LIMIT 20;

SELECT record_type, external_id, reason
  FROM ingestion_run_errors WHERE run_id = :id;
```

## Concurrency

`SingleFlightGuard` serialises runs within one instance, so a scheduled sync and a manual
trigger cannot race to create the same department or pay period; a second concurrent
request gets 409. **This is single-instance only.** Running more than one replica needs a
shared lock — a Postgres advisory lock held for the duration of the run is the natural
fit, and `SingleFlightGuard` is the one place that would change.

## The three phases

1. **Fetch** — every page pulled over HTTP by `HrmsPageReader`, with retries. No
   transaction is open: holding a database connection across network I/O ties up the pool
   for as long as the slowest upstream response, and a retry storm would exhaust it.
2. **Transform** — records validated by `HrmsRecordMapper` and mapped onto `PayrollRows`.
   Pure, no I/O. Bad records are rejected here with a reason.
3. **Write** — one `applyBatch` call. Everything commits or everything rolls back.

The trade this makes: nothing is written until the entire feed has been fetched and
validated. That is the cost of atomicity.

## Where the transaction lives, and why

`@Transactional` sits on `BulkPayrollRepository.applyBatch`, not on the calling service.
Spring proxies do not intercept self-invocation, so a service method that fetches over HTTP
and then calls its own transactional method would get no transaction at all — every
statement would autocommit. Putting the boundary on the repository keeps the fetch outside
it and still gives one atomic unit. Spring Data repositories are transactional by default
for the same reason.

Audit rows are written by `IngestionRunRecorder` in `REQUIRES_NEW`, so the record of a
failed run survives the rollback of the run's own data.

## Statements

Ordered so foreign keys resolve: departments and pay periods, then employees, then
payslips, then lines. Each is an idempotent `INSERT ... ON CONFLICT` on a natural key,
batched in chunks of 500.

Pay periods use `ON CONFLICT DO NOTHING` rather than `DO UPDATE`: a payslip feed describes
payslips, not the lifecycle of a period that already exists, and overwriting a `CLOSED`
period back to `OPEN` would be wrong. Payslip lines are deleted and re-inserted rather than
merged, since the HRMS is authoritative for the breakdown and a merge would strand a
component it removed.

Two reads run before the write, outside the transaction: which pay periods already exist (a
`payDate` is only mandatory when one has to be created) and which employee codes already
exist (so a payslip for an unknown worker is a clean rejection rather than a not-null
violation that would take the whole batch down).

---

# Package layout

```
com.payroll.demo
├── controller/   triggers and query endpoints only - no fetching, transform or SQL
├── service/      fetch, validate, transform to the payroll schema
├── repository/   the only tier that touches our database; owns the transaction
├── domain/       JPA entities and enums
├── dto/          wire shapes (HRMS in, API out) and the row shapes between tiers
├── config/       HRMS client, properties, Clock
├── exception/    application exceptions, mapped to HTTP status
├── hrms/         the external HRMS API adapter
└── util/         logic shared by both ingestion paths
```

`hrms/` is deliberately not `repository/`. "Repository" here means our own database, as
the brief specifies; the HRMS client is an outbound HTTP adapter with its own failure
vocabulary, and folding it into the persistence tier would blur the one boundary that
matters most in this system.

## Shared logic

| Extracted to | Was duplicated in | What it is |
|---|---|---|
| `service/HrmsPageReader` | 4 near-identical paging loops | request page, retry, count attempt, stop on `hasMore` or the page cap |
| `service/HrmsRecordMapper` | 2 validation sites | validation and status/type mapping |
| `util/MoneyUtils` | scattered `BigDecimal` handling | scale, precision and value-comparison rules for money |
| `util/TextUtils` | 5 classes | null-safe strip, blank-to-null, truncate, key folding |
| `util/FailureDetails` | 2 identical 3-branch catches | classify what aborted a run into kind + detail |
| `util/SingleFlightGuard` | 2 copies of the same lock | refuse a concurrent run, and hold the multi-instance caveat |
| `dto/BulkWriteCounts` | 2 identical count records | rows written per table |

## Money

Every amount is `BigDecimal` end to end - HRMS JSON, DTOs, rows, entities, `NUMERIC(14,2)`.
Jackson parses straight into `BigDecimal`, so no value ever passes through a `double`.

`MoneyUtils` enforces the two rules that make that worth anything:

* **No silent rounding.** `normalize` scales to 2 places with `RoundingMode.UNNECESSARY`.
  An amount with sub-cent precision is rejected as a record, with the reason recorded,
  rather than being quietly rounded by Postgres on insert.
* **No `equals` on amounts.** `100.0` and `100.00` are the same money but not `equals`,
  so `sameValue` compares by value. Every balance check uses it.

Amounts too wide for `NUMERIC(14,2)` are also rejected up front, so one oversized figure
cannot take down an entire bulk transaction with a numeric overflow at insert time.
