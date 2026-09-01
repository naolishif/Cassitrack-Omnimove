package it.unicas.cassitrack.service;

import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a table the fleet manager is looking at into a file worth keeping.
 *
 * <p>Three formats off one dataset, so a CSV and a PDF of the same table can
 * never disagree: the caller assembles the rows once and the renderers only lay
 * them out. Same shape as OMNIMOVE's analytics export, and for the same reason.
 *
 * <p><b>Where the rows come from.</b> They arrive from the browser, already
 * filtered, rather than being queried again here. That is deliberate: every
 * table in the fleet manager filters and sorts client-side, and re-deriving
 * those filters on this side would be two implementations of one behaviour,
 * drifting apart at the first change. What you download stays what you were
 * looking at — the property the CSV export had from the beginning — and what
 * moves to the server is only the rendering, which the browser has no good way
 * of doing for XLSX and PDF.
 *
 * <p><b>No new dependencies.</b> An .xlsx is a zip of XML parts and a PDF is a
 * text format with an offset table, and both are written here directly. Pulling
 * in POI or a PDF toolkit for a handful of tables would add a large transitive
 * surface to a build that is scanned for CVEs.
 */
@Service
public class ReportExportService {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /** One table: a heading, column titles, and rows. */
    public record Section(String title, List<String> headers, List<List<String>> rows) {}

    /** Everything the three renderers share. */
    public record Report(String title, String subtitle,
                         ZonedDateTime generatedAt, List<Section> sections) {}

    public String fileBaseName(Report r) {
        String slug = (r.title() == null ? "cassitrack" : r.title())
                .toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (slug.isBlank()) slug = "cassitrack";
        return slug + "-" + r.generatedAt().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"));
    }

    // ════════════════════════════════════════════════════════════════
    //  CSV
    // ════════════════════════════════════════════════════════════════

    /**
     * Semicolons and a BOM, not commas.
     *
     * <p>RFC 4180 says comma, and on an Italian Windows this is the difference
     * between a spreadsheet and a single column of text: Excel reads the list
     * separator from the locale, and here that is the semicolon. The BOM is the
     * other half of the same problem — without it the accented stop names open
     * as Latin-1. The quoting rules are RFC 4180's, only around a different
     * delimiter.
     */
    public byte[] toCsv(Report report) {
        StringBuilder sb = new StringBuilder("﻿");
        sb.append(csvRow(List.of("CASSITRACK — " + nz(report.title()))));
        if (notBlank(report.subtitle())) sb.append(csvRow(List.of("Filter", report.subtitle())));
        sb.append(csvRow(List.of("Generated", report.generatedAt().format(STAMP))))
          .append("\r\n");

        for (Section s : report.sections()) {
            if (notBlank(s.title())) sb.append(csvRow(List.of(s.title())));
            sb.append(csvRow(s.headers()));
            for (List<String> row : s.rows()) sb.append(csvRow(row));
            sb.append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String csvRow(List<String> cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) sb.append(';');
            sb.append(csvCell(cells.get(i)));
        }
        return sb.append("\r\n").toString();
    }

    private static String csvCell(String v) {
        String s = v == null ? "" : v;
        if (s.indexOf(';') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0)
            return '"' + s.replace("\"", "\"\"") + '"';
        return s;
    }

    // ════════════════════════════════════════════════════════════════
    //  XLSX
    // ════════════════════════════════════════════════════════════════

    /**
     * A workbook, not a CSV with a different extension.
     *
     * <p>What earns the format over the CSV: numbers are written as numbers, so
     * a column of delays can be summed and charted; the header row is frozen, so
     * it stays put on a table of four hundred runs; it carries an autofilter, so
     * the manager can narrow it further without leaving Excel; and the columns
     * are sized from their own content rather than all landing at the default
     * width. A file that has to be reformatted before it can be read is a file
     * nobody opens twice.
     *
     * <p>Strings go in as {@code t="inlineStr"} rather than through a
     * shared-strings part: it costs a few bytes and saves a whole component that
     * would have to be kept in step with the sheet.
     */
    public byte[] toXlsx(Report report) {
        List<Section> sections = report.sections();

        // The header block, then each section. Row numbers are tracked as we go
        // so the freeze and the autofilter can name the right one.
        StringBuilder body = new StringBuilder();
        int row = 0;
        row = xlsxRow(body, ++row, List.of("CASSITRACK — " + nz(report.title())), true);
        if (notBlank(report.subtitle()))
            row = xlsxRow(body, row, List.of("Filter", report.subtitle()), false);
        row = xlsxRow(body, row, List.of("Generated", report.generatedAt().format(STAMP)), false);

        int firstHeaderRow = 0;
        int lastDataRow = 0;
        int widestRow = 1;

        for (Section s : sections) {
            row = xlsxRow(body, row, List.of(), false);              // spacer
            if (notBlank(s.title())) row = xlsxRow(body, row, List.of(s.title()), true);
            if (firstHeaderRow == 0) firstHeaderRow = row;
            row = xlsxRow(body, row, s.headers(), true);
            for (List<String> r : s.rows()) row = xlsxRow(body, row, r, false);
            lastDataRow = row - 1;
            widestRow = Math.max(widestRow, s.headers().size());
        }

        // Only a single-table export can freeze and filter meaningfully: with two
        // tables stacked in one sheet there is no one header row to pin.
        boolean single = sections.size() == 1 && firstHeaderRow > 0;

        StringBuilder sheet = new StringBuilder();
        sheet.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
             .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
        if (single) {
            sheet.append("<sheetViews><sheetView workbookViewId=\"0\">")
                 .append("<pane ySplit=\"").append(firstHeaderRow)
                 .append("\" topLeftCell=\"A").append(firstHeaderRow + 1)
                 .append("\" activePane=\"bottomLeft\" state=\"frozen\"/>")
                 .append("</sheetView></sheetViews>");
        }
        sheet.append(columnWidths(sections, widestRow));
        sheet.append("<sheetData>").append(body).append("</sheetData>");
        if (single && lastDataRow >= firstHeaderRow) {
            sheet.append("<autoFilter ref=\"A").append(firstHeaderRow)
                 .append(':').append(columnLetter(widestRow - 1)).append(lastDataRow)
                 .append("\"/>");
        }
        sheet.append("</worksheet>");

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
                <sheets><sheet name="Export" sheetId="1" r:id="rId1"/></sheets>
                </workbook>""");

            put(zip, "xl/_rels/workbook.xml.rels", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
                </Relationships>""");

            // Style 1 is bold on a pale fill — the header rows. Plain is style 0.
            put(zip, "xl/styles.xml", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <fonts count="2"><font><sz val="11"/><name val="Calibri"/></font>
                <font><b/><sz val="11"/><color rgb="FF0F172A"/><name val="Calibri"/></font></fonts>
                <fills count="3"><fill><patternFill patternType="none"/></fill>
                <fill><patternFill patternType="gray125"/></fill>
                <fill><patternFill patternType="solid"><fgColor rgb="FFE8EEF9"/><bgColor indexed="64"/></patternFill></fill></fills>
                <borders count="1"><border/></borders>
                <cellStyleXfs count="1"><xf/></cellStyleXfs>
                <cellXfs count="2"><xf xfId="0"/>
                <xf xfId="0" fontId="1" fillId="2" applyFont="1" applyFill="1"/></cellXfs>
                </styleSheet>""");

            put(zip, "xl/worksheets/sheet1.xml", sheet.toString());
            zip.finish();
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not build the workbook", e);
        }
    }

    /** Widths from the longest cell in each column, clamped either side of readable. */
    private static String columnWidths(List<Section> sections, int columnCount) {
        int[] widest = new int[columnCount];
        for (Section s : sections) {
            measure(widest, s.headers());
            for (List<String> r : s.rows()) measure(widest, r);
        }
        StringBuilder sb = new StringBuilder("<cols>");
        for (int i = 0; i < columnCount; i++) {
            int w = Math.max(10, Math.min(46, widest[i] + 3));
            sb.append("<col min=\"").append(i + 1).append("\" max=\"").append(i + 1)
              .append("\" width=\"").append(w).append("\" customWidth=\"1\"/>");
        }
        return sb.append("</cols>").toString();
    }

    private static void measure(int[] widest, List<String> cells) {
        for (int i = 0; i < cells.size() && i < widest.length; i++) {
            String v = cells.get(i);
            if (v != null) widest[i] = Math.max(widest[i], v.length());
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

    /**
     * Numbers become numbers; anything that only looks like one does not.
     *
     * <p>A plate, a run id and a stop code are digits that must stay text: read
     * as numbers they lose their leading zeros and "01" becomes 1, which is a
     * different line. So a value is only numeric if it round-trips — parsing it
     * and printing it back gives the same characters.
     */
    private static boolean isNumeric(String v) {
        if (v == null || v.isBlank()) return false;
        if (!v.matches("-?\\d+(\\.\\d+)?")) return false;
        try {
            double d = Double.parseDouble(v);
            String back = (d == Math.floor(d) && !v.contains("."))
                    ? String.valueOf((long) d)
                    : String.valueOf(d);
            return back.equals(v);
        } catch (NumberFormatException e) {
            return false;
        }
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
    private static final float MARGIN = 44f;
    private static final float LINE   = 13f;

    /** The wordmark's two halves: slate, then the app's blue (#3B82F6). */
    private static final String INK   = "0.06 0.09 0.16";
    private static final String BLUE  = "0.23 0.51 0.96";
    private static final String MUTED = "0.42 0.45 0.50";
    private static final String RULE  = "0.85 0.87 0.90";

    /**
     * A laid-out A4 report rather than a screenshot of a dashboard.
     *
     * <p>The CASSITRACK mark is drawn as text in the brand's two colours rather
     * than embedded as an image: the logo IS typographic — the app draws it the
     * same way, "CASSI" in slate and "TRACK" in blue — so there is nothing to
     * embed, and it stays crisp at any zoom instead of being a bitmap.
     *
     * <p>Only the fourteen standard PDF fonts are used, so nothing has to be
     * embedded and the file stays a few kilobytes whatever the reader has.
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
            if (notBlank(s.title())) {
                y -= LINE;
                pdfText(page, MARGIN, y, 12, true, INK, s.title());
                y -= 4;
                pdfRule(page, y);
                y -= LINE;
            }

            float[] cols = columnPositions(s.headers().size());
            int[] widths = columnChars(s.headers().size());
            pdfTableRow(page, y, cols, widths, s.headers(), true);
            y -= 3;
            pdfRule(page, y);
            y -= LINE - 3;

            for (List<String> row : s.rows()) {
                if (y < MARGIN + LINE) {
                    pages.add(page.toString());
                    page = new StringBuilder();
                    y = PAGE_H - MARGIN;
                    pdfTableRow(page, y, cols, widths, s.headers(), true);   // repeat the headings
                    y -= 3;
                    pdfRule(page, y);
                    y -= LINE - 3;
                }
                pdfTableRow(page, y, cols, widths, row, false);
                y -= LINE;
            }
            y -= LINE / 2;
        }
        pages.add(page.toString());
        return assemblePdf(pages, report);
    }

    /** Wordmark, title, filter, timestamp, row count. Returns the y to carry on from. */
    private float pdfHeader(StringBuilder page, Report report, float y) {
        y -= 14;
        // "CASSI" slate, "TRACK" blue — the same split as the app's header. The
        // second half starts exactly where the first ends: guessing the offset
        // leaves a visible gap and the wordmark reads as two words.
        final float markSize = 22;
        pdfText(page, MARGIN, y, markSize, true, INK, "CASSI");
        pdfText(page, MARGIN + helveticaBoldWidth("CASSI", markSize), y, markSize,
                true, BLUE, "TRACK");

        // The owner, opposite the mark, where a report says who issued it
        pdfText(page, PAGE_W - MARGIN - helveticaWidth("UNICAS · Cassino", 9), y + 4, 9,
                false, MUTED, "UNICAS - Cassino");

        y -= LINE + 6;
        pdfText(page, MARGIN, y, 13, true, INK, nz(report.title()));

        y -= LINE;
        String meta = "Generated: " + report.generatedAt().format(STAMP)
                    + "     Rows: " + report.sections().stream().mapToInt(s -> s.rows().size()).sum();
        pdfText(page, MARGIN, y, 9, false, MUTED, meta);

        if (notBlank(report.subtitle())) {
            y -= LINE - 2;
            pdfText(page, MARGIN, y, 9, false, MUTED, "Filter: " + report.subtitle());
        }

        y -= 8;
        pdfRule(page, y);
        return y - 6;
    }

    /**
     * Column x positions across the printable width.
     *
     * <p>Even columns, unlike the analytics report next door: these are tables of
     * ids, plates and times where no single column is the label the others hang
     * off, so giving the first one half the page would waste it.
     */
    private static float[] columnPositions(int count) {
        float usable = PAGE_W - MARGIN * 2;
        float[] xs = new float[Math.max(1, count)];
        float each = usable / Math.max(1, count);
        for (int i = 0; i < xs.length; i++) xs[i] = MARGIN + each * i;
        return xs;
    }

    /** How many characters fit in one column before it runs into the next. */
    private static int[] columnChars(int count) {
        float usable = PAGE_W - MARGIN * 2;
        float each = usable / Math.max(1, count);
        int chars = Math.max(6, (int) (each / 4.6f));   // ~4.6pt per char at 9pt Helvetica
        int[] w = new int[Math.max(1, count)];
        java.util.Arrays.fill(w, chars);
        return w;
    }

    private void pdfTableRow(StringBuilder page, float y, float[] cols, int[] widths,
                             List<String> cells, boolean bold) {
        for (int i = 0; i < cells.size() && i < cols.length; i++) {
            pdfText(page, cols[i], y, 9, bold, bold ? "0.29 0.33 0.39" : INK,
                    truncate(cells.get(i), widths[i]));
        }
    }

    private static String truncate(String v, int max) {
        String s = v == null ? "" : v;
        return s.length() <= max ? s : s.substring(0, Math.max(1, max - 1)) + "…";
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
                case 'C' -> 722; case 'A' -> 722; case 'S' -> 667; case 'I' -> 278;
                case 'T' -> 611; case 'R' -> 722; case 'K' -> 722;
                default  -> 600;
            };
        }
        return units * size / 1000f;
    }

    /** Rough Helvetica width, enough to right-align one short line. */
    private static float helveticaWidth(String text, float size) {
        return text.length() * 0.5f * size;
    }

    private void pdfRule(StringBuilder page, float y) {
        page.append(RULE).append(" RG 0.7 w ")
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
                case '→' -> sb.append("->");
                case '€' -> sb.append("EUR ");
                case 'à' -> sb.append('a'); case 'è', 'é' -> sb.append('e');
                case 'ì' -> sb.append('i'); case 'ò' -> sb.append('o'); case 'ù' -> sb.append('u');
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
            String bodyText = pages.get(i);
            objects.add("<< /Length " + bodyText.getBytes(StandardCharsets.ISO_8859_1).length + " >>\n"
                      + "stream\n" + bodyText + "endstream");
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
           .append(" /Root 1 0 R /Info << /Title (CASSITRACK ")
           .append(pdfEscape(nz(report.title())))
           .append(") /Producer (CASSITRACK) >> >>\nstartxref\n")
           .append(xref).append("\n%%EOF");

        return pdf.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
    private static String nz(String s) { return s == null ? "Export" : s; }
}
