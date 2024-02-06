package org.commonhaus.automation.github.rules;

import java.util.List;

import org.commonhaus.automation.github.EventData;
import org.commonhaus.automation.github.QueryHelper;
import org.commonhaus.automation.github.QueryHelper.QueryContext;
import org.commonhaus.automation.github.model.EventType;
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
    List<String> paths;

    public boolean matches(QueryContext queryContext) {
        EventData eventData = queryContext.getEventData();
        if (paths == null || paths.isEmpty() || eventData == null || eventData.getEventType() != EventType.pull_request) {
            return false;
        }

        GHEventPayload.PullRequest pullRequest = eventData.getGHEventPayload(GHEventPayload.PullRequest.class);
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
                            MatchingEngine matchingEngine = compileGlob(p);
                            if (matchingEngine.matches(changedFile.getFilename())) {
                                return true;
                            }
                        } catch (Exception e) {
                            Log.error("Error evaluating glob expression: " + p, e);
                        }
                    }
                }
            }
        }
        return false;
    }

    MatchingEngine compileGlob(String filenamePattern) {
        MatchingEngine me = QueryHelper.QueryCache.GLOB.getCachedValue(filenamePattern, MatchingEngine.class);
        if (me == null) {
            me = QueryHelper.QueryCache.GLOB.putCachedValue(filenamePattern, GlobPattern.compile(filenamePattern));
        }
        return me;
    }
}
