package dev.infrai.media;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

public final class NightlyMediaSnapshotTest {
    public static void main(String[] args) {
        NightlyMediaSnapshot service = new NightlyMediaSnapshot();
        List<NightlyMediaSnapshot.Asset> assets = List.of(
                new NightlyMediaSnapshot.Asset("ready", "creator-a", "ingest/ready.ts"),
                new NightlyMediaSnapshot.Asset("processing", "creator-b", "ingest/processing.ts"),
                new NightlyMediaSnapshot.Asset("undelivered", "creator-c", "ingest/undelivered.ts"));
        List<NightlyMediaSnapshot.ProcessingJob> jobs = List.of(
                new NightlyMediaSnapshot.ProcessingJob("ready", "COMPLETED", "processed/ready.mp4"),
                new NightlyMediaSnapshot.ProcessingJob("processing", "RUNNING", "processed/processing.mp4"),
                new NightlyMediaSnapshot.ProcessingJob("undelivered", "COMPLETED", "processed/undelivered.mp4"));
        List<NightlyMediaSnapshot.Delivery> deliveries = List.of(
                new NightlyMediaSnapshot.Delivery("ready", "DELIVERED", "creator-a"),
                new NightlyMediaSnapshot.Delivery("undelivered", "QUEUED", "creator-c"));

        NightlyMediaSnapshot.Snapshot first = service.close(LocalDate.parse("2026-09-28"), assets, jobs, deliveries);
        NightlyMediaSnapshot.Snapshot retry = service.close(LocalDate.parse("2026-09-28"), assets, jobs, deliveries);
        String manifest = new String(first.manifest(), StandardCharsets.UTF_8);

        require(first.assetCount() == 1, "only completed and delivered media enters the snapshot");
        require(manifest.contains("\"asset_id\":\"ready\""), "ready asset is recorded");
        require(!manifest.contains("processing") && !manifest.contains("undelivered"), "open work is excluded");
        require(first.objectKey().equals(retry.objectKey()), "reruns keep the same object key");
        require(first.idempotencyKey().equals(retry.idempotencyKey()), "reruns keep the same request identity");
        System.out.println("PASS nightly close selected 1 asset and produced a stable snapshot identity");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
