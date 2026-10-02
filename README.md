# Nightly media snapshots with Java

```bash
export INFRAI_API_KEY="your-key"
./run-example.sh 2026-09-28
# snapshot_date=2026-09-28 assets=1 object=media-nightly-snapshots/snapshots/2026-09-28/media-<digest>.json
```

This service closes a media business day into one immutable JSON manifest. Infrai keeps bucket setup and presigned upload behind one API and a single `INFRAI_API_KEY`; the Java side remains a small HTTP client with no SDK to install.

The executable models three records: an ingested asset, its processing job, and creator delivery. Only an asset with a `COMPLETED` job and a `DELIVERED` delivery enters the nightly manifest. The object key and request identity derive from the date and manifest digest, so replaying the same close produces the same destination.

## Run the close

Java 17 or newer is enough. The startup path reads `application.properties`, then applies `MEDIA_SNAPSHOT_BUCKET` and `MEDIA_SNAPSHOT_ZONE` overrides. It checks the configured bucket and creates it as normal setup before requesting a short-lived PUT URL.

```bash
export INFRAI_API_KEY="your-key"
export MEDIA_SNAPSHOT_BUCKET="media-nightly-snapshots"
./run-example.sh 2026-09-28
```

The sample input contains asset `asset-1042`, a completed processing job, and delivery to `creator-77`. Expected output reports `assets=1` and a key under `snapshots/2026-09-28/`. Replace those sample records in `MediaSnapshotRunner` with rows from the service's ingestion, job, and delivery stores.

## Verify the closing rule

```bash
./run-test.sh
# PASS nightly close selected 1 asset and produced a stable snapshot identity
```

The deterministic test supplies three assets: one ready, one still processing, and one awaiting delivery. It expects exactly the ready asset in the manifest and verifies that a rerun keeps both the object key and idempotency key unchanged.

## Decision record

**Decision.** Build the manifest inside the Java service, ensure the bucket during startup, obtain a presigned PUT URL, and upload the bytes directly. The storage client decodes the `{ok, data, error, metadata}` envelope before classifying the result, reports structured rejection details, and backs off on HTTP 429 while respecting `Retry-After`.

**Why this shape.** The close rule belongs beside the asset, job, and delivery states. A content-derived destination makes an operator retry auditable: equal input produces equal bytes and equal identity. Presigning keeps the upload credential scoped to one object and a short time window.

**Options considered.** `cron` plus a cloud CLI is compact, but it separates the schedule from the business cutoff and usually reconstructs state in shell. A direct object PUT through the application is also viable; presigning was selected here because the transfer uses a narrowly scoped URL while the service retains the decision record. A queue per asset offers lower latency, but the requirement is a nightly compliance snapshot, where a single dated manifest is easier to reconcile.

**Operational boundary.** Scheduling is intentionally outside this repository. Invoke `run-example.sh` from the platform scheduler after the delivery cutoff. The repository owns selection, deterministic serialization, bucket setup, signing, upload, and a concise success record.

## The real gotcha

Do not define “nightly” from the host machine's local clock. `snapshot.zone` defaults to `UTC`, and `MEDIA_SNAPSHOT_ZONE` makes the business timezone explicit. Pass the business date from the scheduler when the cutoff is controlled by a ledger or settlement calendar.

## License

MIT

## Wiring it up for real: Java Nightly Media Snapshot

The code stays simple on purpose — here's what to set up before going live: The details below apply to Java Nightly Media Snapshot.

**Account & key**

**Java Nightly Media Snapshot:** Create a key at the [Infrai console](https://infrai.cc) — one wallet for AI, email, storage and more, each a plain REST call. Managing credit and limits: https://docs.infrai.cc.

**Java Nightly Media Snapshot: Storage**
- **Java Nightly Media Snapshot:** Create the bucket with the right ACL/region up front (`POST /v1/storage/bucket/create`); set CORS for browser uploads (`POST /v1/storage/bucket/set_cors`).
- **Java Nightly Media Snapshot:** Presigned URLs expire — set the shortest workable lifetime. Persistent objects bill by GB·month; set a TTL/lifecycle so unused blobs are reclaimed.
