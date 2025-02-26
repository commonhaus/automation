package org.commonhaus.automation.github.hr.rules;

import java.util.List;

import org.commonhaus.automation.github.context.EventData;
import org.commonhaus.automation.github.context.EventType;
import org.commonhaus.automation.github.context.QueryCache;
import org.commonhaus.automation.github.hr.EventQueryContext;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHPullRequestFileDetail;
import org.kohsuke.github.PagedIterable;

import com.hrakaroo.glob.GlobPattern;
import com.hrakaroo.glob.MatchingEngine;

import io.quarkus.logging.Log;

/**
 * Only applies to GitHub Pull Requests
 */
public class MatchPaths {
    static final QueryCache GLOB = QueryCache.create(
            "GLOB", b -> b.maximumSize(200));

    List<String> paths;

    public MatchPaths() {
        this.paths = List.of();
    }

    public MatchPaths(List<String> paths) {
        this.paths = paths;
    }

    public boolean matches(EventQueryContext qc) {
        if (qc.getEventType() != EventType.pull_request) {
            return false;
        }
        if (paths == null || paths.isEmpty()) {
            // it is a pull request, but no paths are specified, so it matches
            return true;
        }

        EventData eventData = qc.getEventData();
        GHEventPayload.PullRequest pullRequest = eventData.getGHEventPayload();
        GHPullRequest ghPullRequest = pullRequest.getPullRequest();

        PagedIterable<GHPullRequestFileDetail> prFiles = ghPullRequest.listFiles();
        if (prFiles != null) {
            for (GHPullRequestFileDetail changedFile : prFiles) {
                for (String p : paths) {
                    if (!p.contains("*")) {
                        if (changedFile.getFilename().startsWith(p)) {
                            return true;
                        }
                    } else {
                        try {
                            MatchingEngine matchingEngine = GLOB.computeIfAbsent(p,
                                    k -> GlobPattern.compile(p));
                            if (matchingEngine.matches(changedFile.getFilename())) {
                                return true;
                            }
                        } catch (Exception e) {
                            Log.errorf(e, "[%s] Error evaluating glob expression %s: %s",
                                    qc.getLogId(), p, e.toString());
                        }
                    }
                }
            }
        }
        return false;
    }
}
