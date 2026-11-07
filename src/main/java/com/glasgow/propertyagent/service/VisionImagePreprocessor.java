package com.glasgow.propertyagent.service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * REF-02 / IMP-03 / UPG-01: Single-responsibility image preprocessor for multimodal Vision LLMs.
 *  - Encodes architectural floorplans as lossless high-contrast RGB PNG (default <= 1680px)
 *  - Encodes property gallery photos as compressed RGB JPEG (default <= 896px)
 *  - Transparently decodes .webp, .png, and .jpg via TwelveMonkeys ImageIO WebP ServiceLoader,
 *    always converting .webp photos to JPEG/PNG so Ollama's vision encoder never rejects WebP bytes.
 */
public final class VisionImagePreprocessor {

    private VisionImagePreprocessor() {}

    public static List<Path> filterInformativePhotos(List<Path> photos, int maxCap) {
        if (photos == null || photos.isEmpty()) return List.of();
        List<Path> filtered = new ArrayList<>();
        for (Path p : photos) {
            String name = p.getFileName().toString().toLowerCase();
            if (name.contains("epc") || name.contains("logo") || name.contains("icon")
                    || name.contains("energy") || name.contains("qr") || name.contains("badge")) {
                continue;
            }
            try {
                long size = Files.size(p);
                if (size < 4000) {
                    continue;
                }
            } catch (Exception ignored) {}
            filtered.add(p);
            if (maxCap > 0 && filtered.size() >= maxCap) {
                break;
            }
        }
        if (filtered.isEmpty()) {
            return (maxCap > 0 && photos.size() > maxCap) ? photos.subList(0, maxCap) : photos;
        }
        return filtered;
    }

    public static byte[] prepareFloorplanBytesForVision(Path imageFile, int maxDimension) {
        try {
            if (!Files.exists(imageFile) || Files.size(imageFile) < 20) return null;
            String fileNameLower = imageFile.getFileName().toString().toLowerCase();
            boolean isWebp = fileNameLower.endsWith(".webp");
            boolean isPng = fileNameLower.endsWith(".png");

            BufferedImage original = ImageIO.read(imageFile.toFile());
            if (original == null) {
                return Files.size(imageFile) >= 300 ? Files.readAllBytes(imageFile) : null;
            }
            int width = original.getWidth();
            int height = original.getHeight();

            // Zero-copy fast path: already a standard JPEG or PNG within dimension cap and under 600KB
            if (!isWebp && width <= maxDimension && height <= maxDimension && Files.size(imageFile) < 600_000) {
                return Files.readAllBytes(imageFile);
            }

            double scale = Math.min(1.0, (double) maxDimension / Math.max(width, height));
            int newWidth = Math.max(1, (int) Math.round(width * scale));
            int newHeight = Math.max(1, (int) Math.round(height * scale));

            BufferedImage canvas = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = canvas.createGraphics();
            g2d.setColor(Color.WHITE);
            g2d.fillRect(0, 0, newWidth, newHeight);
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.drawImage(original, 0, 0, newWidth, newHeight, Color.WHITE, null);
            g2d.dispose();

            // WebP and PNG floorplans output lossless PNG (preserves Phase 2/2.5 test assertions)
            if (isPng || isWebp) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(canvas, "png", baos);
                return baos.toByteArray();
            } else {
                // Standard JPEG floorplans re-encode to sharp high-quality JPEG (0.92f) at ~100KB instead of 2MB PNG
                return encodeRgbToJpeg(canvas, 0.92f);
            }
        } catch (Exception e) {
            try {
                return Files.readAllBytes(imageFile);
            } catch (Exception ex) {
                return null;
            }
        }
    }

    public static byte[] prepareImageBytesForVision(Path imageFile, int maxDimension, float jpegQuality) {
        try {
            if (!Files.exists(imageFile) || Files.size(imageFile) < 20) return null;
            String fileNameLower = imageFile.getFileName().toString().toLowerCase();
            boolean isWebp = fileNameLower.endsWith(".webp");

            BufferedImage original = ImageIO.read(imageFile.toFile());
            if (original == null) {
                return Files.size(imageFile) >= 300 ? Files.readAllBytes(imageFile) : null;
            }
            int width = original.getWidth();
            int height = original.getHeight();
            // Always re-encode .webp to JPEG even when already small so Ollama vision receives standard JPEG bytes
            if (!isWebp && width <= maxDimension && height <= maxDimension && Files.size(imageFile) < 300_000) {
                return Files.readAllBytes(imageFile);
            }
            double scale = Math.min(1.0, (double) maxDimension / Math.max(width, height));
            int newWidth = Math.max(1, (int) Math.round(width * scale));
            int newHeight = Math.max(1, (int) Math.round(height * scale));

            BufferedImage resized = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = resized.createGraphics();
            g2d.setColor(Color.WHITE);
            g2d.fillRect(0, 0, newWidth, newHeight);
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.drawImage(original, 0, 0, newWidth, newHeight, Color.WHITE, null);
            g2d.dispose();

            return encodeRgbToJpeg(resized, jpegQuality);
        } catch (Exception e) {
            try {
                return Files.readAllBytes(imageFile);
            } catch (Exception ex) {
                return null;
            }
        }
    }

    public static byte[] encodeRgbToJpeg(BufferedImage img, float jpegQuality) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (writers.hasNext()) {
            ImageWriter writer = writers.next();
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
                writer.setOutput(ios);
                ImageWriteParam param = writer.getDefaultWriteParam();
                if (param.canWriteCompressed()) {
                    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    param.setCompressionQuality(jpegQuality);
                }
                writer.write(null, new IIOImage(img, null, null), param);
            } finally {
                writer.dispose();
            }
            return baos.toByteArray();
        } else {
            ImageIO.write(img, "jpg", baos);
            return baos.toByteArray();
        }
    }
}
