package org.commonhaus.automation.github.rules;

import java.util.List;

import org.commonhaus.automation.github.EventData;
import org.commonhaus.automation.github.model.EventQueryContext;
import org.commonhaus.automation.github.model.EventType;
import org.commonhaus.automation.github.model.QueryCache;
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

    public boolean matches(EventQueryContext queryContext) {
        if (queryContext.getEventType() != EventType.pull_request) {
            return false;
        }
        if (paths == null || paths.isEmpty()) {
            // it is a pull request, but no paths are specified, so it matches
            return true;
        }

        EventData eventData = queryContext.getEventData();
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
                            MatchingEngine matchingEngine = compileGlob(p);
                            if (matchingEngine.matches(changedFile.getFilename())) {
                                return true;
                            }
                        } catch (Exception e) {
                            Log.errorf(e, "[%s] Error evaluating glob expression %s: %s",
                                    queryContext.getLogId(), p, e.toString());
                        }
                    }
                }
            }
        }
        return false;
    }

    MatchingEngine compileGlob(String filenamePattern) {
        MatchingEngine me = QueryCache.GLOB.getCachedValue(filenamePattern);
        if (me == null) {
            me = QueryCache.GLOB.putCachedValue(filenamePattern, GlobPattern.compile(filenamePattern));
        }
        return me;
    }
}
