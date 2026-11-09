package com.glasgow.propertyagent.service.scraping;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Downloads and caches ALL property gallery images and ALL architectural floorplan images
 * locally under ./data/property-images/<listingId>/ so the local Vision LLM can inspect every image
 * from disk and the UI can browse the full local gallery.
 *
 * Phase 2.5 (SIMP-03 + UPG-01):
 *  - Uses Java 25 Virtual Threads gated by a Semaphore(8) concurrency cap instead of 8 idle OS threads.
 *  - Lazy-initializes HttpClient so unit tests in restricted sandboxes never open NIO selector pipes.
 *  - Decodes .webp, .png, and .jpg transparently via TwelveMonkeys ImageIO WebP ServiceLoader.
 */
@Service
public class PropertyMediaService {

    private static final Logger log = LoggerFactory.getLogger(PropertyMediaService.class);
    private static final Path ROOT_MEDIA_DIR = Path.of("data", "property-images");

    private volatile HttpClient httpClient;
    private final ExecutorService downloadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore concurrencyLimiter = new Semaphore(8);

    public record DownloadedMediaBundle(
        String listingId,
        List<Path> floorplanFiles,
        List<Path> photoFiles,
        List<String> localFloorplanWebUrls,
        List<String> localPhotoWebUrls,
        int totalDownloadedFiles
    ) {
        public String localThumbnailWebUrl(String fallbackUrl) {
            String safeId = listingId.replaceAll("[^a-zA-Z0-9_-]", "_");
            Path thumbFile = ROOT_MEDIA_DIR.resolve(safeId).resolve("photo-01-thumb.jpg");
            if (Files.exists(thumbFile) && thumbFile.toFile().length() > 200) {
                return "/property-media/" + safeId + "/photo-01-thumb.jpg";
            }
            if (localPhotoWebUrls != null && !localPhotoWebUrls.isEmpty()) {
                return localPhotoWebUrls.get(0);
            }
            return fallbackUrl;
        }
    }

    public PropertyMediaService() {
    }

    private HttpClient getHttpClient() {
        HttpClient client = this.httpClient;
        if (client == null) {
            synchronized (this) {
                client = this.httpClient;
                if (client == null) {
                    client = HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(Duration.ofSeconds(10))
                        .build();
                    this.httpClient = client;
                }
            }
        }
        return client;
    }

    @PreDestroy
    public void shutdown() {
        try {
            downloadExecutor.shutdown();
            if (!downloadExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                downloadExecutor.shutdownNow();
            }
        } catch (Exception ignored) {}
    }

    public DownloadedMediaBundle downloadAllPropertyMedia(
            String listingId,
            List<String> remoteFloorplanUrls,
            List<String> remotePhotoUrls) {

        String safeId = listingId.replaceAll("[^a-zA-Z0-9_-]", "_");
        Path listingDir = ROOT_MEDIA_DIR.resolve(safeId);
        try {
            Files.createDirectories(listingDir);
        } catch (Exception e) {
            log.warn("Could not create media directory {}: {}", listingDir, e.getMessage());
        }

        Set<String> uniqueFloorplans = new LinkedHashSet<>();
        if (remoteFloorplanUrls != null) {
            for (String u : remoteFloorplanUrls) {
                if (u != null && u.startsWith("http")) uniqueFloorplans.add(u);
            }
        }

        Set<String> uniquePhotos = new LinkedHashSet<>();
        if (remotePhotoUrls != null) {
            for (String u : remotePhotoUrls) {
                if (u != null && u.startsWith("http") && !uniqueFloorplans.contains(u)) {
                    // N8: Skip non-informative EPC charts, agency logos, QR codes, and badges based on URL cues before downloading
                    String lower = u.toLowerCase();
                    if (lower.contains("epc") || lower.contains("energy") || lower.contains("logo")
                            || lower.contains("qr") || lower.contains("badge") || lower.contains("watermark")) {
                        continue;
                    }
                    uniquePhotos.add(u);
                }
            }
        }

        List<CompletableFuture<Path>> fpFutures = new ArrayList<>();
        int fpIdx = 1;
        for (String fpUrl : uniqueFloorplans) {
            String ext = guessExtension(fpUrl);
            Path target = listingDir.resolve(String.format("floorplan-%02d.%s", fpIdx++, ext));
            fpFutures.add(CompletableFuture.supplyAsync(() -> downloadIfMissing(fpUrl, target), downloadExecutor));
        }

        List<CompletableFuture<Path>> photoFutures = new ArrayList<>();
        int photoIdx = 1;
        for (String photoUrl : uniquePhotos) {
            final int currIdx = photoIdx++;
            String ext = guessExtension(photoUrl);
            Path target = listingDir.resolve(String.format("photo-%02d.%s", currIdx, ext));
            photoFutures.add(CompletableFuture.supplyAsync(() -> {
                Path downloaded = downloadIfMissing(photoUrl, target);
                if (downloaded != null && currIdx == 1) {
                    Path thumbTarget = listingDir.resolve("photo-01-thumb.jpg");
                    generateThumbnailIfMissing(downloaded, thumbTarget);
                }
                return downloaded;
            }, downloadExecutor));
        }

        List<Path> savedFloorplans = new ArrayList<>();
        List<String> webFloorplans = new ArrayList<>();
        for (CompletableFuture<Path> f : fpFutures) {
            Path p = f.join();
            if (p != null) {
                savedFloorplans.add(p);
                webFloorplans.add("/property-media/" + safeId + "/" + p.getFileName().toString());
            }
        }

        List<Path> savedPhotos = new ArrayList<>();
        List<String> webPhotos = new ArrayList<>();
        for (CompletableFuture<Path> f : photoFutures) {
            Path p = f.join();
            if (p != null) {
                savedPhotos.add(p);
                webPhotos.add("/property-media/" + safeId + "/" + p.getFileName().toString());
            }
        }

        int total = savedFloorplans.size() + savedPhotos.size();
        log.info("Downloaded/verified {} media files for {} ({} floorplans + {} photos) -> {}",
            total, listingId, savedFloorplans.size(), savedPhotos.size(), listingDir.toAbsolutePath());

        return new DownloadedMediaBundle(
            listingId,
            savedFloorplans,
            savedPhotos,
            webFloorplans,
            webPhotos,
            total
        );
    }

    public DownloadedMediaBundle loadExistingLocalMedia(String listingId) {
        String safeId = listingId.replaceAll("[^a-zA-Z0-9_-]", "_");
        Path listingDir = ROOT_MEDIA_DIR.resolve(safeId);
        List<Path> floorplans = new ArrayList<>();
        List<Path> photos = new ArrayList<>();
        List<String> webFloorplans = new ArrayList<>();
        List<String> webPhotos = new ArrayList<>();

        if (Files.isDirectory(listingDir)) {
            try (Stream<Path> stream = Files.list(listingDir)) {
                stream.sorted().forEach(p -> {
                    String name = p.getFileName().toString();
                    if (name.contains("-thumb.")) return; // Ignore generated card thumbnails
                    if (name.startsWith("floorplan-")) {
                        floorplans.add(p);
                        webFloorplans.add("/property-media/" + safeId + "/" + name);
                    } else if (name.startsWith("photo-")) {
                        photos.add(p);
                        webPhotos.add("/property-media/" + safeId + "/" + name);
                    }
                });
            } catch (Exception ignored) {}
        }

        return new DownloadedMediaBundle(
            listingId,
            floorplans,
            photos,
            webFloorplans,
            webPhotos,
            floorplans.size() + photos.size()
        );
    }

    public void deleteAllDownloadedMedia() {
        if (!Files.exists(ROOT_MEDIA_DIR)) return;
        try (Stream<Path> walk = Files.walk(ROOT_MEDIA_DIR)) {
            walk.sorted((a, b) -> b.compareTo(a))
                .filter(p -> !p.equals(ROOT_MEDIA_DIR))
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {}
                });
            log.info("Cleared local downloaded media directory: {}", ROOT_MEDIA_DIR.toAbsolutePath());
        } catch (Exception e) {
            log.warn("Could not clear media directory: {}", e.getMessage());
        }
    }

    public static void generateThumbnailIfMissing(Path original, Path thumbTarget) {
        if (Files.exists(thumbTarget) && thumbTarget.toFile().length() > 200) {
            return;
        }
        try {
            BufferedImage img = ImageIO.read(original.toFile());
            if (img == null) return;
            int origW = img.getWidth();
            int origH = img.getHeight();
            int newW = Math.min(400, origW);
            int newH = origW <= 400 ? origH : (int) Math.round((double) origH * (400.0 / origW));
            BufferedImage thumb = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = thumb.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, newW, newH);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(img, 0, 0, newW, newH, null);
            g.dispose();
            ImageIO.write(thumb, "jpg", thumbTarget.toFile());
        } catch (Exception e) {
            log.debug("Thumbnail generation skipped for {}: {}", original.getFileName(), e.getMessage());
        }
    }

    private Path downloadIfMissing(String url, Path target) {
        try {
            if (Files.exists(target) && Files.size(target) > 500) {
                return target;
            }
            concurrencyLimiter.acquire();
            try {
                if (Files.exists(target) && Files.size(target) > 500) {
                    return target;
                }
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
                HttpResponse<byte[]> res = getHttpClient().send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (res.statusCode() == 200 && res.body() != null && res.body().length > 500) {
                    Files.write(target, res.body());
                    return target;
                } else {
                    log.debug("Image download unsuccessful (HTTP {}, len={}): {}",
                        res.statusCode(), (res.body() != null ? res.body().length : 0), url);
                }
            } finally {
                concurrencyLimiter.release();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("Failed downloading image {} -> {}: {}", url, target.getFileName(), e.getMessage());
        }
        return null;
    }

    private String guessExtension(String url) {
        String lower = url.toLowerCase();
        if (lower.contains(".png")) return "png";
        if (lower.contains(".webp")) return "webp";
        return "jpg";
    }
}
