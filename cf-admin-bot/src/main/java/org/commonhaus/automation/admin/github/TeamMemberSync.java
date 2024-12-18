package org.commonhaus.automation.admin.github;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.commonhaus.automation.admin.Routes;
import org.commonhaus.automation.admin.api.CouncilResource.TeamSyncTriggerEvent;
import org.commonhaus.automation.admin.config.AdminConfigFile;
import org.commonhaus.automation.admin.config.SponsorsConfig;
import org.commonhaus.automation.admin.config.TeamManagementConfig;
import org.commonhaus.automation.admin.config.TeamSourceConfig;
import org.commonhaus.automation.admin.config.TeamSourceConfig.Defaults;
import org.commonhaus.automation.admin.config.TeamSourceConfig.SyncToTeams;
import org.commonhaus.automation.github.context.DataSponsorship;
import org.commonhaus.automation.github.context.DataTeam;
import org.commonhaus.automation.github.discovery.BootstrapDiscoveryEvent;
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
import io.quarkus.logging.Log;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;
import io.vertx.core.impl.ConcurrentHashSet;

@ApplicationScoped
public class TeamMemberSync {

    private final BlockingQueue<Runnable> taskQueue = new LinkedBlockingQueue<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    private final Set<MonitoredRepo> monitoredRepos = new ConcurrentHashSet<>();
    private static volatile String lastRun = "never";

    @Inject
    AppContextService ctx;

    @Inject
    CommonhausDatastore datastore;

    public void configureExecutor(@Observes StartupEvent startup) {
        int initialDelay = 30;
        TimeUnit unit = TimeUnit.SECONDS;
        if (LaunchMode.current() == LaunchMode.TEST) {
            initialDelay = 0;
            unit = TimeUnit.MILLISECONDS;
        }
        executor.scheduleAtFixedRate(() -> {
            Runnable task = taskQueue.poll();
            if (task != null) {
                task.run();
            }
        }, initialDelay, 10, unit);

        Routes.registerSupplier("TeamMemberSync", () -> lastRun);
    }

    public void shutdown(@Observes ShutdownEvent shutdown) {
        executor.shutdown();
    }

    /**
     * Event handler for repository discovery.
     */
    public void repositoryDiscovered(@Observes RepositoryDiscoveryEvent repoEvent) {
        lastRun = DateTimeFormatter.ISO_INSTANT.format(Instant.now());

        long ghiId = repoEvent.installationId();
        GHRepository repo = repoEvent.repository();
        String repoFullName = repo.getFullName();

        Optional<AdminConfigFile> repoConfig = repoEvent.getRepositoryConfig();
        TeamManagementConfig groupManagement = TeamManagementConfig.getGroupManagementConfig(repoConfig.orElse(null));

        if (repoEvent.removed() || repoConfig.isEmpty() || groupManagement.isDisabled()) {
            monitoredRepos.removeIf(entry -> entry.repoFullName().equals(repoFullName));
            return;
        }

        MonitoredRepo cfg = new MonitoredRepo(repoFullName, ghiId).refresh(repoConfig.get());
        monitoredRepos.add(cfg);

        Log.debugf("[%s] teamSync repositoryDiscovered: %s", repoEvent.installationId(), repo.getFullName());
        if (!repoEvent.bootstrap()) {
            // for bootstrap events, wait to queue until after all repositories are discovered
            scheduleQueryRepository(cfg, repoEvent.github());
        }
    }

    public void postBootstrapDiscovery(@Observes BootstrapDiscoveryEvent postEvent) {
        Log.debugf("postBootstrapDiscovery: %s", postEvent.installations());
        for (MonitoredRepo repoCfg : monitoredRepos) {
            scheduleQueryRepository(repoCfg, ctx.getInstallationClient(repoCfg.installationId()));
        }
    }

    /**
     * Push event
     * If the pushed file matches the configuration file, re-read the configuration and update the team members.
     */
    public void updateTeamMembers(GitHubEvent event, GitHub github, DynamicGraphQLClient graphQLClient,
            @Push GHEventPayload.Push pushEvent) {
        lastRun = DateTimeFormatter.ISO_INSTANT.format(Instant.now());

        GHRepository repo = pushEvent.getRepository();
        ctx.refreshScopedQueryContext(event.getInstallationId(), repo)
                .addExisting(github).addExisting(graphQLClient);

        Log.debugf("updateTeamMembers (push): %s", repo.getFullName());

        for (MonitoredRepo repoCfg : monitoredRepos) {
            if (repoCfg.repoFullName().equals(repo.getFullName())
                    && pushEvent.getRef().endsWith("/main")
                    && repoCfg.sourceConfig().stream().anyMatch(s -> ctx.commitsContain(pushEvent, s.path()))) {
                Log.debugf("updateTeamMembers (push): Re-read configuration: %s", repo.getFullName());

                AdminConfigFile file = ctx.getConfiguration(repo, true);
                repoCfg.refresh(file);

                // schedule a query for the repository to refresh the team membership to new config
                scheduleQueryRepository(repoCfg, github);
            }
        }
    }

    void syncTrigger(@Observes TeamSyncTriggerEvent event) {
        Log.debug("syncTrigger: triggered by event");
        scheduledSync();
    }

    @Scheduled(cron = "${automation.admin.team-sync-cron:23 25 3 ? * * *}")
    void scheduledSync() {
        lastRun = DateTimeFormatter.ISO_INSTANT.format(Instant.now());

        try {
            Iterator<MonitoredRepo> i = monitoredRepos.iterator();
            while (i.hasNext()) {
                MonitoredRepo repoCfg = i.next();
                Log.debugf("[%s] scheduledSync: %s", repoCfg.installationId(), repoCfg);

                GitHub github = ctx.getInstallationClient(repoCfg.installationId());
                GHRepository repo = github.getRepository(repoCfg.repoFullName());
                AdminConfigFile file = ctx.getConfiguration(repo);
                repoCfg.refresh(file);

                if (repoCfg.sourceConfig() == null || repoCfg.sourceConfig().isEmpty()) {
                    // Team sync no longer enabled. Remove the repository
                    Log.infof("[%s] scheduledSync: disable confogl for %s", repoCfg.installationId(), repoCfg.repoFullName());
                    i.remove();
                } else {
                    scheduleQueryRepository(repoCfg, github);
                }
            }
        } catch (GHIOException e) {
            ctx.logAndSendEmail("scheduledSync", "Error making GH Request", e, null);
            if (Log.isDebugEnabled() && e.getResponseHeaderFields() != null) {
                e.getResponseHeaderFields()
                        .forEach((k, v) -> Log.debugf("%s: %s", k, v));
                e.printStackTrace();
            }
        } catch (Throwable t) {
            ctx.logAndSendEmail("scheduledSync", "Error making GH Request", t, null);
            if (Log.isDebugEnabled()) {
                t.printStackTrace();
            }
        }
    }

    void scheduleQueryRepository(MonitoredRepo repoCfg, GitHub github) {
        Log.debugf("scheduleQueryRepository: queue repository %s", repoCfg.repoFullName());

        ScopedQueryContext qc = this.ctx.getScopedQueryContext(repoCfg.repoFullName());
        if (qc == null) {
            Log.errorf("[%s] scheduleQueryRepository: no query context for %s", repoCfg.installationId(),
                    repoCfg.repoFullName());
            return;
        }
        qc.addExisting(github).addExisting(repoCfg);

        for (TeamSourceConfig source : repoCfg.sourceConfig) {
            if (source.performSync()) {
                taskQueue.add(() -> syncTeamMembership(qc, source));
            }
        }
        if (repoCfg.sponsors() != null) {
            taskQueue.add(() -> syncSponsors(qc, repoCfg.sponsors()));
        }
    }

    private void syncSponsors(ScopedQueryContext qc, SponsorsConfig sponsors) {
        try {
            Log.debugf("[%s] syncSponsors: %s", qc.getLogId(), sponsors);
            Collection<String> sponsorLogins = getSponsors(sponsors.sponsorable());
            addMissingCollaborators(sponsors.repository(), sponsorLogins, sponsors.dryRun(), qc.dryRunEmailAddress());
        } catch (Throwable t) {
            qc.logAndSendEmail("Error syncing sponsors", t);
        }
    }

    Collection<String> getSponsors(String sponsorable) {
        ScopedQueryContext qc = ctx.getScopedQueryContext(sponsorable);

        List<DataSponsorship> recentSponsors = DataSponsorship.queryRecentSponsors(qc, sponsorable);
        if (recentSponsors == null) {
            Log.warnf("[%s] syncSponsors: failed to query sponsors for %s",
                    qc.getLogId(), sponsorable);
            return List.of();
        }
        Collection<String> sponsorLogin = recentSponsors.stream()
                .map(DataSponsorship::sponsorLogin)
                .collect(Collectors.toCollection(ArrayList::new));

        Log.debugf("[%s] syncSponsors: recent sponsors for %s: %s", qc.getLogId(),
                sponsorable, sponsorLogin);
        return sponsorLogin;
    }

    void addMissingCollaborators(String repositoryName, Collection<String> sponsorLogins, boolean isDryRun,
            String[] dryRunEmail) throws Throwable {
        ScopedQueryContext qc = ctx.getScopedQueryContext(repositoryName);
        if (qc == null) {
            throw new IllegalStateException("addMissingCollaborators: No query context for " + repositoryName);
        }
        String orgName = ScopedQueryContext.toOrganizationName(repositoryName);
        GHOrganization org = qc.getOrganization(orgName);
        if (org == null) {
            Log.warnf("[%s] addMissingCollaborators: organization %s not found", qc.getLogId(), orgName);
            return;
        }
        GHRepository repo = qc.getRepository(repositoryName);
        if (repo == null) {
            Log.warnf("[%s] addMissingCollaborators: repository %s not found", qc.getLogId(), repositoryName);
            return;
        }

        Set<String> collaborators = qc.collaborators(repositoryName);

        List<GHUser> missingCollaborators = new ArrayList<>();
        for (String login : sponsorLogins) {
            if (collaborators.contains(login)) {
                continue;
            }
            GHUser ghUser = qc.getUser(login);
            if (ghUser == null) {
                Log.warnf("[%s] addMissingCollaborators: user %s not found", qc.getLogId(), login);
                continue;
            }
            missingCollaborators.add(ghUser);
        }

        if (isDryRun) {
            List<String> added = missingCollaborators.stream().map(GHUser::getLogin).collect(Collectors.toList());
            List<String> finalList = new ArrayList<>(collaborators);
            finalList.addAll(added);
            Logins allLogins = new Logins(
                    collaborators,
                    added,
                    List.of(),
                    finalList);
            sendDryRunEmail(repositoryName + " collaborators:", allLogins, dryRunEmail);
        } else {
            qc.addCollaborators(repo, missingCollaborators);
            if (qc.clearNotFound() && qc.hasErrors()) {
                throw qc.bundleExceptions();
            }
        }
        Log.infof("[%s] addMissingCollaborators: finished syncing %s outside collaborators", qc.getLogId(),
                missingCollaborators.size());
    }

    void syncTeamMembership(ScopedQueryContext qc, TeamSourceConfig source) {
        Log.debugf("[%s] syncTeamMembership: %s / %s", qc.getLogId(), source.repo(), source.path());

        GHRepository repo = qc.getRepository(source.repo());
        if (repo == null) {
            Log.warnf("[%s] syncTeamMembership: source repository %s not found", qc.getLogId(), source.repo());
            return;
        }

        // get contents of file from the specified repo + path
        JsonNode data = qc.readYamlSourceFile(repo, source.path());
        if (data == null) {
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
                Log.debugf("[%s] syncTeamMembership: field %s from %s to %s", qc.getLogId(), field, groupName, sync.teams());

                Set<String> logins = new HashSet<>(sync.preserveUsers(defaults));
                for (JsonNode member : sourceTeamData) {
                    JsonNode node = member.get(field);
                    String login = node == null || !node.isTextual() ? null : node.asText();
                    if (login == null || !login.matches("^[a-zA-Z0-9-]+$")) {
                        Log.debugf("[%s] syncTeamMembership: ignoring empty %s in %s", qc.getLogId(), member, groupName);
                    } else {
                        logins.add(login);
                    }
                }
                Log.debugf("[%s] syncTeamMembership: source group %s has members %s", qc.getLogId(), groupName, logins);

                for (String targetTeam : sync.teams()) {
                    try {
                        if (!targetTeam.contains("/")) {
                            targetTeam = repo.getFullName() + "/" + targetTeam;
                        }
                        doSyncTeamMembers(source, targetTeam, logins, qc.dryRunEmailAddress());
                    } catch (Throwable t) {
                        qc.logAndSendEmail("Error syncing team members", t);
                    }
                }
            } else {
                Log.debugf("[%s] syncTeamMembership: group %s not found in %s", qc.getLogId(), groupName, data);
            }
        }
    }

    void doSyncTeamMembers(TeamSourceConfig config, String fullTeamName, Set<String> sourceLogins, String[] dryRunEmail) {
        boolean productionRun = !config.dryRun();

        String orgName = ScopedQueryContext.toOrganizationName(fullTeamName);
        String relativeName = ScopedQueryContext.toRelativeName(orgName, fullTeamName);

        ScopedQueryContext qc = ctx.getScopedQueryContext(orgName);
        if (qc == null) {
            throw new IllegalStateException("doSyncTeamMembers: No query context for " + fullTeamName);
        }
        GHOrganization org = qc.getOrganization(orgName);
        if (org == null) {
            Log.warnf("[%s] doSyncTeamMembers: organization %s not found", qc.getLogId(), orgName);
            return;
        }

        GHTeam team = qc.getTeam(org, relativeName);
        if (team == null) {
            Log.warnf("[%s] doSyncTeamMembers: team %s not found in %s", qc.getLogId(), relativeName, orgName);
            return;
        }

        // Use GraphQL to get the _immediate_ team members (exclude child teams)
        List<String> currentLogins = DataTeam.queryImmediateTeamMemberLogin(qc, orgName, relativeName);

        Set<String> toVerify = new HashSet<>(sourceLogins);
        toVerify.addAll(currentLogins);

        Set<String> toAdd = new HashSet<>(sourceLogins);
        toAdd.removeAll(currentLogins);

        Set<String> toRemove = new HashSet<>(currentLogins);
        toRemove.removeAll(sourceLogins);

        Log.debugf("[%s] doSyncTeamMembers: %s; add %s; remove %s", qc.getLogId(),
                fullTeamName, toAdd, toRemove);

        if (productionRun) {
            final Set<GHUser> validate = new HashSet<>();
            // Membership events will update the team cache; do nothing with the cache here.
            qc.execGitHubSync((gh, dryRun) -> {
                if (dryRun) {
                    return null;
                }
                for (String login : toVerify) {
                    try {
                        GHUser user = gh.getUser(login);
                        if (toRemove.contains(login)) {
                            team.remove(user);
                            Log.infof("[%s] doSyncTeamMembers: remove %s from %s", qc.getLogId(), user, fullTeamName);
                        } else {
                            validate.add(user);
                            if (toAdd.contains(login)) {
                                team.add(user);
                                Log.infof("[%s] doSyncTeamMembers: add %s to %s", qc.getLogId(), user, fullTeamName);
                            }
                        }
                    } catch (IOException e) {
                        Log.errorf(e, "[%s] doSyncTeamMembers: failed to find, add, or remove user %s", qc.getLogId(), login);
                    }
                }
                return null;
            });
            for (GHUser user : validate) {
                datastore.asyncEnsureCommonhausUser(user);
            }
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
        Log.infof("[%s] doSyncTeamMembers: finished syncing %s; %s added; %s removed", qc.getLogId(),
                fullTeamName, toAdd.size(), toRemove.size());
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
