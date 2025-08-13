package org.commonhaus.automation.hm.github;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

import org.commonhaus.automation.github.stats.ProjectHealthReport;
import org.commonhaus.automation.hm.ProjectManager.ProjectConfigState;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.logging.Log;

public class ProjectHealthBatch {
    private static final String ME = "🩺-batch";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final long installationId;
    private final String sourceRepoFullName;
    private final LocalDate collectionDate;
    private final ReportQueryContext rqc;
    private final Map<String, ProjectHealthReport> reports = new HashMap<>();
    private final ObjectMapper objectMapper;

    public ProjectHealthBatch(ProjectConfigState state, LocalDate collectionDate,
            ReportQueryContext rqc, ObjectMapper objectMapper) {
        this.sourceRepoFullName = state.repoFullName();
        this.installationId = state.installationId();
        this.collectionDate = collectionDate;
        this.rqc = rqc;
        this.objectMapper = objectMapper;
    }

    public void addReport(String repoFullName, ProjectHealthReport report) {
        Log.debugf("[%s] Adding health report for %s to batch", ME, repoFullName);
        reports.put(repoFullName, report);
    }

    public void commitAllReports() {
        if (reports.isEmpty()) {
            Log.debugf("[%s] No reports to commit for %s", ME, sourceRepoFullName);
            return;
        }

        if (rqc.isDryRun()) {
            logDryRunReports();
            return;
        }
    }

    private void logDryRunReports() {
        Log.infof("[%s] Dry run: would commit %d reports for %s", ME, reports.size(), collectionDate);

        for (Map.Entry<String, ProjectHealthReport> entry : reports.entrySet()) {
            try {
                String reportJson = objectMapper.writeValueAsString(entry.getValue());
                Log.infof("[%s] Dry run report for %s: %s", ME, entry.getKey(), reportJson);
            } catch (Exception e) {
                Log.errorf(e, "[%s] Error serializing dry run report for %s", ME, entry.getKey());
            }
        }
    }

    public int getReportCount() {
        return reports.size();
    }

    public String getSourceRepoFullName() {
        return sourceRepoFullName;
    }

    public LocalDate getCollectionDate() {
        return collectionDate;
    }

    public long installationId() {
        return installationId;
    }
}
