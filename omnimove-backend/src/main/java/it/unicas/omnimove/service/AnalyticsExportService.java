package it.unicas.omnimove.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the dashboard's analytics into a downloadable report.
 *
 * <p>Three formats off one dataset, so a CSV and a PDF of the same period can
 * never disagree: {@link #build} assembles the numbers once and the renderers
 * only lay them out.
 *
 * <p><b>No new dependencies.</b> An .xlsx is a zip of XML parts and a PDF is a
 * text format with an offset table, and both are written here directly. Pulling
 * in POI or a PDF toolkit for a handful of tables would have added a large
 * transitive surface to a build that is scanned for CVEs, and — for the usual
 * PDF libraries — a licence question this project does not need.
 *
 * <p>The content is aggregate figures only: no name, no e-mail, no journey of
 * any identifiable person. That is what the "anonymised" on the button means
 * and it is the reason the report can be handed to anyone.
 */
@Service
@RequiredArgsConstructor
public class AnalyticsExportService {

    private final AnalyticsService analytics;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /** One section of the report: a heading, column titles, and rows. */
    public record Section(String title, List<String> headers, List<List<String>> rows) {}

    /** Everything the three renderers share. */
    public record Report(String range, ZonedDateTime generatedAt, List<Section> sections) {}

    // ════════════════════════════════════════════════════════════════
    //  THE DATA
    // ════════════════════════════════════════════════════════════════

    public Report build(String range) {
        List<Section> sections = new ArrayList<>();

        // ── Summary ──
        Map<String, Object> kpis = analytics.getSummaryKpis(range);
        List<List<String>> kpiRows = new ArrayList<>();
        kpis.forEach((k, v) -> kpiRows.add(List.of(humanise(k), String.valueOf(v))));
        sections.add(new Section("Summary", List.of("Measure", "Value"), kpiRows));

        // ── Mode split ──
        Map<String, Long> modes = analytics.getModeDistribution(range);
        long modeTotal = modes.values().stream().mapToLong(Long::longValue).sum();
        List<List<String>> modeRows = new ArrayList<>();
        modes.forEach((mode, n) -> modeRows.add(List.of(
                mode,
                String.valueOf(n),
                modeTotal == 0 ? "0%" : Math.round(n * 100.0 / modeTotal) + "%")));
        sections.add(new Section("Journeys by mode",
                List.of("Mode", "Journeys", "Share"), modeRows));

        // ── Day of week ──
        List<List<String>> dowRows = new ArrayList<>();
        analytics.getModeByDayOfWeek(range)
                 .forEach((day, n) -> dowRows.add(List.of(day, String.valueOf(n))));
        sections.add(new Section("Journeys by day of week",
                List.of("Day", "Journeys"), dowRows));

        // ── Green index over time ──
        List<List<String>> giRows = new ArrayList<>();
        for (Map<String, Object> point : analytics.getGreenIndexTrend(range)) {
            giRows.add(List.of(String.valueOf(point.getOrDefault("date", "")),
                               String.valueOf(point.getOrDefault("value", ""))));
        }
        sections.add(new Section("Average green index",
                List.of("Date", "Index"), giRows));

        // ── Combined journeys. The MaaS question the report exists to answer. ──
        Map<String, Object> combined = analytics.getCombinedStats(range);

        List<List<String>> combinedSummary = new ArrayList<>();
        combinedSummary.add(List.of("Combined journeys", str(combined.get("combinedJourneys"))));
        combinedSummary.add(List.of("Share of all journeys", str(combined.get("combinedShare")) + "%"));
        combinedSummary.add(List.of("Average duration (min)", str(combined.get("avgDurationMin"))));
        combinedSummary.add(List.of("Median duration (min)", str(combined.get("medianDurationMin"))));
        combinedSummary.add(List.of("Average distance (km)", str(combined.get("avgDistanceKm"))));
        combinedSummary.add(List.of("Average cost (EUR)", str(combined.get("avgCostEuros"))));
        combinedSummary.add(List.of("Average green index", str(combined.get("avgGreenIndex"))));
        combinedSummary.add(List.of("Average legs", str(combined.get("avgLegs"))));
        combinedSummary.add(List.of("CO2 saved (kg)", str(combined.get("co2SavedKg"))));
        sections.add(new Section("Combined journeys — summary",
                List.of("Measure", "Value"), combinedSummary));

        // Order matters: a scooter before the bus is a different offer from a
        // scooter after it, and collapsing the two would erase the finding.
        List<List<String>> chainRows = new ArrayList<>();
        for (Map<String, Object> c : asRows(combined.get("chains"))) {
            chainRows.add(List.of(
                    str(c.get("label")),
                    str(c.get("journeys")),
                    str(c.get("share")) + "%",
                    str(c.get("avgDurationMin")),
                    str(c.get("avgDistanceKm")),
                    str(c.get("avgCostEuros")),
                    str(c.get("avgGreenIndex"))));
        }
        sections.add(new Section("Combined journeys — composition",
                List.of("Chain", "Journeys", "Share", "Avg minutes", "Avg km", "Avg EUR", "Avg green index"),
                chainRows));

        List<List<String>> durationRows = new ArrayList<>();
        asCounts(combined.get("durationBuckets"))
                .forEach((band, n) -> durationRows.add(List.of(band, str(n))));
        sections.add(new Section("Combined journeys — time taken",
                List.of("Duration", "Journeys"), durationRows));

        List<List<String>> compareRows = new ArrayList<>();
        for (Map<String, Object> c : asRows(combined.get("comparison"))) {
            compareRows.add(List.of(
                    str(c.get("label")),
                    str(c.get("journeys")),
                    str(c.get("avgDurationMin")),
                    str(c.get("avgDistanceKm")),
                    str(c.get("minutesPerKm")),
                    str(c.get("avgGreenIndex"))));
        }
        sections.add(new Section("Combined against single modes",
                List.of("Mode", "Journeys", "Avg minutes", "Avg km", "Min per km", "Avg green index"),
                compareRows));

        // ── Busiest routes. Stop names, never a traveller. ──
        List<List<String>> routeRows = new ArrayList<>();
        for (Map<String, Object> r : analytics.getTopRoutes(range)) {
            routeRows.add(List.of(
                    String.valueOf(r.getOrDefault("origin", "")),
                    String.valueOf(r.getOrDefault("dest", "")),
                    String.valueOf(r.getOrDefault("uses", ""))));
        }
        sections.add(new Section("Busiest routes",
                List.of("From", "To", "Journeys"), routeRows));

        return new Report(rangeLabel(range), ZonedDateTime.now(), sections);
    }

    private static String rangeLabel(String range) {
        // A custom period names its own dates: "Last month" on a report covering
        // 3-17 March would be a caption that contradicts its own figures.
        if (range != null && range.toUpperCase().startsWith("CUSTOM:")) {
            String[] parts = range.substring("CUSTOM:".length()).split(":");
            if (parts.length == 2) return parts[0].trim() + " to " + parts[1].trim();
        }
        return switch (range == null ? "1M" : range.toUpperCase()) {
            case "1W" -> "Last week";
            case "3M" -> "Last 3 months";
            case "6M" -> "Last 6 months";
            case "1Y" -> "Last year";
            default   -> "Last month";
        };
    }

    /** "—" for a figure that was never recorded, so a gap does not read as a zero. */
    private static String str(Object v) {
        return v == null ? "—" : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asRows(Object v) {
        return v instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asCounts(Object v) {
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    /** camelCase key -> "Camel case", so the report reads as prose not as JSON. */
    private static String humanise(String key) {
        String spaced = key.replaceAll("([a-z])([A-Z])", "$1 $2").replace('_', ' ');
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1).toLowerCase();
    }

    public String fileBaseName(Report r) {
        return "omnimove-analytics-"
             + r.generatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    }

    // ════════════════════════════════════════════════════════════════
    //  CSV
    // ════════════════════════════════════════════════════════════════

    /**
     * RFC 4180. Sections are separated by a blank line and introduced by their
     * title, so one file carries the whole report rather than five downloads.
     * A leading BOM so Excel opens UTF-8 as UTF-8 rather than as Latin-1.
     */
    public byte[] toCsv(Report report) {
        StringBuilder sb = new StringBuilder("﻿");
        sb.append(csvRow(List.of("OMNIMOVE — analytics report")))
          .append(csvRow(List.of("Period", report.range())))
          .append(csvRow(List.of("Generated", report.generatedAt().format(STAMP))))
          .append('\n');

        for (Section s : report.sections()) {
            sb.append(csvRow(List.of(s.title())));
            sb.append(csvRow(s.headers()));
            for (List<String> row : s.rows()) sb.append(csvRow(row));
            sb.append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String csvRow(List<String> cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(csvCell(cells.get(i)));
        }
        return sb.append("\r\n").toString();
    }

    private static String csvCell(String v) {
        String s = v == null ? "" : v;
        // Quote when the value contains a delimiter, a quote or a newline —
        // and double any quote inside, which is how RFC 4180 escapes them.
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0)
            return '"' + s.replace("\"", "\"\"") + '"';
        return s;
    }

    // ════════════════════════════════════════════════════════════════
    //  XLSX
    // ════════════════════════════════════════════════════════════════

    /**
     * A minimal but valid OOXML workbook, one sheet, written straight into a zip.
     *
     * <p>Strings go in the cells as {@code t="inlineStr"} rather than through a
     * shared-strings part: it costs a few bytes on a report this size and saves a
     * whole component that would have to be kept in step with the sheet.
     * Numbers are written as numbers so the columns can be summed and charted —
     * that is the only reason to offer this format over the CSV.
     */
    public byte[] toXlsx(Report report) {
        StringBuilder sheet = new StringBuilder();
        sheet.append("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <cols><col min="1" max="1" width="34"/><col min="2" max="4" width="18"/></cols>
                <sheetData>""");

        int row = 0;
        row = xlsxRow(sheet, ++row, List.of("OMNIMOVE — analytics report"), true);
        row = xlsxRow(sheet, row, List.of("Period", report.range()), false);
        row = xlsxRow(sheet, row, List.of("Generated", report.generatedAt().format(STAMP)), false);

        for (Section s : report.sections()) {
            row = xlsxRow(sheet, row, List.of(), false);            // blank spacer
            row = xlsxRow(sheet, row, List.of(s.title()), true);
            row = xlsxRow(sheet, row, s.headers(), true);
            for (List<String> r : s.rows()) row = xlsxRow(sheet, row, r, false);
        }
        sheet.append("</sheetData></worksheet>");

        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out)) {

            put(zip, "[Content_Types].xml", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                <Default Extension="xml" ContentType="application/xml"/>
                <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
                </Types>""");

            put(zip, "_rels/.rels", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                </Relationships>""");

            put(zip, "xl/workbook.xml", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                          xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                <sheets><sheet name="Analytics" sheetId="1" r:id="rId1"/></sheets>
                </workbook>""");

            put(zip, "xl/_rels/workbook.xml.rels", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
                </Relationships>""");

            // Two formats: plain, and bold for the headings. Style index 1 is the
            // bold one, referenced as s="1" on those cells.
            put(zip, "xl/styles.xml", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <fonts count="2"><font><sz val="11"/><name val="Calibri"/></font>
                <font><b/><sz val="11"/><name val="Calibri"/></font></fonts>
                <fills count="1"><fill><patternFill patternType="none"/></fill></fills>
                <borders count="1"><border/></borders>
                <cellStyleXfs count="1"><xf/></cellStyleXfs>
                <cellXfs count="2"><xf xfId="0"/><xf xfId="0" fontId="1" applyFont="1"/></cellXfs>
                </styleSheet>""");

            put(zip, "xl/worksheets/sheet1.xml", sheet.toString());
            zip.finish();
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not build the workbook", e);
        }
    }

    private static void put(java.util.zip.ZipOutputStream zip, String name, String body)
            throws java.io.IOException {
        zip.putNextEntry(new java.util.zip.ZipEntry(name));
        zip.write(body.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /** @return the next free row number, so callers never track it themselves. */
    private static int xlsxRow(StringBuilder sheet, int rowNum, List<String> cells, boolean bold) {
        if (cells.isEmpty()) return rowNum + 1;
        sheet.append("<row r=\"").append(rowNum).append("\">");
        for (int i = 0; i < cells.size(); i++) {
            String ref = columnLetter(i) + rowNum;
            String v   = cells.get(i) == null ? "" : cells.get(i);
            String st  = bold ? " s=\"1\"" : "";
            if (isNumeric(v)) {
                sheet.append("<c r=\"").append(ref).append('"').append(st).append("><v>")
                     .append(v).append("</v></c>");
            } else {
                sheet.append("<c r=\"").append(ref).append('"').append(st)
                     .append(" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                     .append(xml(v)).append("</t></is></c>");
            }
        }
        sheet.append("</row>");
        return rowNum + 1;
    }

    private static boolean isNumeric(String v) {
        if (v == null || v.isBlank()) return false;
        try { Double.parseDouble(v); return true; } catch (NumberFormatException e) { return false; }
    }

    private static String columnLetter(int index) {
        StringBuilder sb = new StringBuilder();
        int n = index;
        do { sb.insert(0, (char) ('A' + n % 26)); n = n / 26 - 1; } while (n >= 0);
        return sb.toString();
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ════════════════════════════════════════════════════════════════
    //  PDF
    // ════════════════════════════════════════════════════════════════

    // A4 at 72dpi, the unit PDF measures in.
    private static final float PAGE_W = 595f, PAGE_H = 842f;
    private static final float MARGIN = 48f;
    private static final float LINE   = 14f;

    /**
     * A laid-out A4 report, written directly as PDF.
     *
     * <p>The OMNIMOVE mark is drawn as text in the brand's two colours rather
     * than embedded as an image: the logo IS typographic — the app draws it the
     * same way — so there is nothing to embed, and the mark stays crisp at any
     * zoom instead of being a bitmap.
     *
     * <p>Only the fourteen standard PDF fonts are used, so nothing has to be
     * embedded and the file stays a few kilobytes whatever the reader has
     * installed.
     */
    public byte[] toPdf(Report report) {
        List<String> pages = new ArrayList<>();
        StringBuilder page = new StringBuilder();
        float y = PAGE_H - MARGIN;

        y = pdfHeader(page, report, y);

        for (Section s : report.sections()) {
            // Keep a heading with at least its column titles and one row: a
            // section title alone at the foot of a page reads as a mistake.
            if (y < MARGIN + LINE * 4) {
                pages.add(page.toString());
                page = new StringBuilder();
                y = PAGE_H - MARGIN;
            }
            y -= LINE;
            pdfText(page, MARGIN, y, 12, true, "0.06 0.09 0.16", s.title());
            y -= 4;
            pdfRule(page, y);
            y -= LINE;

            float[] cols = columnPositions(s.headers().size());
            pdfTableRow(page, y, cols, s.headers(), true);
            y -= LINE;

            for (List<String> row : s.rows()) {
                if (y < MARGIN + LINE) {
                    pages.add(page.toString());
                    page = new StringBuilder();
                    y = PAGE_H - MARGIN;
                    pdfTableRow(page, y, cols, s.headers(), true);   // repeat the headings
                    y -= LINE;
                }
                pdfTableRow(page, y, cols, row, false);
                y -= LINE;
            }
            y -= LINE / 2;
        }
        pages.add(page.toString());
        return assemblePdf(pages, report);
    }

    /** Wordmark, title, period, timestamp. Returns the y to carry on from. */
    private float pdfHeader(StringBuilder page, Report report, float y) {
        y -= 12;
        // "OMNI" dark, "MOVE" in the brand green — the same split as the app.
        // The second half starts exactly where the first ends: guessing the
        // offset left a visible gap and the wordmark read as two words.
        final float markSize = 22;
        pdfText(page, MARGIN, y, markSize, true, "0.06 0.09 0.16", "OMNI");
        pdfText(page, MARGIN + helveticaBoldWidth("OMNI", markSize), y, markSize,
                true, "0.06 0.73 0.51", "MOVE");

        y -= LINE + 4;
        pdfText(page, MARGIN, y, 13, true, "0.06 0.09 0.16", "Analytics report");
        y -= LINE;
        pdfText(page, MARGIN, y, 9, false, "0.42 0.45 0.50",
                "Period: " + report.range()
              + "     Generated: " + report.generatedAt().format(STAMP));
        y -= LINE - 2;
        pdfText(page, MARGIN, y, 9, false, "0.42 0.45 0.50",
                "Aggregate figures only — no personal data.");
        y -= 8;
        pdfRule(page, y);
        return y - 6;
    }

    /** Column x positions across the printable width. */
    private static float[] columnPositions(int count) {
        float usable = PAGE_W - MARGIN * 2;
        float[] xs = new float[count];
        if (count == 1) { xs[0] = MARGIN; return xs; }
        // The first column carries the labels and gets the room; the rest share
        // what is left, which keeps numbers in tidy right-hand columns.
        float first = usable * 0.46f;
        float other = (usable - first) / (count - 1);
        xs[0] = MARGIN;
        for (int i = 1; i < count; i++) xs[i] = MARGIN + first + other * (i - 1);
        return xs;
    }

    private void pdfTableRow(StringBuilder page, float y, float[] cols,
                             List<String> cells, boolean bold) {
        for (int i = 0; i < cells.size() && i < cols.length; i++) {
            pdfText(page, cols[i], y, 9, bold,
                    bold ? "0.29 0.33 0.39" : "0.06 0.09 0.16",
                    truncate(cells.get(i), i == 0 ? 44 : 22));
        }
    }

    private static String truncate(String v, int max) {
        String s = v == null ? "" : v;
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private void pdfText(StringBuilder page, float x, float y, float size,
                         boolean bold, String rgb, String text) {
        page.append("BT /").append(bold ? "F2" : "F1").append(' ').append(size).append(" Tf ")
            .append(rgb).append(" rg ")
            .append(x).append(' ').append(y).append(" Td (")
            .append(pdfEscape(text)).append(") Tj ET\n");
    }

    /**
     * Width of a string in Helvetica-Bold, from the font's own advance widths
     * (units of 1/1000 em). Only the characters the wordmark uses are listed —
     * this exists to butt two coloured halves together, not to lay out text.
     */
    private static float helveticaBoldWidth(String text, float size) {
        int units = 0;
        for (char c : text.toCharArray()) {
            units += switch (c) {
                case 'O' -> 778; case 'M' -> 889; case 'N' -> 722; case 'I' -> 278;
                case 'V' -> 667; case 'E' -> 667;
                default  -> 600;
            };
        }
        return units * size / 1000f;
    }

    private void pdfRule(StringBuilder page, float y) {
        page.append("0.85 0.87 0.90 RG 0.7 w ")
            .append(MARGIN).append(' ').append(y).append(" m ")
            .append(PAGE_W - MARGIN).append(' ').append(y).append(" l S\n");
    }

    /**
     * WinAnsi is the encoding of the standard fonts, so anything outside it
     * would render as the wrong glyph. Parentheses and backslashes are the
     * string delimiters and must be escaped.
     */
    private static String pdfEscape(String text) {
        StringBuilder sb = new StringBuilder();
        for (char c : (text == null ? "" : text).toCharArray()) {
            switch (c) {
                case '(', ')', '\\' -> sb.append('\\').append(c);
                case '…' -> sb.append("...");
                case '—', '–' -> sb.append('-');
                case '·' -> sb.append('-');
                default -> sb.append(c < 32 || c > 126 ? '?' : c);
            }
        }
        return sb.toString();
    }

    /** Objects, cross-reference table, trailer. */
    private byte[] assemblePdf(List<String> pages, Report report) {
        int pageCount = pages.size();
        // 1 catalog, 2 pages tree, 3 F1, 4 F2, then a page + its stream each
        int firstPageObj = 5;
        int objectCount  = 4 + pageCount * 2;

        List<String> objects = new ArrayList<>();
        objects.add("<< /Type /Catalog /Pages 2 0 R >>");

        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < pageCount; i++) kids.append(firstPageObj + i * 2).append(" 0 R ");
        objects.add("<< /Type /Pages /Count " + pageCount + " /Kids [" + kids.toString().trim() + "] >>");
        objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>");
        objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>");

        for (int i = 0; i < pageCount; i++) {
            int contentObj = firstPageObj + i * 2 + 1;
            objects.add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + PAGE_W + " " + PAGE_H + "]"
                      + " /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >>"
                      + " /Contents " + contentObj + " 0 R >>");
            String body = pages.get(i);
            objects.add("<< /Length " + body.getBytes(StandardCharsets.ISO_8859_1).length + " >>\n"
                      + "stream\n" + body + "endstream");
        }

        StringBuilder pdf = new StringBuilder("%PDF-1.4\n");
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(pdf.length());
            pdf.append(i + 1).append(" 0 obj\n").append(objects.get(i)).append("\nendobj\n");
        }

        int xref = pdf.length();
        pdf.append("xref\n0 ").append(objectCount + 1).append("\n0000000000 65535 f \n");
        for (int off : offsets) pdf.append(String.format("%010d 00000 n %n", off));
        pdf.append("trailer\n<< /Size ").append(objectCount + 1)
           .append(" /Root 1 0 R /Info << /Title (OMNIMOVE analytics report)")
           .append(" /Producer (OMNIMOVE) >> >>\nstartxref\n")
           .append(xref).append("\n%%EOF");

        return pdf.toString().getBytes(StandardCharsets.ISO_8859_1);
    }
}
