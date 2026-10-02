package dev.infrai.media;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

public final class NightlyMediaSnapshot {
    public record Asset(String assetId, String creatorId, String sourceUri) {}
    public record ProcessingJob(String assetId, String state, String outputKey) {}
    public record Delivery(String assetId, String state, String recipient) {}
    public record Snapshot(String objectKey, String idempotencyKey, byte[] manifest, int assetCount) {}

    public Snapshot close(LocalDate businessDate, List<Asset> assets,
                          List<ProcessingJob> jobs, List<Delivery> deliveries) {
        List<String> eligible = assets.stream()
                .filter(asset -> completed(jobs, asset.assetId()))
                .filter(asset -> delivered(deliveries, asset.assetId()))
                .sorted(Comparator.comparing(Asset::assetId))
                .map(asset -> manifestLine(asset, jobs, deliveries))
                .toList();
        String manifest = "{\"business_date\":" + quote(businessDate.toString())
                + ",\"asset_count\":" + eligible.size()
                + ",\"assets\":[" + String.join(",", eligible) + "]}\n";
        String digest = sha256(manifest);
        return new Snapshot("snapshots/" + businessDate + "/media-" + digest.substring(0, 16) + ".json",
                "media-snapshot-" + businessDate + "-" + digest, manifest.getBytes(StandardCharsets.UTF_8), eligible.size());
    }

    private static boolean completed(List<ProcessingJob> jobs, String assetId) {
        return jobs.stream().anyMatch(job -> job.assetId().equals(assetId) && job.state().equals("COMPLETED"));
    }

    private static boolean delivered(List<Delivery> deliveries, String assetId) {
        return deliveries.stream().anyMatch(delivery -> delivery.assetId().equals(assetId) && delivery.state().equals("DELIVERED"));
    }

    private static String manifestLine(Asset asset, List<ProcessingJob> jobs, List<Delivery> deliveries) {
        ProcessingJob job = jobs.stream().filter(item -> item.assetId().equals(asset.assetId()) && item.state().equals("COMPLETED"))
                .findFirst().orElseThrow();
        Delivery delivery = deliveries.stream().filter(item -> item.assetId().equals(asset.assetId()) && item.state().equals("DELIVERED"))
                .findFirst().orElseThrow();
        return "{\"asset_id\":" + quote(asset.assetId()) + ",\"creator_id\":" + quote(asset.creatorId())
                + ",\"source_uri\":" + quote(asset.sourceUri()) + ",\"output_key\":" + quote(job.outputKey())
                + ",\"recipient\":" + quote(delivery.recipient()) + "}";
    }

    private static String quote(String value) { return InfraiStorageClient.Json.quote(value); }

    private static String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
