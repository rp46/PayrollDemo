# HRMS payroll ingestion

Pulls workers and payslips from an external HRMS REST API (Workday-shaped) and stores
them in the `payroll` Postgres database.

## Endpoints

| Method | Path                       | Purpose                                            |
|--------|----------------------------|----------------------------------------------------|
| POST   | `/api/ingestion/runs`      | Run a sync now; returns the run record (201)       |
| GET    | `/api/ingestion/runs`      | Recent runs, newest first (`?limit=`, max 100)     |
| GET    | `/api/ingestion/runs/{id}` | One run, including every rejected record           |

```bash
curl -X POST http://localhost:8080/api/ingestion/runs \
  -H 'Content-Type: application/json' \
  -d '{"periodStart":"2026-08-01","periodEnd":"2026-08-31"}'
```

All three body fields are optional. Omitting `periodStart`/`periodEnd` syncs workers only.
`updatedSince` is passed through to the HRMS as an incremental-sync watermark.

A run that fails upstream still returns 200/201 with the run record — the call to *this*
service succeeded, and `status` plus `failureKind` say what went wrong. The only 4xx this
API returns are 409 (a run is already in progress) and 404 (no such run).

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

* **Non-retryable API failure** — aborts the run on the first attempt. Waiting changes
  nothing, so nothing is waited for.
* **Retryable failure** — retried to `max-attempts`, then aborts the run. Records written
  before the abort stay written; the next run re-upserts them.
* **Bad individual record** — rejected, recorded in `ingestion_run_errors` with the reason,
  and skipped. The run continues and finishes `PARTIAL`. One malformed payslip does not
  cost the other nine hundred in the same feed.

Records are validated before they reach the database — required fields, non-negative
money, column widths, `netPay == grossPay - totalDeductions`, and line items summing to
the stated totals. The bias is towards rejecting loudly: a feed that quietly writes a null
salary as zero, or invents a missing pay date, produces wrong money with no trace.

## Idempotency

Writes are upserts on natural keys — `employee_code` for workers, `(employee, pay_period)`
for payslips — each in its own transaction. Re-running a failed or partial sync is safe and
converges to the same state. Payslip lines are replaced rather than merged, since the HRMS
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
`cron` enables a nightly sync of the current calendar month. Opt-in on purpose: a job that
starts firing the moment someone runs the app locally is a good way to point a dev machine
at a production HRMS.

## Schema

`V3__ingestion_tracking.sql` adds `ingestion_runs` (status, counts, failure kind and
detail, HTTP attempts) and `ingestion_run_errors` (per-record rejections), plus a
`last_synced_at` column on `employees` and `payslips`.

## Concurrency

A `ReentrantLock` serialises runs within one instance, so a scheduled sync and a manual
trigger cannot race to create the same department or pay period; a second concurrent
request gets 409. **This is single-instance only.** Running more than one replica needs a
shared lock — a Postgres advisory lock held for the duration of the run is the natural fit.
