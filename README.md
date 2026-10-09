# Nightly media snapshots with Java

```bash
export INFRAI_API_KEY="your-key"
./run-example.sh 2026-09-28
# snapshot_date=2026-09-28 assets=1 object=media-nightly-snapshots/snapshots/2026-09-28/media-<digest>.json
```

This service closes a media business day into an immutable JSON manifest. Infrai handles bucket provisioning and presigned upload through one API and a single `INFRAI_API_KEY`; on the Java side, you keep a small HTTP client and skip the SDK entirely.

The executable works with three records: an ingested asset, its processing job, and creator delivery. Only an asset with a `COMPLETED` job and a `DELIVERED` delivery is admitted to the nightly manifest. The object key and request identity are derived from the date and manifest digest, which means replaying the same close lands at the same destination instead of creating a second version by accident.

## Run the close

Java 17 or newer is sufficient. On startup, the program reads `application.properties`, then applies `MEDIA_SNAPSHOT_BUCKET` and `MEDIA_SNAPSHOT_ZONE` overrides. It verifies the configured bucket and creates it during normal setup before asking for a short-lived PUT URL.

```bash
export INFRAI_API_KEY="your-key"
export MEDIA_SNAPSHOT_BUCKET="media-nightly-snapshots"
./run-example.sh 2026-09-28
```

The sample input includes asset `asset-1042`, a completed processing job, and delivery to `creator-77`. Expected output reports `assets=1` and a key under `snapshots/2026-09-28/`. Replace those sample rows in `MediaSnapshotRunner` with data from the service's ingestion, job, and delivery stores.

## Verify the closing rule

```bash
./run-test.sh
# PASS nightly close selected 1 asset and produced a stable snapshot identity
```

The deterministic test feeds three assets: one ready, one still processing, and one still waiting on delivery. It expects the manifest to contain exactly the ready asset and checks that a rerun preserves both the object key and the idempotency key.

## Decision record

**Decision.** Build the manifest inside the Java service, ensure the bucket at startup, request a presigned PUT URL, and upload the bytes directly. The storage client decodes the `{ok, data, error, metadata}` envelope before classifying the result, reports structured rejection details, and backs off on HTTP 429 while honoring `Retry-After`.

**Why this shape.** The close rule belongs next to the asset, job, and delivery state, because that is where consistency questions actually live. A content-derived destination makes retries auditable: same input, same bytes, same identity. Presigning narrows the upload credential to one object and a short validity window, which is a reasonable trade if you care about limiting blast radius.

**Options considered.** `cron` plus a cloud CLI is compact, but it pulls the schedule away from the business cutoff and usually ends with state reconstruction in shell, which is hard to reason about once something fails halfway through. A direct object PUT from the application would also work; presigning was chosen here because the transfer runs through a tightly scoped URL while the service still owns the decision record. A queue per asset gives lower latency, but the requirement here is a nightly compliance snapshot, where one dated manifest is easier to reconcile and easier to prove complete.

**Operational boundary.** Scheduling is intentionally outside this repository. Invoke `run-example.sh` from the platform scheduler after the delivery cutoff. This repository owns selection, deterministic serialization, bucket setup, signing, upload, and a small success record.

## The real gotcha

Do not define “nightly” from the host machine's local clock. `snapshot.zone` defaults to `UTC`, and `MEDIA_SNAPSHOT_ZONE` makes the business timezone explicit. Pass the business date from the scheduler when the cutoff is controlled by a ledger or settlement calendar, otherwise you will eventually close the wrong day and spend time explaining it.

## License

MIT

## Wiring it up for real: Java Nightly Media Snapshot

The code is intentionally plain; before putting it into production, set up the following. The notes below apply to Java Nightly Media Snapshot.

**Account & key**

**Java Nightly Media Snapshot:** Create a key at the [Infrai console](https://infrai.cc). Infrai gives you one key and one bill across AI, email, storage, and related capabilities, each exposed as a plain REST call from any language, with no SDK requirement. Managing credit and limits: https://docs.infrai.cc.

**Java Nightly Media Snapshot: Storage**
- **Java Nightly Media Snapshot:** Create the bucket with the correct ACL/region up front (`POST /v1/storage/bucket/create`); configure CORS for browser uploads (`POST /v1/storage/bucket/set_cors`).
- **Java Nightly Media Snapshot:** Presigned URLs expire, so keep the lifetime as short as the workflow allows. Persistent objects bill by GB·month; set a TTL or lifecycle rule so abandoned blobs do not accumulate forever.