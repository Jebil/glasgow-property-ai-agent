package com.glasgow.propertyagent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;

/**
 * FEAT-06: Scottish Home Report Inspector (Single Survey, Energy Report & Property Questionnaire).
 *
 * Extracts:
 * - Home Report Market Valuation (£) — distinguishing Section 4 Market Value from Section 3 Reinstatement Insurance
 * - Gross Internal Floor Area (m²)
 * - Council Tax Band (A–H) & EPC Energy Band (A–G)
 * - Single Survey Repair Category counts (Cat 1 / Cat 2 / Cat 3) & explicit Category 2/3 defect warnings
 *
 * Works 100% offline via pure-Java PDF FlateDecode/text-stream decompression + Scottish RICS Single Survey
 * regex extraction, and enriches summaries via local Ollama (gemma4:e4b) when available.
 */
@Service
public class HomeReportAnalyzerService {

    private static final Logger log = LoggerFactory.getLogger(HomeReportAnalyzerService.class);

    public record HomeReportAnalysis(
        Integer homeReportValuationGbp,
        Double floorAreaSqm,
        String councilTaxBand,
        String energyBand,
        int category1Count,
        int category2Count,
        int category3Count,
        List<String> repairWarnings,
        String summary,
        boolean llmEnriched
    ) {}

    private static final Pattern MARKET_VALUE_PATTERN = Pattern.compile(
        "(?:present\\s+market\\s+value|market\\s+value|current\\s+value|opinion\\s+of\\s+value|home\\s+report\\s+valuation)[^£]{0,220}£\\s*([0-9]{2,3}(?:,[0-9]{3})+|[0-9]{5,7})",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    private static final Pattern GENERIC_VALUATION_PATTERN = Pattern.compile(
        "valuation[^£]{0,140}£\\s*([0-9]{2,3}(?:,[0-9]{3})+|[0-9]{5,7})",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    private static final Pattern FLOOR_AREA_PATTERN = Pattern.compile(
        "(?:gross\\s+internal\\s+floor\\s+area|internal\\s+floor\\s+area|approximate\\s+floor\\s+area|floor\\s+area|extends\\s+to\\s+approximately)[^0-9]{0,80}([0-9]{2,4}(?:\\.[0-9]+)?)\\s*(?:m2|m²|sq\\.?\\s*m|square\\s+metres?)",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern GENERIC_SQM_PATTERN = Pattern.compile(
        "\\b([0-9]{2,4}(?:\\.[0-9]+)?)\\s*(?:m2|m²|sq\\.?\\s*m|square\\s+metres?)\\b",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern COUNCIL_TAX_PATTERN = Pattern.compile(
        "council\\s+tax(?:\\s+band)?[\\s:\\-–—]*(?:is\\s+|band\\s+)?([A-H])\\b",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern EPC_BAND_PATTERN = Pattern.compile(
        "(?:current\\s+energy\\s+efficiency\\s+rating|current\\s+energy\\s+rating|energy\\s+efficiency\\s+rating|epc\\s+rating|epc\\s+band)[\\s:\\-–—]*(?:is\\s+|band\\s+|\\d{1,3}\\s*\\(?\\s*(?:band\\s*)?)?([A-G])\\b",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern EXPLICIT_REPAIR_CATEGORY_PATTERN = Pattern.compile(
        "(?:repair\\s+category|category)\\s*[:\\-]?\\s*([123])\\b",
        Pattern.CASE_INSENSITIVE
    );

    private static final List<String> SINGLE_SURVEY_SECTIONS = List.of(
        "Chimney stacks",
        "Roofing including roof space",
        "Roof coverings",
        "Sloping roofs",
        "Flat roofs",
        "Roof spaces",
        "Rainwater fittings",
        "Main walls",
        "Windows, external doors and joinery",
        "External decorations",
        "Conservatories / porches",
        "Communal areas",
        "Garages and permanent outbuildings",
        "Outside areas and boundaries",
        "Ceilings",
        "Internal walls",
        "Floors including sub floors",
        "Internal joinery and kitchen fittings",
        "Chimney breasts and fireplaces",
        "Internal decorations",
        "Cellars",
        "Electricity",
        "Gas",
        "Water, plumbing and bathroom fittings",
        "Heating and hot water",
        "Drainage",
        "Dampness, rot and infestation",
        "Structural movement"
    );

    private final ObjectMapper objectMapper;
    private final OllamaApiClient ollamaClient;
    private final String rankingModel;

    public HomeReportAnalyzerService() {
        this(new ObjectMapper(), null, "gemma4:e4b");
    }

    @Autowired
    public HomeReportAnalyzerService(
            ObjectMapper objectMapper,
            OllamaApiClient ollamaClient,
            @Value("${property-agent.ollama.ranking-model:gemma4:e4b}") String rankingModel) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.ollamaClient = ollamaClient;
        this.rankingModel = (rankingModel == null || rankingModel.isBlank()) ? "gemma4:e4b" : rankingModel;
    }

    public HomeReportAnalysis analyzePdfBytes(byte[] pdfBytes, Integer askingPriceHint) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new IllegalArgumentException("Empty Home Report file uploaded.");
        }
        String extractedText = extractTextFromPdfBytes(pdfBytes);
        if (extractedText.isBlank()) {
            throw new IllegalArgumentException("Could not extract readable text from PDF (file may be a scanned image-only PDF; paste Single Survey text instead).");
        }
        return analyzeHomeReportText(extractedText, askingPriceHint);
    }

    /**
     * Pure-Java PDF text extractor supporting uncompressed & FlateDecode (zlib/deflate) content streams,
     * literal (...) Tj / [...] TJ operators, octal escapes, and UTF-16BE / WinAnsi <...> hex strings.
     */
    public String extractTextFromPdfBytes(byte[] pdfBytes) {
        if (pdfBytes == null || pdfBytes.length == 0) return "";

        // Check if user uploaded a plain-text file directly
        String head = new String(pdfBytes, 0, Math.min(pdfBytes.length, 16), StandardCharsets.ISO_8859_1);
        if (!head.contains("%PDF-")) {
            return new String(pdfBytes, StandardCharsets.UTF_8).trim();
        }

        // ISO-8859-1 maps 1 byte = 1 char, keeping exact byte indices intact for stream slicing
        String rawIso = new String(pdfBytes, StandardCharsets.ISO_8859_1);
        StringBuilder fullText = new StringBuilder(8192);

        int searchFrom = 0;
        while (searchFrom < rawIso.length()) {
            int streamKeyword = rawIso.indexOf("stream", searchFrom);
            if (streamKeyword < 0) break;

            // Ensure "stream" is not the suffix of "endstream"
            if (streamKeyword >= 3 && rawIso.startsWith("end", streamKeyword - 3)) {
                searchFrom = streamKeyword + 6;
                continue;
            }

            int dataStart = streamKeyword + 6;
            if (dataStart < rawIso.length() && rawIso.charAt(dataStart) == '\r') dataStart++;
            if (dataStart < rawIso.length() && rawIso.charAt(dataStart) == '\n') dataStart++;
            else if (dataStart == streamKeyword + 6) {
                // Not followed by EOL -> not a valid PDF stream operator
                searchFrom = streamKeyword + 6;
                continue;
            }

            int endStreamIdx = rawIso.indexOf("endstream", dataStart);
            if (endStreamIdx < 0) break;

            int dataEnd = endStreamIdx;
            while (dataEnd > dataStart && (rawIso.charAt(dataEnd - 1) == '\n' || rawIso.charAt(dataEnd - 1) == '\r' || rawIso.charAt(dataEnd - 1) == ' ')) {
                dataEnd--;
            }

            String dictWindow = rawIso.substring(Math.max(0, streamKeyword - 320), streamKeyword);
            boolean isFlate = dictWindow.contains("/FlateDecode");
            boolean isImageOrFont = dictWindow.contains("/Subtype /Image")
                || dictWindow.contains("/Subtype/Image")
                || dictWindow.contains("/FontFile");

            if (!isImageOrFont && dataEnd > dataStart) {
                byte[] streamSlice = Arrays.copyOfRange(pdfBytes, dataStart, dataEnd);
                byte[] decodedBytes = isFlate ? tryInflate(streamSlice) : streamSlice;
                if (decodedBytes != null && decodedBytes.length > 0) {
                    String content = new String(decodedBytes, StandardCharsets.ISO_8859_1);
                    if (content.contains("Tj") || content.contains("TJ") || content.contains("BT")) {
                        String decodedText = parsePdfContentStreamText(content);
                        if (!decodedText.isBlank()) {
                            fullText.append(decodedText).append('\n');
                        }
                    }
                }
            }

            searchFrom = endStreamIdx + 9;
        }

        return fullText.toString()
            .replaceAll("[ \\t]+", " ")
            .replaceAll("\\n{3,}", "\n\n")
            .trim();
    }

    private static byte[] tryInflate(byte[] compressed) {
        for (boolean nowrap : new boolean[]{false, true}) {
            Inflater inflater = new Inflater(nowrap);
            try (ByteArrayOutputStream bos = new ByteArrayOutputStream(compressed.length * 3)) {
                inflater.setInput(compressed);
                byte[] buf = new byte[4096];
                int guard = 0;
                while (!inflater.finished() && guard++ < 5000) {
                    int count = inflater.inflate(buf);
                    if (count > 0) {
                        bos.write(buf, 0, count);
                    } else if (inflater.needsInput() || inflater.needsDictionary()) {
                        break;
                    }
                }
                if (bos.size() > 0) {
                    return bos.toByteArray();
                }
            } catch (Exception ignored) {
            } finally {
                inflater.end();
            }
        }
        return null;
    }

    private static String parsePdfContentStreamText(String stream) {
        StringBuilder out = new StringBuilder(stream.length() / 2);
        int len = stream.length();
        int i = 0;
        boolean inArray = false;

        while (i < len) {
            char c = stream.charAt(i);
            if (c == '[') {
                inArray = true;
                i++;
            } else if (c == ']') {
                inArray = false;
                i++;
            } else if (c == '(') {
                int depth = 1;
                i++;
                StringBuilder lit = new StringBuilder();
                while (i < len && depth > 0) {
                    char ch = stream.charAt(i++);
                    if (ch == '\\' && i < len) {
                        char next = stream.charAt(i++);
                        switch (next) {
                            case 'n' -> lit.append('\n');
                            case 'r' -> lit.append('\r');
                            case 't' -> lit.append(' ');
                            case 'b', 'f' -> {}
                            case '(', ')', '\\' -> lit.append(next);
                            default -> {
                                if (next >= '0' && next <= '7') {
                                    StringBuilder oct = new StringBuilder().append(next);
                                    while (oct.length() < 3 && i < len && stream.charAt(i) >= '0' && stream.charAt(i) <= '7') {
                                        oct.append(stream.charAt(i++));
                                    }
                                    int code = Integer.parseInt(oct.toString(), 8);
                                    lit.append(mapPdfCharCode(code));
                                } else {
                                    lit.append(next);
                                }
                            }
                        }
                    } else if (ch == '(') {
                        depth++;
                        lit.append(ch);
                    } else if (ch == ')') {
                        depth--;
                        if (depth > 0) lit.append(ch);
                    } else {
                        lit.append(mapPdfCharCode(ch & 0xFF));
                    }
                }
                out.append(lit);
                if (!inArray) out.append(' ');
            } else if (c == '<' && (i + 1 >= len || stream.charAt(i + 1) != '<')) {
                int close = stream.indexOf('>', i + 1);
                if (close > i) {
                    String hex = stream.substring(i + 1, close).replaceAll("\\s+", "");
                    out.append(decodePdfHexString(hex));
                    if (!inArray) out.append(' ');
                    i = close + 1;
                } else {
                    i++;
                }
            } else if (inArray && (c == '-' || Character.isDigit(c))) {
                int start = i++;
                while (i < len && (Character.isDigit(stream.charAt(i)) || stream.charAt(i) == '.')) {
                    i++;
                }
                try {
                    double kern = Double.parseDouble(stream.substring(start, i));
                    if (kern < -120.0) {
                        out.append(' ');
                    }
                } catch (NumberFormatException ignored) {}
            } else if (c == 'T' && i + 1 < len) {
                char op = stream.charAt(i + 1);
                if (op == 'd' || op == 'D' || op == '*') {
                    out.append('\n');
                    i += 2;
                } else {
                    i++;
                }
            } else if (c == 'E' && i + 1 < len && stream.charAt(i + 1) == 'T') {
                out.append('\n');
                i += 2;
            } else {
                i++;
            }
        }
        return out.toString();
    }

    private static char mapPdfCharCode(int code) {
        // WinAnsi / MacRoman common pound & euro & bullet codes in Scottish Home Reports
        return switch (code) {
            case 0xA3 -> '£';
            case 0xB2 -> '²';
            case 0x80 -> '€';
            case 0x95 -> '•';
            case 0x96, 0x97 -> '-';
            default -> (code >= 32 || code == '\n' || code == '\r' || code == '\t') ? (char) code : ' ';
        };
    }

    private static String decodePdfHexString(String hex) {
        if (hex.length() < 2) return "";
        if (hex.length() % 2 != 0) hex = hex + "0";
        byte[] bytes = new byte[hex.length() / 2];
        for (int idx = 0; idx < bytes.length; idx++) {
            try {
                bytes[idx] = (byte) Integer.parseInt(hex.substring(idx * 2, idx * 2 + 2), 16);
            } catch (NumberFormatException e) {
                return "";
            }
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        StringBuilder sb = new StringBuilder(bytes.length);
        for (byte b : bytes) {
            sb.append(mapPdfCharCode(b & 0xFF));
        }
        return sb.toString();
    }

    public HomeReportAnalysis analyzeHomeReportText(String rawText, Integer askingPriceHint) {
        if (rawText == null || rawText.isBlank()) {
            throw new IllegalArgumentException("Home Report text cannot be empty.");
        }

        // 1. Deterministic Scottish RICS Single Survey & EPC extraction
        Integer hrValuation = extractMarketValuation(rawText, askingPriceHint);
        Double floorAreaSqm = extractFloorAreaSqm(rawText);
        String councilTaxBand = extractCouncilTaxBand(rawText);
        String energyBand = extractEnergyBand(rawText);
        SurveyCategoryResult catResult = extractRepairCategories(rawText);

        // 2. Optional Local LLM Enrichment via Ollama if daemon is online
        boolean llmEnriched = false;
        String summary = buildDeterministicSummary(hrValuation, floorAreaSqm, councilTaxBand, energyBand, catResult);
        List<String> repairWarnings = new ArrayList<>(catResult.warnings());

        if (ollamaClient != null) {
            try {
                List<String> installed = ollamaClient.fetchInstalledModels();
                if (installed != null && !installed.isEmpty()) {
                    HomeReportAnalysis llmResult = tryEnrichWithOllama(rawText, hrValuation, floorAreaSqm, councilTaxBand, energyBand, catResult);
                    if (llmResult != null) {
                        return llmResult;
                    }
                }
            } catch (Exception e) {
                log.debug("Home Report Ollama enrichment skipped (using deterministic parser): {}", e.getMessage());
            }
        }

        return new HomeReportAnalysis(
            hrValuation,
            floorAreaSqm,
            councilTaxBand,
            energyBand,
            catResult.cat1(),
            catResult.cat2(),
            catResult.cat3(),
            repairWarnings,
            summary,
            llmEnriched
        );
    }

    private Integer extractMarketValuation(String text, Integer askingPriceHint) {
        // Priority 1: Explicit "Market Value" / "Present Market Value" (excludes Section 3 Reinstatement Insurance!)
        List<Integer> candidates = new ArrayList<>();
        Matcher mv = MARKET_VALUE_PATTERN.matcher(text);
        while (mv.find()) {
            String contextBefore = text.substring(Math.max(0, mv.start() - 100), mv.start()).toLowerCase(Locale.ROOT);
            String matchedSpan = mv.group(0).toLowerCase(Locale.ROOT);
            if (contextBefore.contains("reinstatement") || matchedSpan.contains("reinstatement") || matchedSpan.contains("insurance")) {
                continue;
            }
            Integer parsed = parseAmount(mv.group(1));
            if (parsed != null && parsed >= 45_000 && parsed <= 5_000_000) {
                candidates.add(parsed);
            }
        }
        if (!candidates.isEmpty()) {
            return pickMostPlausibleValuation(candidates, askingPriceHint);
        }

        // Priority 2: Generic "Valuation ... £..." not preceded by insurance/reinstatement
        Matcher gv = GENERIC_VALUATION_PATTERN.matcher(text);
        while (gv.find()) {
            String contextBefore = text.substring(Math.max(0, gv.start() - 100), gv.start()).toLowerCase(Locale.ROOT);
            String matchedSpan = gv.group(0).toLowerCase(Locale.ROOT);
            if (contextBefore.contains("reinstatement") || matchedSpan.contains("reinstatement") || matchedSpan.contains("insurance")) {
                continue;
            }
            Integer parsed = parseAmount(gv.group(1));
            if (parsed != null && parsed >= 45_000 && parsed <= 5_000_000) {
                candidates.add(parsed);
            }
        }
        return candidates.isEmpty() ? null : pickMostPlausibleValuation(candidates, askingPriceHint);
    }

    private static Integer pickMostPlausibleValuation(List<Integer> candidates, Integer askingPriceHint) {
        if (askingPriceHint != null && askingPriceHint > 0) {
            return candidates.stream()
                .min(Comparator.comparingInt(v -> Math.abs(v - askingPriceHint)))
                .orElse(candidates.get(0));
        }
        return candidates.get(0);
    }

    private Double extractFloorAreaSqm(String text) {
        Matcher m = FLOOR_AREA_PATTERN.matcher(text);
        if (m.find()) {
            try {
                double val = Double.parseDouble(m.group(1));
                if (val >= 28.0 && val <= 1_200.0) {
                    return Math.round(val * 10.0) / 10.0;
                }
            } catch (NumberFormatException ignored) {}
        }
        Matcher gm = GENERIC_SQM_PATTERN.matcher(text);
        while (gm.find()) {
            try {
                double val = Double.parseDouble(gm.group(1));
                if (val >= 35.0 && val <= 900.0) {
                    return Math.round(val * 10.0) / 10.0;
                }
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private String extractCouncilTaxBand(String text) {
        Matcher m = COUNCIL_TAX_PATTERN.matcher(text);
        if (m.find()) {
            return m.group(1).toUpperCase(Locale.ROOT);
        }
        return null;
    }

    private String extractEnergyBand(String text) {
        Matcher m = EPC_BAND_PATTERN.matcher(text);
        if (m.find()) {
            return m.group(1).toUpperCase(Locale.ROOT);
        }
        return null;
    }

    private record SurveyCategoryResult(int cat1, int cat2, int cat3, List<String> warnings) {}

    private SurveyCategoryResult extractRepairCategories(String text) {
        int cat1 = 0;
        int cat2 = 0;
        int cat3 = 0;
        Set<String> warnings = new LinkedHashSet<>();

        // 1. Scan explicit "Repair category: 1/2/3" occurrences
        Matcher explicitMatcher = EXPLICIT_REPAIR_CATEGORY_PATTERN.matcher(text);
        while (explicitMatcher.find()) {
            String digit = explicitMatcher.group(1);
            switch (digit) {
                case "1" -> cat1++;
                case "2" -> cat2++;
                case "3" -> cat3++;
            }
            if ("2".equals(digit) || "3".equals(digit)) {
                int beforeStart = Math.max(0, explicitMatcher.start() - 140);
                String beforeWindow = text.substring(beforeStart, explicitMatcher.start());
                String section = findClosestPrecedingSection(beforeWindow);

                int winEnd = Math.min(text.length(), explicitMatcher.end() + 220);
                String afterCat = truncateRepairNote(text.substring(explicitMatcher.end(), winEnd))
                    .replaceAll("\\s+", " ")
                    .trim();
                if (afterCat.length() > 140) {
                    afterCat = afterCat.substring(0, 137) + "...";
                }
                String prefix = "3".equals(digit) ? "[Cat 3 Urgent]" : "[Cat 2 Repair]";
                String label = section != null ? (prefix + " " + section + ": " + afterCat) : (prefix + " " + afterCat);
                if (warnings.size() < 10) {
                    warnings.add(label.trim());
                }
            }
        }

        // 2. Scan known Scottish RICS Single Survey table rows (e.g. "Roof coverings  2  Moss and weathered ridge tiles")
        for (String sec : SINGLE_SURVEY_SECTIONS) {
            Pattern rowPat = Pattern.compile(
                Pattern.quote(sec) + "\\s+(?:repair\\s+category\\s*[:\\-]?\\s*)?([123])\\b([^\\n]{0,180})",
                Pattern.CASE_INSENSITIVE
            );
            Matcher rm = rowPat.matcher(text);
            if (rm.find()) {
                String digit = rm.group(1);
                String note = rm.group(2) != null ? rm.group(2).replaceAll("\\s+", " ").trim() : "";
                if (cat1 + cat2 + cat3 == 0 || !text.toLowerCase(Locale.ROOT).contains("repair category")) {
                    switch (digit) {
                        case "1" -> cat1++;
                        case "2" -> cat2++;
                        case "3" -> cat3++;
                    }
                }
                if (("2".equals(digit) || "3".equals(digit)) && warnings.size() < 10) {
                    String prefix = "3".equals(digit) ? "[Cat 3 Urgent]" : "[Cat 2 Repair]";
                    boolean alreadyCovered = warnings.stream().anyMatch(w -> w.toLowerCase(Locale.ROOT).contains(sec.toLowerCase(Locale.ROOT)));
                    if (!alreadyCovered) {
                        warnings.add(prefix + " " + sec + (note.isBlank() ? "" : ": " + note));
                    }
                }
            }
        }

        return new SurveyCategoryResult(cat1, cat2, cat3, new ArrayList<>(warnings));
    }

    private static String findClosestPrecedingSection(String beforeWindow) {
        String lower = beforeWindow.toLowerCase(Locale.ROOT);
        String bestSection = null;
        int bestIdx = -1;
        for (String sec : SINGLE_SURVEY_SECTIONS) {
            int idx = lower.lastIndexOf(sec.toLowerCase(Locale.ROOT));
            if (idx >= 0 && (idx > bestIdx || (idx == bestIdx && bestSection != null && sec.length() > bestSection.length()))) {
                bestIdx = idx;
                bestSection = sec;
            }
        }
        return bestSection;
    }

    private static String truncateRepairNote(String rawAfter) {
        if (rawAfter == null || rawAfter.isEmpty()) {
            return "";
        }
        String lower = rawAfter.toLowerCase(Locale.ROOT);
        int cut = rawAfter.length();

        int nextCat = lower.indexOf("repair category");
        if (nextCat >= 0 && nextCat < cut) {
            cut = nextCat;
        }
        int firstNewline = rawAfter.indexOf('\n');
        if (firstNewline >= 0) {
            String firstLine = rawAfter.substring(0, firstNewline).trim();
            if (!firstLine.isEmpty()) {
                cut = Math.min(cut, firstNewline);
            } else {
                int secondNewline = rawAfter.indexOf('\n', firstNewline + 1);
                if (secondNewline >= 0) {
                    cut = Math.min(cut, secondNewline);
                }
            }
        }
        for (String sec : SINGLE_SURVEY_SECTIONS) {
            int secIdx = lower.indexOf(sec.toLowerCase(Locale.ROOT));
            if (secIdx > 0 && secIdx < cut) {
                cut = secIdx;
            }
        }
        return rawAfter.substring(0, cut);
    }

    private static String buildDeterministicSummary(
            Integer hrValuation,
            Double floorAreaSqm,
            String councilTaxBand,
            String energyBand,
            SurveyCategoryResult cat) {
        List<String> parts = new ArrayList<>();
        if (hrValuation != null) {
            parts.add("Home Report Market Valuation: £" + String.format("%,d", hrValuation));
        }
        if (floorAreaSqm != null) {
            parts.add("Internal Floor Area: " + floorAreaSqm + " m²");
        }
        if (councilTaxBand != null) {
            parts.add("Council Tax Band " + councilTaxBand);
        }
        if (energyBand != null) {
            parts.add("EPC Band " + energyBand);
        }
        if (cat.cat3() > 0) {
            parts.add("⚠ " + cat.cat3() + " Category 3 urgent repair item(s) & " + cat.cat2() + " Category 2 item(s)");
        } else if (cat.cat2() > 0) {
            parts.add(cat.cat2() + " Category 2 future maintenance item(s), 0 Category 3 urgent defects");
        } else if (cat.cat1() > 0) {
            parts.add("Clean Single Survey (" + cat.cat1() + " Category 1 items, 0 Category 2/3 warnings)");
        }
        return parts.isEmpty()
            ? "Parsed Scottish Home Report extract."
            : String.join(" • ", parts) + ".";
    }

    private HomeReportAnalysis tryEnrichWithOllama(
            String rawText,
            Integer regexHrVal,
            Double regexSqm,
            String regexTaxBand,
            String regexEpc,
            SurveyCategoryResult catResult) {
        String truncated = rawText.length() > 14_000 ? rawText.substring(0, 14_000) : rawText;
        String prompt = """
            You are an expert Scottish property solicitor & surveyor assistant reviewing a Scottish Home Report (Single Survey & Energy Report).
            Extract accurate figures into strict JSON:
            {
              "homeReportValuationGbp": integer or null (Section 4 Market Value ONLY, NEVER Section 3 insurance reinstatement cost),
              "floorAreaSqm": number or null (gross internal floor area in m2),
              "councilTaxBand": "A".."H" or null,
              "energyBand": "A".."G" or null,
              "category2Count": integer,
              "category3Count": integer,
              "repairWarnings": ["[Cat 3 Urgent] Section: summary", "[Cat 2 Repair] Section: summary"],
              "summary": "2-sentence buyer executive summary highlighting valuation, condition, and key survey risks"
            }

            HOME REPORT TEXT:
            """ + truncated;

        Map<String, Object> req = Map.of(
            "model", rankingModel,
            "messages", List.of(Map.of("role", "user", "content", prompt)),
            "format", "json",
            "stream", false,
            "options", Map.of("temperature", 0.1, "num_predict", 600)
        );

        JsonNode resp = ollamaClient.postChatJson(req);
        String content = resp.path("message").path("content").asString("");
        if (content.isBlank()) return null;

        JsonNode parsed = objectMapper.readTree(content);
        Integer llmVal = parsed.hasNonNull("homeReportValuationGbp") ? parsed.get("homeReportValuationGbp").asInt() : null;
        Double llmSqm = parsed.hasNonNull("floorAreaSqm") ? parsed.get("floorAreaSqm").asDouble() : null;
        String llmTax = parsed.hasNonNull("councilTaxBand") ? parsed.get("councilTaxBand").asString("") : null;
        String llmEpc = parsed.hasNonNull("energyBand") ? parsed.get("energyBand").asString("") : null;
        int llmCat2 = parsed.path("category2Count").asInt(catResult.cat2());
        int llmCat3 = parsed.path("category3Count").asInt(catResult.cat3());
        String llmSummary = parsed.path("summary").asString("");

        List<String> mergedWarnings = new ArrayList<>(catResult.warnings());
        JsonNode warnNode = parsed.path("repairWarnings");
        if (warnNode.isArray() && mergedWarnings.isEmpty()) {
            for (JsonNode w : warnNode) {
                String ws = w.asString("").trim();
                if (!ws.isBlank()) mergedWarnings.add(ws);
            }
        }

        Integer finalVal = regexHrVal != null ? regexHrVal : (llmVal != null && llmVal >= 40_000 ? llmVal : null);
        Double finalSqm = regexSqm != null ? regexSqm : (llmSqm != null && llmSqm >= 25.0 ? llmSqm : null);
        String finalTax = regexTaxBand != null ? regexTaxBand : (llmTax != null && !llmTax.isBlank() ? llmTax : null);
        String finalEpc = regexEpc != null ? regexEpc : (llmEpc != null && !llmEpc.isBlank() ? llmEpc : null);

        return new HomeReportAnalysis(
            finalVal,
            finalSqm,
            finalTax,
            finalEpc,
            catResult.cat1(),
            Math.max(catResult.cat2(), llmCat2),
            Math.max(catResult.cat3(), llmCat3),
            mergedWarnings,
            !llmSummary.isBlank() ? llmSummary : buildDeterministicSummary(finalVal, finalSqm, finalTax, finalEpc, catResult),
            true
        );
    }

    private static Integer parseAmount(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Integer.parseInt(raw.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
