package org.commonhaus.automation.github;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.commonhaus.automation.BotConfig;
import org.kohsuke.github.GHApp;
import org.kohsuke.github.GHAppInstallation;
import org.kohsuke.github.GHAuthenticatedAppInstallation;
import org.kohsuke.github.GHIOException;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import io.quarkiverse.githubapp.GitHubClientProvider;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import io.quarkus.logging.Log;

@Singleton
public class CFGHApp {
    static final DateTimeFormatter DATE_TIME_PARSER_SLASHES = DateTimeFormatter
            .ofPattern("yyyy/MM/dd HH:mm:ss Z");

    static Map<String, RepositoryInfo> repositoryInfoCache = new HashMap<>();
    
    private final BotConfig quarkusBotConfig;
    private final GitHubClientProvider gitHubClientProvider;

    @Inject
    public CFGHApp(BotConfig quarkusBotConfig, GitHubClientProvider gitHubClientProvider) {
        this.quarkusBotConfig = quarkusBotConfig;
        this.gitHubClientProvider = gitHubClientProvider;
    }

    public void initializeCache() {
        if (!repositoryInfoCache.isEmpty()) {
            return;
        }
        
        GitHub ac = gitHubClientProvider.getApplicationClient();
        GHApp ghApp;
        try {
            ghApp = ac.getApp();
            for (GHAppInstallation ghAppInstallation : ghApp.listInstallations()) {
                GitHub ic = gitHubClientProvider.getInstallationClient(ghAppInstallation.getId());
                GHAuthenticatedAppInstallation ghai = ic.getInstallation();
                for (GHRepository ghRepository : ghai.listRepositories()) {
                    QueryContext queryContext = new QueryContext(quarkusBotConfig, 
                            ghRepository, ghAppInstallation.getId(), gitHubClientProvider);
                    RepositoryInfo repositoryInfo = new RepositoryInfo(queryContext);
                    if (queryContext.hasErrors()) {
                        Log.warnf("Unable to cache repository information", ghRepository.getFullName());
                        throw new RepositoryCacheException(queryContext);
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

    public RepositoryInfo getRepositoryInfo(String fullName) {
        return repositoryInfoCache.get(fullName);
    }

    public QueryContext getQueryContext(RepositoryInfo repositoryInfo) {
        return new QueryContext(quarkusBotConfig, 
                repositoryInfo.ghRepository, repositoryInfo.ghiId, gitHubClientProvider);
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
