package it.unicas.cassitrack.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * A table the fleet manager is looking at, sent up to be rendered.
 *
 * <p>The rows travel rather than being queried again: every table in the fleet
 * manager filters and sorts in the browser, and what the manager wants a copy of
 * is what is on the screen, filters included. See {@code ReportExportService}.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReportRequest {

    /** What the report is called: "Buses", "Stops", "Timetable"… */
    private String title;

    /** The filters that produced these rows, as the screen words them. Optional. */
    private String subtitle;

    private List<SectionDto> sections = new ArrayList<>();

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SectionDto {
        private String title;
        private List<String> headers = new ArrayList<>();
        private List<List<String>> rows = new ArrayList<>();
    }
}
