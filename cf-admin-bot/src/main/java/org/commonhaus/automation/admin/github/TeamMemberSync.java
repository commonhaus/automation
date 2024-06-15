package org.commonhaus.automation.admin.github;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.commonhaus.automation.admin.config.AdminConfigFile;
import org.commonhaus.automation.admin.config.SponsorsConfig;
import org.commonhaus.automation.admin.config.TeamManagementConfig;
import org.commonhaus.automation.admin.config.TeamSourceConfig;
import org.commonhaus.automation.admin.config.TeamSourceConfig.Defaults;
import org.commonhaus.automation.admin.config.TeamSourceConfig.SyncToTeams;
import org.commonhaus.automation.github.context.DataSponsorship;
import org.commonhaus.automation.github.context.DataTeam;
import org.commonhaus.automation.github.discovery.RepositoryDiscoveryEvent;
import org.commonhaus.automation.markdown.MarkdownConverter;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHIOException;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHTeam;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.GitHub;

import com.fasterxml.jackson.databind.JsonNode;

import io.quarkiverse.githubapp.GitHubEvent;
import io.quarkiverse.githubapp.event.Push;
import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.arc.profile.UnlessBuildProfile;
import io.quarkus.logging.Log;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;

@ApplicationScoped
public class TeamMemberSync {
    private final BlockingQueue<Runnable> taskQueue = new LinkedBlockingQueue<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final Map<MonitoredRepo, String> monitoredRepos = new ConcurrentHashMap<>();

    @Inject
    AppContextService ctx;

    @UnlessBuildProfile("test")
    public void startup(@Observes StartupEvent startup) {
        // Don't flood. Be leisurely for scheduled/cron queries
        executor.scheduleAtFixedRate(() -> {
            Runnable task = taskQueue.poll();
            if (task != null) {
                task.run();
            }
        }, 0, 10, TimeUnit.SECONDS);
    }

    @IfBuildProfile("test")
    public void testStartup(@Observes StartupEvent startup) {
        executor.scheduleAtFixedRate(() -> {
            Runnable task = taskQueue.poll();
            if (task != null) {
                task.run();
            }
        }, 0, 30, TimeUnit.MILLISECONDS);
    }

    public void shutdown(@Observes ShutdownEvent shutdown) {
        executor.shutdown();
    }

    /**
     * Event handler for repository discovery.
     */
    public void repositoryDiscovered(@Observes RepositoryDiscoveryEvent repoEvent) {
        long ghiId = repoEvent.installationId();
        GHRepository repo = repoEvent.repository();
        String repoFullName = repo.getFullName();

        Optional<AdminConfigFile> repoConfig = repoEvent.getRepositoryConfig();
        TeamManagementConfig groupManagement = TeamManagementConfig.getGroupManagementConfig(repoConfig.orElse(null));

        if (repoEvent.removed() || repoConfig.isEmpty() || groupManagement.isDisabled()) {
            monitoredRepos.entrySet().removeIf(entry -> entry.getValue().equals(repoFullName));
            return;
        }

        MonitoredRepo cfg = new MonitoredRepo(repoFullName, ghiId).refresh(repoConfig.get());
        monitoredRepos.put(cfg, repoFullName);

        Log.debugf("repositoryDiscovered: %s", repo.getFullName());

        // Scan immediately in dev & test
        scheduleQueryRepository(cfg, repo, repoEvent.github());
    }

    public void updateTeamMembers(GitHubEvent event, GitHub github, DynamicGraphQLClient graphQLClient,
            @Push GHEventPayload.Push pushEvent) {
        GHRepository repo = pushEvent.getRepository();
        Log.debugf("updateTeamMembers (push): %s", repo.getFullName());

        for (Entry<MonitoredRepo, String> entry : monitoredRepos.entrySet()) {
            MonitoredRepo repoCfg = entry.getKey();
            if (repoCfg.repoFullName().equals(repo.getFullName())
                    && pushEvent.getRef().endsWith("/main")
                    && repoCfg.sourceConfig().stream().anyMatch(s -> ctx.commitsContain(pushEvent, s.path()))) {
                Log.debugf("updateTeamMembers (push): Re-read configuration: %s", repo.getFullName());

                AdminConfigFile file = ctx.getConfiguration(repo, true);
                repoCfg.refresh(file);

                // schedule a query for the repository to refresh the team membership to new config
                scheduleQueryRepository(repoCfg, repo, github);
            }
        }
    }

    @Scheduled(cron = "${automation.admin.team-sync-cron:13 27 */5 * * ?}")
    void scheduledSync() {
        String[] errorAddresses = null;
        try {
            Iterator<Entry<MonitoredRepo, String>> i = monitoredRepos.entrySet().iterator();
            while (i.hasNext()) {
                var e = i.next();
                MonitoredRepo repoCfg = e.getKey();
                Log.infof("scheduledSync: %s", repoCfg);

                GitHub github = ctx.getInstallationClient(repoCfg.installationId());
                GHRepository repo = github.getRepository(repoCfg.repoFullName());

                AdminConfigFile file = ctx.getConfiguration(repo);
                repoCfg.refresh(file);

                if (repoCfg.sourceConfig() == null || repoCfg.sourceConfig().isEmpty()) {
                    // Team sync no longer enabled. Remove the repository
                    i.remove();
                } else {
                    scheduleQueryRepository(repoCfg, repo, github);
                }
            }
        } catch (GHIOException e) {
            ctx.logAndSendEmail("scheduledSync", "Error making GH Request", e, errorAddresses);
            if (Log.isDebugEnabled() && e.getResponseHeaderFields() != null) {
                e.getResponseHeaderFields()
                        .forEach((k, v) -> Log.debugf("%s: %s", k, v));
                e.printStackTrace();
            }
        } catch (Throwable t) {
            ctx.logAndSendEmail("scheduledSync", "Error making GH Request", t, errorAddresses);
            if (Log.isDebugEnabled()) {
                t.printStackTrace();
            }
        }
    }

    void scheduleQueryRepository(MonitoredRepo repoCfg, GHRepository repo, GitHub github) {
        Log.debugf("scheduleQueryRepository: queue repository %s", repo.getFullName());
        for (TeamSourceConfig source : repoCfg.sourceConfig) {
            if (source.performSync()) {
                taskQueue.add(() -> {
                    ScopedQueryContext qc = this.ctx.refreshScopedQueryContext(repoCfg, repo).addExisting(github);
                    syncTeamMembership(qc, source);
                });
            }
        }

        if (repoCfg.sponsors() != null) {
            taskQueue.add(() -> {
                ScopedQueryContext qc = this.ctx.refreshScopedQueryContext(repoCfg, repo).addExisting(github);
                syncSponsors(qc, repoCfg.sponsors());
            });
        }
    }

    private void syncSponsors(ScopedQueryContext qc, SponsorsConfig sponsors) {
        try {
            Log.debugf("syncSponsors: %s", sponsors);

            String sponsorable = sponsors.sponsorable();
            ScopedQueryContext sponsorableQc = ctx.getScopedQueryContext(sponsorable);

            List<DataSponsorship> recentSponsors = DataSponsorship.queryRecentSponsors(sponsorableQc, sponsors.sponsorable());
            if (recentSponsors == null) {
                Log.warnf("[%s] syncSponsors: failed to query sponsors for %s",
                        sponsorableQc.getLogId(), sponsors.sponsorable());
                return;
            }
            Collection<String> sponsorLogin = recentSponsors.stream()
                    .map(DataSponsorship::sponsorLogin)
                    .collect(Collectors.toCollection(ArrayList::new));
            Log.debugf("syncSponsors: recent sponsors for %s: %s", sponsorable, sponsorLogin);

            String repositoryName = sponsors.repository();
            ScopedQueryContext repoQc = ctx.getScopedQueryContext(repositoryName);

            String orgName = ScopedQueryContext.toOrganizationName(repositoryName);
            GHOrganization org = repoQc == null ? null : repoQc.getOrganization(orgName);
            if (org == null) {
                Log.warnf("syncSponsors: organization %s not found", orgName);
                return;
            }
            GHRepository repo = repoQc == null ? null : repoQc.getRepository(repositoryName);
            if (repo == null) {
                Log.warnf("syncSponsors: repository %s not found", repositoryName);
                return;
            }

            List<GHUser> missingCollaborators = new ArrayList<>();
            Set<String> existingCollaborators = repoQc.collaborators(repositoryName);
            for (String login : sponsorLogin) {
                if (existingCollaborators.contains(login)) {
                    Log.debugf("syncSponsors: %s is already a collaborator", login);
                    continue;
                }
                GHUser ghUser = repoQc.getUser(login);
                if (ghUser == null) {
                    Log.warnf("syncSponsors: user %s not found", login);
                    continue;
                }
                missingCollaborators.add(ghUser);
            }

            if (sponsors.dryRun()) {
                List<String> added = missingCollaborators.stream().map(GHUser::getLogin).collect(Collectors.toList());
                List<String> finalList = new ArrayList<>(existingCollaborators);
                finalList.addAll(added);
                Logins allLogins = new Logins(
                        existingCollaborators,
                        added,
                        List.of(),
                        finalList);
                sendDryRunEmail(repositoryName + " collaborators:", allLogins, qc.dryRunEmailAddress());
            } else {
                repoQc.addCollaborators(repo, missingCollaborators);
                if (repoQc.clearNotFound() && repoQc.hasErrors()) {
                    throw repoQc.bundleExceptions();
                }
            }
        } catch (Throwable t) {
            qc.logAndSendEmail("syncSponsors", "Error syncing sponsors", t);
        }
    }

    void syncTeamMembership(ScopedQueryContext qc, TeamSourceConfig source) {
        Log.debugf("syncTeamMembership: %s / %s", source.repo(), source.path());

        GHRepository repo = qc.getRepository(source.repo());
        if (repo == null) {
            Log.warnf("syncTeamMembership: source repository %s not found", source.repo());
            return;
        }

        // get contents of file from the specified repo + path
        JsonNode data = qc.readSourceFile(repo, source.path());
        if (data == null) {
            Log.warnf("syncTeamMembership: source file %s not found or could not be read", source.path());
            return;
        }

        Defaults defaults = source.defaults();
        // sync team membership
        for (Map.Entry<String, SyncToTeams> entry : source.sync().entrySet()) {
            String groupName = entry.getKey();
            SyncToTeams sync = entry.getValue();
            String field = sync.field(defaults);

            JsonNode sourceTeamData = data.get(groupName);
            if (sourceTeamData != null && sourceTeamData.isArray()) {
                Log.debugf("syncTeamMembership: field %s from %s to %s", field, groupName, sync.teams());

                Set<String> logins = new HashSet<>();
                logins.addAll(sync.preserveUsers(defaults));
                for (JsonNode member : sourceTeamData) {
                    JsonNode node = member.get(field);
                    String login = node == null || !node.isTextual() ? null : node.asText();
                    if (login == null || !login.matches("^[a-zA-Z0-9-]+$")) {
                        Log.debugf("syncTeamMembership: ignoring empty %s in %s", member, groupName);
                    } else {
                        logins.add(login);
                    }
                }
                Log.debugf("syncTeamMembership: source group %s has members %s", groupName, logins);

                for (String targetTeam : sync.teams()) {
                    try {
                        if (!targetTeam.contains("/")) {
                            targetTeam = repo.getFullName() + "/" + targetTeam;
                        }
                        doSyncTeamMembers(source, targetTeam, logins, qc.dryRunEmailAddress());
                    } catch (Throwable t) {
                        qc.logAndSendEmail("doSyncTeamMembers", "Error syncing team members", t);
                    }
                }
            } else {
                Log.debugf("syncTeamMembership: group %s not found in %s", groupName, data);
            }
        }
    }

    void doSyncTeamMembers(TeamSourceConfig config, String fullTeamName, Set<String> sourceLogins, String[] dryRunEmail) {
        boolean productionRun = !config.dryRun();

        String orgName = ScopedQueryContext.toOrganizationName(fullTeamName);
        String relativeName = ScopedQueryContext.toRelativeName(orgName, fullTeamName);

        ScopedQueryContext qc = ctx.getScopedQueryContext(orgName);
        GHOrganization org = qc == null ? null : qc.getOrganization(orgName);
        if (org == null) {
            Log.warnf("doSyncTeamMembers: %s %s not found", qc == null ? "ScopedQueryContext for " : "Organization", orgName);
            return;
        }

        GHTeam team = qc.getTeam(org, relativeName);
        if (team == null) {
            Log.warnf("doSyncTeamMembers: team %s not found in %s", relativeName, orgName);
            return;
        }

        // Use GraphQL to get the _immediate_ team members (exclude child teams)
        List<String> currentLogins = DataTeam.queryImmediateTeamMemberLogin(qc, orgName, relativeName);

        Set<String> toAdd = new HashSet<>(sourceLogins);
        Set<String> toRemove = new HashSet<>();

        currentLogins.forEach(user -> {
            if (sourceLogins.contains(user)) {
                toAdd.remove(user); // already in team
            } else {
                toRemove.add(user);
            }
        });

        if (productionRun) {
            // Membership events will update the team cache; do nothing with the cache here.
            qc.execGitHubSync((gh, dryRun) -> {
                if (dryRun) {
                    return null;
                }
                for (String login : toAdd) {
                    try {
                        GHUser user = gh.getUser(login);
                        team.add(user);
                        Log.infof("doSyncTeamMembers: add %s to %s", user, fullTeamName);
                    } catch (IOException e) {
                        Log.warnf("doSyncTeamMembers: failed to add %s to %s", login, fullTeamName);
                    }
                }
                for (String login : toRemove) {
                    try {
                        GHUser user = gh.getUser(login);
                        team.remove(user);
                        Log.infof("doSyncTeamMembers: remove %s from %s", user, fullTeamName);
                    } catch (IOException e) {
                        Log.warnf("doSyncTeamMembers: failed to remove %s from %s", login, fullTeamName);
                    }
                }
                return null;
            });
        } else {
            Set<String> finalLogins = new HashSet<>(currentLogins);
            finalLogins.addAll(toAdd);
            finalLogins.removeAll(toRemove);

            if (toAdd.isEmpty() && toRemove.isEmpty()) {
                // no action required
                return;
            }
            Logins allLogins = new Logins(currentLogins, toAdd, toRemove, finalLogins);
            sendDryRunEmail(fullTeamName, allLogins, dryRunEmail);
        }
    }

    record Logins(
            Collection<String> previous,
            Collection<String> added,
            Collection<String> removed,
            Collection<String> updated) {
    }

    void sendDryRunEmail(String fullTeamName, Logins logins, String[] dryRunEmail) {
        if (dryRunEmail == null || dryRunEmail.length == 0 || Objects.equals(logins.previous, logins.updated)) {
            Log.infof("[sendDryRunEmail: %s] Current members: %s; New members: %s", fullTeamName,
                    logins.previous, logins.updated);
            return;
        }

        String title = "Dry Run: sync team members for %s".formatted(fullTeamName);
        String txtBody = """
                Team %s requires the following changes.

                Add:
                %s

                Remove:
                %s

                Final:
                %s
                """.formatted(fullTeamName, toPlainList(logins.added), toPlainList(logins.removed),
                toPlainList(logins.updated));

        String htmlBody = MarkdownConverter.toHtml(txtBody);

        ctx.sendEmail("sendDryRunEmail|", title, txtBody, htmlBody, dryRunEmail);
    }

    public String toPlainList(Collection<String> members) {
        if (members == null || members.isEmpty()) {
            return "none";
        }
        return String.join(", ", members);
    }
}
