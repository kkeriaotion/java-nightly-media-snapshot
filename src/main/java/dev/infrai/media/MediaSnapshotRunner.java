package dev.infrai.media;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Properties;

public final class MediaSnapshotRunner {
    public static void main(String[] args) throws Exception {
        Settings settings = Settings.load();
        LocalDate businessDate = args.length == 0 ? LocalDate.now(ZoneId.of(settings.zone())) : LocalDate.parse(args[0]);
        NightlyMediaSnapshot snapshotter = new NightlyMediaSnapshot();
        NightlyMediaSnapshot.Snapshot snapshot = snapshotter.close(businessDate,
                List.of(new NightlyMediaSnapshot.Asset("asset-1042", "creator-77", "ingest/live/session-1042.ts")),
                List.of(new NightlyMediaSnapshot.ProcessingJob("asset-1042", "COMPLETED", "processed/asset-1042.mp4")),
                List.of(new NightlyMediaSnapshot.Delivery("asset-1042", "DELIVERED", "creator-77")));

        InfraiStorageClient storage = new InfraiStorageClient(System.getenv("INFRAI_API_KEY"));
        storage.ensureBucket(settings.bucket());
        String url = storage.presignSnapshotPut(settings.bucket(), snapshot.objectKey(),
                "application/json", snapshot.manifest().length, snapshot.idempotencyKey());
        storage.putSigned(url, snapshot.manifest(), "application/json");
        System.out.printf("snapshot_date=%s assets=%d object=%s/%s%n",
                businessDate, snapshot.assetCount(), settings.bucket(), snapshot.objectKey());
    }

    record Settings(String bucket, String zone) {
        static Settings load() throws IOException {
            Properties properties = new Properties();
            try (InputStream input = MediaSnapshotRunner.class.getResourceAsStream("/application.properties")) {
                if (input != null) properties.load(input);
            }
            String bucket = envOr("MEDIA_SNAPSHOT_BUCKET", properties.getProperty("snapshot.bucket", "media-nightly-snapshots"));
            String zone = envOr("MEDIA_SNAPSHOT_ZONE", properties.getProperty("snapshot.zone", "UTC"));
            return new Settings(bucket, zone);
        }

        private static String envOr(String name, String fallback) {
            String value = System.getenv(name);
            return value == null || value.isBlank() ? fallback : value;
        }
    }
}
