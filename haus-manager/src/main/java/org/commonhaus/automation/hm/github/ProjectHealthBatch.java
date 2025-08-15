package org.commonhaus.automation.hm.github;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.commonhaus.automation.github.stats.ProjectHealthReport;
import org.commonhaus.automation.hm.ProjectManager.ProjectConfigState;
import org.kohsuke.github.GHRepository;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.logging.Log;

public class ProjectHealthBatch {
    private static final String ME = "🩺-batch";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final long installationId;
    private final String sourceRepoFullName;
    private final LocalDate startDate;
    private final ReportQueryContext rqc;
    private final Map<String, ProjectHealthReport> reports = new HashMap<>();
    private final ObjectMapper objectMapper;

    public ProjectHealthBatch(ProjectConfigState state, LocalDate startDate,
            ReportQueryContext rqc, ObjectMapper objectMapper) {
        this.sourceRepoFullName = state.repoFullName();
        this.installationId = state.installationId();
        this.startDate = startDate;
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

        // Prepare payload with all reports
        Map<String, Object> payload = Map.of(
                "start_date", startDate.format(DATE_FORMAT),
                "reports", reports.entrySet().stream()
                        .collect(Collectors.toMap(
                                Map.Entry::getKey,
                                e -> {
                                    try {
                                        return objectMapper.writeValueAsString(e.getValue());
                                    } catch (Exception ex) {
                                        Log.errorf(ex, "[%s] Failed to serialize report for %s", ME, e.getKey());
                                        return "{}";
                                    }
                                })));

        try {
            GHRepository repo = rqc.getRepository();
            repo.dispatch("health-reports", payload);

            // Fine-grained PAT does not seem to have access to create blobs directly
            // (docs seem to indicate this shouldn't be the case)
            //
            // String baseBranch = repo.getDefaultBranch();
            // GHRef mainRef = repo.getRef("heads/" + baseBranch);
            // String baseCommitSha = mainRef.getObject().getSha();
            //
            // GHTreeBuilder treeBuilder = repo.createTree().baseTree(baseCommitSha);
            // String dateFolder = "reports/" + anchorDate.format(DATE_FORMAT);
            //
            // for (Map.Entry<String, ProjectHealthReport> entry : reports.entrySet()) {
            //      String repoFullName = entry.getKey();
            //      String fileName = dateFolder + "/" + repoFullName.replace("/", "_") +".json";
            //      String content = objectMapper.writeValueAsString(entry.getValue());
            //      treeBuilder.add(fileName, content, false);
            // }
            // GHTree tree = treeBuilder.create();
            // String commitMessage = """
            //         Add project health reports for %s
            //
            //         Generated health reports for %d repositories.
            //         """.formatted(anchorDate, reports.size());
            //
            // GHCommit commit = repo.createCommit()
            //         .message(commitMessage)
            //         .tree(tree.getSha())
            //         .parent(baseCommitSha)
            //         .create();
            //
            // // Update branch reference
            // mainRef.updateTo(commit.getSHA1());
            //
            // Log.infof("[%s] Committed %d health reports to %s at %s",
            // ME, reports.size(), sourceRepoFullName, commit.getSHA1());
        } catch (Exception e) {
            rqc.addException(e);
            rqc.logAndSendContextErrors("Error committing health reports for " +
                    sourceRepoFullName);
        }
    }

    private void logDryRunReports() {
        Log.infof("[%s] Dry run: would commit %d reports for %s", ME, reports.size(), startDate);

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

    public LocalDate getStartDate() {
        return startDate;
    }

    public long installationId() {
        return installationId;
    }
}
