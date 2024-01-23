package org.commonhaus.automation.github;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;

import org.commonhaus.automation.BotConfig;
import org.commonhaus.automation.github.CFGHQueryHelper.RepoQuery;
import org.commonhaus.automation.github.model.JsonAttribute;
import org.kohsuke.github.GHApp;
import org.kohsuke.github.GHAppInstallation;
import org.kohsuke.github.GHAuthenticatedAppInstallation;
import org.kohsuke.github.GHIOException;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import io.quarkiverse.githubapp.runtime.github.GitHubService;
import io.quarkus.logging.Log;
import io.smallrye.graphql.client.Response;

@ApplicationScoped
public class CFGHApp {
    static final DateTimeFormatter DATE_TIME_PARSER_SLASHES = DateTimeFormatter
            .ofPattern("yyyy/MM/dd HH:mm:ss Z");

    static final Map<Long, InstallationInfo> installationInfo = new HashMap<>();

    private final BotConfig quarkusBotConfig;
    private final GitHubService gitHubService;

    @Inject
    protected Instance<CFGHEventHandler> eventHandler;

    @Inject
    CFGHApp(BotConfig quarkusBotConfig, GitHubService gitHubService) {
        this.quarkusBotConfig = quarkusBotConfig;
        this.gitHubService = gitHubService;
    }

    public void initializeCache() {
        discoverInstallations();
    }

    public void purgeCache() {

    }

    /**
     * @param ghiId installation id
     * @return installation information for the given installation id
     */
    public InstallationInfo getInstallationInfo(long ghiId) {
        return installationInfo.get(ghiId);
    }

    /**
     * @param ghiId installation id
     * @param fullName full name of the repository
     * @return cached repository info for the specified installation
     */
    public CFGHRepoInfo getRepositoryInfo(long ghiId, String fullName) {
        InstallationInfo info = getInstallationInfo(ghiId);
        if (info == null) {
            return null;
        }
        return info.getRepositoryInfo(fullName);
    }

    /**
     * If this bot has multiple installations that are allowed to access
     * the same repository, this will return the first one found.
     *
     * @param fullName
     * @return RepoInfo for the specified git repository
     */
    public CFGHRepoInfo getRepositoryInfo(String fullName) {
        for (InstallationInfo info : installationInfo.values()) {
            CFGHRepoInfo repoInfo = info.getRepositoryInfo(fullName);
            if (repoInfo != null) {
                return repoInfo;
            }
        }
        return null;
    }

    private void discoverInstallations() {
        GitHub ac = gitHubService.getApplicationClient();
        try {
            GHApp ghApp = ac.getApp();
            for (GHAppInstallation ghAppInstallation : ghApp.listInstallations()) {
                InstallationInfo info = createInstallationInfo(ghAppInstallation);
                installationInfo.put(ghAppInstallation.getId(), info);
            }
        } catch (GHIOException e) {
            // TODO: Config to handle GHIOException (retry? quit? ensure notification?)
            e.getResponseHeaderFields().forEach((k, v) -> Log.debugf("%s: %s", k, v));
            throw new IllegalStateException(e);
        } catch (Throwable t) {
            // TODO Auto-generated catch block
            t.printStackTrace();
        }
    }

    private InstallationInfo createInstallationInfo(GHAppInstallation ghAppInstallation)
            throws IOException, ExecutionException, InterruptedException {
        long ghiId = ghAppInstallation.getId();
        // Get repositories this installation has access to
        GitHub github = gitHubService.getInstallationClient(ghiId);
        GHAuthenticatedAppInstallation ghai = github.getInstallation();

        CFGHQueryHelper queryHelper = new CFGHQueryHelper(quarkusBotConfig, ghiId, gitHubService)
                .addExisting(github);
        String login = getViewer(queryHelper);

        InstallationInfo info = new InstallationInfo(ghiId, login);
        Log.infof("[%s] GitHub App Login: %s", ghiId, login);

        for (GHRepository ghRepository : ghai.listRepositories()) {
            CFGHRepoInfo repositoryInfo = new CFGHRepoInfo(ghRepository, ghiId);
            info.addRepositoryInfo(repositoryInfo);
            Log.infof("[%s] GitHub Repository: %s",
                    info.getInstallationId(), repositoryInfo.getFullName());
        }
        return info;
    }

    String getViewer(CFGHQueryHelper queryHelper) {
        Response response = queryHelper.execQuerySync("""
                    query {
                        viewer {
                          login
                        }
                    }
                """, null);
        if (!response.hasError()) {
            JsonObject viewer = JsonAttribute.viewer.jsonObjectFrom(response.getData());
            return JsonAttribute.login.stringFrom(viewer);
        }
        return "unknown";
    }

    public RepoQuery getRepoQueryContext(GHRepository ghRepository, GHAppInstallation ghai) {
        InstallationInfo info = getInstallationInfo(ghai.getId());
        CFGHRepoInfo repoInfo = info.getRepositoryInfo(ghRepository.getFullName());
        return new RepoQuery(quarkusBotConfig, repoInfo, gitHubService);
    }

    public RepoQuery getRepoQueryContext(String repo) {
        CFGHRepoInfo repoInfo = getRepositoryInfo(repo);
        return new RepoQuery(quarkusBotConfig, repoInfo, gitHubService);
    }

    /** Parses to Date as GitHubClient.parseDate does */
    public static final Date parseDate(String timestamp) {
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

    public static class InstallationInfo {
        final Map<String, CFGHRepoInfo> repoInfoCache = new HashMap<>();
        final long ghiId;
        final String login;

        public InstallationInfo(long ghiId, String login) {
            this.ghiId = ghiId;
            this.login = login;
        }

        public String getLogin() {
            return login;
        }

        public long getInstallationId() {
            return ghiId;
        }

        public CFGHRepoInfo getRepositoryInfo(String fullName) {
            return repoInfoCache.get(fullName);
        }

        void addRepositoryInfo(CFGHRepoInfo repoInfo) {
            repoInfoCache.put(repoInfo.getFullName(), repoInfo);
        }
    }
}
