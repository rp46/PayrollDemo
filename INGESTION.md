# HRMS payroll ingestion

Pulls workers and payslips from an external HRMS REST API (Workday-shaped) and stores
them in the `payroll` Postgres database.

## Endpoints

| Method | Path                                    | Purpose                                                |
|--------|-----------------------------------------|--------------------------------------------------------|
| POST   | `/api/payroll/bulk-upsert`              | Trigger the sync; one transaction, commit or roll back |
| GET    | `/api/employees/{code}/pay-details`     | Compensation plus totals over the payslips in scope    |
| GET    | `/api/employees/{code}/payslips`        | That employee's payslips, newest period first          |
| GET    | `/api/employees/{code}/payslips/latest` | Their most recent payslip                              |
| GET    | `/api/payslips/{id}`                    | One payslip by id                                      |
| GET    | `/api/pay-periods/{id}/payslips`        | The payroll register for one run                       |

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

## Reading pay data

`pay-details` and the payslip listing take the same optional filters:

| Param    | Meaning                                                     |
|----------|-------------------------------------------------------------|
| `from`   | include payslips whose period **ends** on or after this date |
| `to`     | include payslips whose period **starts** on or before it     |
| `status` | `DRAFT`, `APPROVED` or `PAID`; omit for all                  |

The window is an overlap test, not containment, so a payslip counts if any part of its
period falls inside it.

```bash
# everything
curl http://localhost:8080/api/employees/E-1001/pay-details

# a financial year, counting only what was actually paid
curl 'http://localhost:8080/api/employees/E-1001/pay-details?from=2026-04-01&to=2027-03-31&status=PAID'
```

`pay-details` returns standing compensation (base salary, department, status, hire and
termination dates) alongside a `summary` of the payslips in scope, a `componentTotals`
breakdown by earning and deduction code, and the full `latestPayslip`. The filters are
echoed back as `scopeFrom` / `scopeTo` / `scopeStatus`, so a total can never be read
without knowing what went into it.

`averageNetPerPayslip` is `null`, not `0`, when nothing is in scope — the mean of no
payslips is undefined. Totals are `0.00`.

An unknown employee, payslip or period is a 404. An employee who exists but has no
payslips gets an empty list from the listing, and a 404 from `/payslips/latest` — those
are different situations and the caller should be able to tell them apart.

Aggregates are computed in Java, not by a SQL `GROUP BY`: the scope is one employee's
payslips, the line items are loaded anyway for the breakdown, and doing the arithmetic in
`MoneyUtils` keeps it under the same scale and comparison rules as every other amount here.

### One trap worth knowing about

The filtered query is written with `coalesce`, not the usual `(:param is null or ...)`:

```sql
and p.payPeriod.periodEnd >= coalesce(:from, p.payPeriod.periodEnd)
```

Postgres cannot infer the type of a parameter that only ever appears next to `NULL`, and
fails with *"could not determine data type of parameter"* — but only once pgjdbc promotes
the statement to a server-side prepared one, which happens after about five executions.
It therefore works in a quick manual test and starts failing in production.
`PayslipQueryServiceTest` drives the query well past that threshold on purpose.

## Errors and logging

Every failed request returns the same shape, so a client can parse a failure without
knowing which endpoint produced it:

```json
{
  "timestamp": "2026-09-09T03:54:42.573Z",
  "status": 400,
  "error": "BAD_REQUEST",
  "message": "'to' (2026-01-01) must not be before 'from' (2027-01-01)",
  "path": "/api/employees/W-5001/payslips"
}
```

`error` is the HTTP status name, derived from `status` by one rule for the whole API so
the two can never disagree. Branch on it rather than on the wording of `message`, which
carries the specifics.

### How a failure becomes a status

Services validate their arguments up front (`Validate`) and run their work through
`ServiceGuard`, which is the only place the translation happens:

| Inside the service                                 | Out of the API | `error`               |
|----------------------------------------------------|----------------|-----------------------|
| Missing or blank argument, inverted range          | 400            | `BAD_REQUEST`         |
| Unparseable query parameter or path variable       | 400            | `BAD_REQUEST`         |
| `NullPointerException` and the bad-argument family | 400            | `BAD_REQUEST`         |
| No such employee / payslip / pay period            | 404            | `NOT_FOUND`           |
| A run is already in progress                       | 409            | `CONFLICT`            |
| Database unreachable                               | 503            | `SERVICE_UNAVAILABLE` |

### One exception class

All of the above is raised through a single `ApiException` with four factory methods —
`badRequest`, `notFound`, `conflict`, `unavailable` — which replaced seven classes that
existed only to pair a message with a status:

```java
throw ApiException.notFound("No payslip with id " + payslipId);
throw ApiException.badRequest("payslip id must be a positive id, but was " + id);
```

The only other exception in the package is `IngestionRecordException`, kept because it is a
different thing: an internal signal for one bad record inside a feed, carrying the record
type and external id for the audit row, which never becomes an HTTP response.

`badRequest` covers almost everything. The other three survive because folding them into
400 would throw away something the caller needs: a 404 says the request was well formed and
simply named something absent, a 409 says nothing is wrong and the same call will work
shortly, and a 503 says the fault is ours rather than theirs.

The bad-argument row is worth being explicit about. A `NullPointerException` is a defect in
this application, not a mistake by the caller, and 500 would normally be the more accurate
signal. It is reported as 400 because a caller should never be shown a 5xx for a request
that can be corrected — but the full stack trace is logged at ERROR every time, so a tidy
400 on the wire never means an operator loses the detail. A genuinely unrecognised failure
is still a 500; disguising that one would send whoever is reading it to look in the wrong
place.

A database outage is 503 rather than 400 for the same reason in reverse: nothing about the
request was wrong, and retrying later is the correct response.

### Logs

`slf4j` throughout. Every service method logs `START` and `DONE` with its arguments and
elapsed time, so one request is traceable end to end:

```
INFO  PayslipQueryService : START  build pay details for W-5001 (from=null, to=null, status=PAID)
INFO  PayslipQueryService : Summarising 1 payslip(s) for W-5001
INFO  PayslipQueryService : DONE   build pay details for W-5001 (from=null, to=null, status=PAID) in 75 ms
```

Levels are used consistently: INFO for what happened, WARN for a caller-caused rejection or
a record the feed got wrong, ERROR for anything that needs an operator. Per-record
validation logs at DEBUG — a feed of ten thousand rows would otherwise emit ten thousand
INFO lines, and each rejection is already logged once by the caller and stored in
`ingestion_run_errors`.

## Retries

Every HRMS call goes through `HrmsRetryExecutor`, which makes **at least three retries**
(four attempts) on any retryable failure. The floor is enforced, not merely defaulted:

```
WARN  HrmsRetryExecutor : payroll.hrms.retry.max-attempts is 1, below the floor of 4; using 4.
                          The HRMS drops requests, so fewer than 3 retries cannot tell a blip from an outage.
INFO  HrmsRetryExecutor : HRMS retry policy: 4 attempts (3 retries), backoff PT0.1S..PT0.3S x2.0 with 25% jitter
```

Configuring `max-attempts` below 4 raises it and warns; above 4 is honoured as given.
Retries are still confined to failures where waiting can help (5xx, 429, timeouts,
transport). A 400 or a 401 fails on the first attempt — re-sending an identical rejected
request three more times only delays the report.

When the retries are exhausted, the give-up is logged at ERROR twice on purpose: once by
the executor with the cause, and once by `HrmsPageReader` with the read that was in
progress and how much of it had already succeeded.

```
WARN  HrmsRetryExecutor : GET /workers page 0 failed on attempt 1/4 (SERVER_ERROR (HTTP 503) ...), retrying in 1000 ms
...
ERROR HrmsRetryExecutor : GET /workers page 0 FAILED after all 4 attempt(s) (3 retries). Giving up. Cause: ...
ERROR HrmsPageReader    : HRMS read of /workers failed at page 0 after 0 item(s) had been collected: ...
```

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

# Tests and coverage

```bash
./mvnw verify          # runs the suite, writes the report, enforces the floors
```

Report: `target/site/jacoco/index.html`.

| Counter     | Covered   |
|-------------|-----------|
| Instruction | 98.99%    |
| Line        | 98.64%    |
| Branch      | 91.94%    |
| Method      | 99.00%    |
| Complexity  | 93.31%    |

370 tests across 71 measured classes.

## The floors are enforced

`jacoco:check` runs in `verify` and **fails the build** below 90% line or 85% branch. They
are floors, not targets: set under what the suite actually achieves, so a real regression
breaks the build while an ordinary refactor does not. Confirmed to bite:

```
[WARNING] Rule violated for bundle demo: lines covered ratio is 0.986, but expected minimum is 0.999
[INFO] BUILD FAILURE
```

Only `DemoApplication` is excluded — a `main` method with nothing to assert. The DTO
records are measured rather than excluded, because several of them carry real logic
(`HrmsPage.moreAvailable`, `PayrollBatch.totalLines`, the `BulkUpsertResult` factories) and
excluding a package to flatter a number defeats the point of measuring.

JaCoCo 0.8.15 is pinned because anything older cannot read Java 26 class files.

## What the suite covers

* **Unit** — every service, the mapper, the retry policy, the utils, the DTO logic, the
  entity association helpers. Mocked collaborators, no I/O.
* **Web** — `@WebMvcTest` per controller plus the global handler: parameter binding, status
  mapping, error bodies. The handler branches no current route can reach are exercised
  directly, since they become reachable the moment an endpoint is added.
* **Database** — `BulkPayrollRepositoryTest` and `PayslipQueryServiceTest` run against real
  Postgres. Rollback, and the SQL that only misbehaves against a real driver, cannot be
  demonstrated any other way. Both namespace their rows and delete them afterwards.
* **Socket** — `HrmsClientTest` runs against a real HTTP server for the failures that do not
  reproduce against a mocked stack: read timeouts, refused connections, truncated bodies.

## Two bugs the tests found

Worth recording, because both passed a casual look and neither was hypothetical:

* `(:param is null or ...)` in the payslip query. Postgres cannot infer the type of a
  parameter that only ever sits beside `NULL`, and fails once the driver promotes the
  statement to a server-side prepared one — after roughly five executions, not the first.
  It would have passed a manual smoke test and started failing in production. Rewritten
  with `coalesce`; a test now drives the query well past that threshold.
* `replaceAll("\s+", " ")` written with a single backslash. Since Java 15, `"\s"` in a
  string literal is the escape for a space, so it compiled cleanly and collapsed runs of
  spaces while leaving newlines and tabs intact — quietly defeating the one-line-for-logs
  intent for HRMS error bodies.

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
