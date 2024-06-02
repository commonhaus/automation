package org.commonhaus.automation.admin.github;

import java.io.IOException;
import java.util.ArrayList;
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

import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.commonhaus.automation.admin.RepositoryConfigFile;
import org.commonhaus.automation.admin.github.SourceConfig.SyncToTeams;
import org.commonhaus.automation.github.discovery.RepositoryDiscoveryEvent;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHIOException;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHTeam;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.GitHub;

import com.fasterxml.jackson.databind.JsonNode;

import io.quarkiverse.githubapp.event.Push;
import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.arc.profile.UnlessBuildProfile;
import io.quarkus.logging.Log;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;

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
        Log.debugf("DEBUG: HERE WE ARE");
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

        Optional<RepositoryConfigFile> repoConfig = repoEvent.getRepositoryConfig();
        GroupManagement groupManagement = GroupManagement.getGroupManagementConfig(repoConfig.orElse(null));

        if (repoEvent.removed() || repoConfig.isEmpty() || groupManagement.isDisabled()) {
            monitoredRepos.entrySet().removeIf(entry -> entry.getValue().equals(repoFullName));
            return;
        }

        MonitoredRepo cfg = new MonitoredRepo(repoFullName, ghiId).refresh(repoConfig.get());
        monitoredRepos.put(cfg, repoFullName);

        Log.debugf("repositoryDiscovered: %s", repo.getFullName());
        scheduleQueryRepository(cfg, repo, repoEvent.github());
    }

    public void updateTeamMembers(@Push GHEventPayload.Push pushEvent, GitHub github) {
        GHRepository repo = pushEvent.getRepository();
        for (Entry<MonitoredRepo, String> entry : monitoredRepos.entrySet()) {
            MonitoredRepo repoCfg = entry.getKey();
            if (repoCfg.repoFullName().equals(repo.getFullName())
                    && pushEvent.getRef().endsWith("/main")
                    && repoCfg.sourceConfig().stream().anyMatch(s -> ctx.commitsContain(pushEvent, s.path()))) {
                Log.debugf("updateTeamMembers (push): %s", repo.getFullName());
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

                RepositoryConfigFile file = ctx.getConfiguration(repo);
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
        for (SourceConfig source : repoCfg.sourceConfig) {
            if (source.performSync()) {
                taskQueue.add(() -> {
                    ScopedQueryContext qc = this.ctx.refreshScopedQueryContext(repo, repoCfg)
                            .addExisting(github);
                    syncTeamMembership(qc, source);
                });
            }
        }
    }

    void syncTeamMembership(ScopedQueryContext qc, SourceConfig source) {
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

        // sync team membership
        for (Map.Entry<String, SyncToTeams> entry : source.sync().entrySet()) {
            String groupName = entry.getKey();
            SyncToTeams sync = entry.getValue();
            String field = sync.field();

            JsonNode sourceTeamData = data.get(groupName);
            if (sourceTeamData != null && sourceTeamData.isArray()) {
                Log.debugf("syncTeamMembership: field %s from %s to %s", field, groupName, sync.teams());

                List<String> logins = new ArrayList<>();
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

    void doSyncTeamMembers(SourceConfig config, String fullTeamName, List<String> sourceLogins, String[] dryRunEmail) {
        int slash = fullTeamName.indexOf('/');
        String orgName = fullTeamName.substring(0, slash);
        String relativeName = fullTeamName.substring(slash + 1);
        boolean productionRun = !config.dryRun();

        ScopedQueryContext qc = ctx.getScopedQueryContext(orgName);
        GHOrganization org = qc == null ? null : qc.getOrganization(orgName);
        if (org == null) {
            Log.warnf("doSyncTeamMembers: %s %s not found", qc == null ? "ScopedQueryContext for " : "Organization", orgName);
            return;
        }

        Logins allLogins = qc.execGitHubSync((gh, dryRun) -> {
            GHTeam team = org.getTeamByName(relativeName);
            if (team == null) {
                Log.warnf("doSyncTeamMembers: team %s not found in %s", relativeName, orgName);
                return null;
            }
            Set<GHUser> original = team.getMembers();
            Set<GHUser> finalLogins = new HashSet<>();
            Set<GHUser> added = new HashSet<>();
            Set<GHUser> removed = new HashSet<>();

            Set<String> toAdd = new HashSet<>(sourceLogins);
            original.forEach(user -> {
                if (sourceLogins.contains(user.getLogin())) {
                    toAdd.remove(user.getLogin()); // already in team
                    finalLogins.add(user);
                } else {
                    Log.infof("doSyncTeamMembers: removing %s from %s", user.getLogin(), relativeName);
                    removed.add(user);
                    if (productionRun) {
                        try {
                            team.remove(user);
                        } catch (IOException e) {
                            qc.logAndSendEmail("doSyncTeamMembers",
                                    "failed to remove %s to %s".formatted(user.getLogin(), fullTeamName), e);
                        }
                    }
                }
            });

            if (!toAdd.isEmpty()) {
                Log.infof("doSyncTeamMembers: adding %s to %s", toAdd, fullTeamName);
                for (String login : toAdd) {
                    try {
                        GHUser user = gh.getUser(login);
                        if (user == null) {
                            Log.warnf("doSyncTeamMembers: user %s not found", login);
                            continue;
                        }
                        added.add(user);
                        finalLogins.add(user);

                        if (productionRun) {
                            try {
                                team.add(user);
                            } catch (IOException e) {
                                qc.logAndSendEmail("doSyncTeamMembers",
                                        "failed to add %s to %s".formatted(user.getLogin(), fullTeamName), e);
                            }
                        }
                    } catch (Exception e) {
                        qc.logAndSendEmail("doSyncTeamMembers",
                                "failed to add %s to %s".formatted(login, relativeName), e);
                    }
                }
            }
            return new Logins(original, added, removed, finalLogins);
        });

        Log.infof("doSyncTeamMembers: %s has %d members", fullTeamName, allLogins.updated.size());

        if (productionRun) {
            qc.updateTeamList(fullTeamName, allLogins.updated);
        } else {
            sendDryRunEmail(fullTeamName, allLogins, dryRunEmail);
        }
    }

    record Logins(
            Set<GHUser> previous,
            Set<GHUser> added,
            Set<GHUser> removed,
            Set<GHUser> updated) {
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

        String htmlBody = """
                <p>Team %s requires the following changes.</p>
                <p>Add:</p>
                %s
                <p>Remove:</p>
                %s
                <p>Final:</p>
                %s
                """.formatted(fullTeamName, toHtmlList(logins.added), toHtmlList(logins.removed),
                toHtmlList(logins.updated));

        ctx.sendEmail("sendDryRunEmail|", title, txtBody, htmlBody, dryRunEmail);
    }

    public String toPlainList(Set<GHUser> members) {
        if (members == null || members.isEmpty()) {
            return "none";
        }
        return members.stream()
                .map(x -> "- %s (%s)".formatted(x.getLogin(), x.getHtmlUrl()))
                .collect(Collectors.joining("\n"));
    }

    public String toHtmlList(Set<GHUser> members) {
        if (members == null || members.isEmpty()) {
            return "none";
        }
        return "<ul>\n" + members.stream()
                .map(x -> "<li><a href=\"%s\">%s</a></li>".formatted(x.getHtmlUrl(), x.getLogin()))
                .collect(Collectors.joining("\n"))
                + "\n</ul>";
    }
}
