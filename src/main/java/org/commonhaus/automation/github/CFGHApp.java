package org.commonhaus.automation.github;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.commonhaus.automation.BotConfig;
import org.kohsuke.github.GHApp;
import org.kohsuke.github.GHAppInstallation;
import org.kohsuke.github.GHAuthenticatedAppInstallation;
import org.kohsuke.github.GHIOException;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import io.quarkiverse.githubapp.runtime.github.GitHubService;
import io.quarkus.logging.Log;

@Singleton
public class CFGHApp {
    static final DateTimeFormatter DATE_TIME_PARSER_SLASHES = DateTimeFormatter
            .ofPattern("yyyy/MM/dd HH:mm:ss Z");

    static Map<String, CFGHRepoInfo> repositoryInfoCache = new HashMap<>();

    private final BotConfig quarkusBotConfig;
    private final GitHubService gitHubService;

    @Inject
    public CFGHApp(BotConfig quarkusBotConfig, GitHubService gitHubService) {
        this.quarkusBotConfig = quarkusBotConfig;
        this.gitHubService = gitHubService;
    }

    public void initializeCache() {
        if (!repositoryInfoCache.isEmpty()) {
            return;
        }

        GitHub ac = gitHubService.getApplicationClient();
        try {
            GHApp ghApp = ac.getApp();
            for (GHAppInstallation ghAppInstallation : ghApp.listInstallations()) {
                GitHub ic = gitHubService.getInstallationClient(ghAppInstallation.getId());
                GHAuthenticatedAppInstallation ghai = ic.getInstallation();
                
                for (GHRepository ghRepository : ghai.listRepositories()) {
                    CFGHRepoInfo repositoryInfo = new CFGHRepoInfo(ghRepository, ghAppInstallation.getId());
                    CFGHQueryHelper queryContext = new CFGHQueryHelper(quarkusBotConfig,
                            ghRepository, ghAppInstallation.getId(), gitHubService);
                    if (queryContext.hasErrors()) {
                        Log.warnf("Unable to cache repository information", ghRepository.getFullName());
                        throw new CFHGCacheException(queryContext);
                    } else if (Log.isInfoEnabled()) {
                        repositoryInfo.logRepositoryInformation();
                    }
                    repositoryInfoCache.put(ghRepository.getFullName(), repositoryInfo);
                }
            }
        } catch (GHIOException e) {
            // TODO: Config to handle GHIOException (retry? quit? ensure notification?)
            e.getResponseHeaderFields().forEach((k, v) -> Log.debugf("%s: %s", k, v));
            throw new IllegalStateException(e);
        } catch (IOException e) {
            // TODO: Config to handle IOException (retry? quit? ensure notification?)
            throw new IllegalStateException(e);
        }
    }

    public void purgeCache() {
        repositoryInfoCache.clear();
    }

    public CFGHRepoInfo getRepositoryInfo(String fullName) {
        return repositoryInfoCache.get(fullName);
    }

    public CFGHQueryHelper getQueryContext(CFGHRepoInfo repositoryInfo) {
        return new CFGHQueryHelper(quarkusBotConfig,
                repositoryInfo.ghRepository, repositoryInfo.ghiId, gitHubService);
    }

    public CFGHQueryHelper getQueryContext(GHRepository ghRepository, GHAppInstallation ghai) {
        return new CFGHQueryHelper(quarkusBotConfig, ghRepository, ghai.getId(), gitHubService);
    }

    /** Parses to Date as GitHubClient.parseDate does */
    static final Date parseDate(String timestamp) {
        if (timestamp == null) {
            return null;
        }
        return Date.from(parseInstant(timestamp));
    }

    /** Parses to Instant as GitHubClient.parseInstant does */
    static Instant parseInstant(String timestamp) {
        if (timestamp == null) {
            return null;
        }

        if (timestamp.charAt(4) == '/') {
            // Unsure where this is used, but retained for compatibility.
            return Instant.from(CFGHApp.DATE_TIME_PARSER_SLASHES.parse(timestamp));
        } else {
            return Instant.from(DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(timestamp));
        }
    }
}
