package it.unicas.cassitrack.controller;

import it.unicas.cassitrack.dto.ReportRequest;
import it.unicas.cassitrack.service.ManagerActivityService;
import it.unicas.cassitrack.service.ReportExportService;
import it.unicas.cassitrack.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * One endpoint, three formats, one layout engine.
 *
 * <p>Before this, every table offered a CSV built in the browser and the
 * "PDF report" button called {@code window.print()} — which prints the
 * dashboard, chrome and all, at whatever size the window happens to be. A
 * printed screen is not a report you can file.
 */
@RestController
@RequestMapping("/api/v1/reports")
public class ReportExportController {

    private static final Logger log = LoggerFactory.getLogger(ReportExportController.class);

    private final ReportExportService exportService;
    private final ManagerActivityService activityService;
    private final UserService userService;

    public ReportExportController(ReportExportService exportService,
                                  ManagerActivityService activityService,
                                  UserService userService) {
        this.exportService = exportService;
        this.activityService = activityService;
        this.userService = userService;
    }

    /**
     * Caps, so one request cannot ask for a file the server has to hold in
     * memory whole. Generous against any real table in the fleet manager, and
     * far below what would hurt.
     */
    private static final int MAX_SECTIONS = 12;
    private static final int MAX_ROWS     = 20_000;
    private static final int MAX_COLUMNS  = 40;
    private static final int MAX_CELL     = 500;

    @PostMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(defaultValue = "csv") String format,
                                         @RequestBody ReportRequest req) {

        List<ReportExportService.Section> sections = new ArrayList<>();
        int totalRows = 0;

        for (ReportRequest.SectionDto s : safe(req.getSections())) {
            if (sections.size() >= MAX_SECTIONS) break;

            List<String> headers = clampRow(s.getHeaders());
            List<List<String>> rows = new ArrayList<>();
            for (List<String> r : safe(s.getRows())) {
                if (++totalRows > MAX_ROWS) break;
                rows.add(clampRow(r));
            }
            sections.add(new ReportExportService.Section(s.getTitle(), headers, rows));
        }

        if (sections.isEmpty())
            return ResponseEntity.badRequest().build();

        ReportExportService.Report report = new ReportExportService.Report(
                req.getTitle(), req.getSubtitle(), ZonedDateTime.now(), sections);

        String base = exportService.fileBaseName(report);
        byte[] body;
        String type, ext;

        try {
            switch (format == null ? "csv" : format.toLowerCase()) {
                case "xlsx" -> {
                    body = exportService.toXlsx(report);
                    type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                    ext  = "xlsx";
                }
                case "pdf" -> {
                    body = exportService.toPdf(report);
                    type = MediaType.APPLICATION_PDF_VALUE;
                    ext  = "pdf";
                }
                default -> {
                    body = exportService.toCsv(report);
                    type = "text/csv; charset=UTF-8";
                    ext  = "csv";
                }
            }
        } catch (RuntimeException e) {
            log.error("Could not render the {} export: {}", format, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }

        // Every download in the fleet manager comes through here, which is what
        // makes one line enough to answer "what has this operator taken out".
        // After the file is built, never before: a render that failed is not a
        // download, and the register should not claim it was.
        // Counted from what actually went into the file, not from the loop
        // counter above, which overshoots by one when the row cap is reached.
        recordDownload(report, format,
                sections.stream().mapToInt(sec -> sec.rows().size()).sum());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, type)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + base + "." + ext + "\"")
                // The browser reads the name off this header; without exposing it
                // a cross-origin fetch sees no filename at all.
                .header("Access-Control-Expose-Headers", HttpHeaders.CONTENT_DISPOSITION)
                .body(body);
    }

    /**
     * Notes the download against whoever is signed in.
     *
     * <p>Best-effort on purpose: the manager asked for a file they are entitled
     * to, and a bookkeeping failure must not turn that into an error page. It is
     * logged instead, which is the one thing that must not be silent.
     */
    private void recordDownload(ReportExportService.Report report, String format, int rows) {
        try {
            var auth = org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication();
            if (auth == null || auth.getName() == null) return;

            var user = userService.getUserByEmail(auth.getName());
            if (user == null) return;

            activityService.recordExport(user.getId(), report.title(),
                    format == null ? "csv" : format.toLowerCase(), rows, report.subtitle());
        } catch (Exception e) {
            log.warn("Could not record the download of '{}': {}", report.title(), e.getMessage());
        }
    }

    private static <T> List<T> safe(List<T> v) { return v == null ? List.of() : v; }

    /** Trims a row to the column cap and each cell to the length cap. */
    private static List<String> clampRow(List<String> row) {
        List<String> out = new ArrayList<>();
        for (String cell : safe(row)) {
            if (out.size() >= MAX_COLUMNS) break;
            String v = cell == null ? "" : cell;
            out.add(v.length() > MAX_CELL ? v.substring(0, MAX_CELL) : v);
        }
        return out;
    }
}
