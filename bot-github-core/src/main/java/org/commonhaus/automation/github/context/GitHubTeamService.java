package org.commonhaus.automation.github.context;

import static org.commonhaus.automation.github.context.BaseQueryCache.COLLABORATORS;
import static org.commonhaus.automation.github.context.BaseQueryCache.TEAM_MEMBERS;
import static org.commonhaus.automation.github.context.QueryContext.toFullName;
import static org.commonhaus.automation.github.context.QueryContext.toOrganizationName;
import static org.commonhaus.automation.github.context.QueryContext.toRelativeName;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;

import org.commonhaus.automation.config.EmailNotification;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHOrganization.RepositoryRole;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHTeam;
import org.kohsuke.github.GHUser;

import io.quarkus.logging.Log;

@ApplicationScoped
public class GitHubTeamService {

    static GHTeam getCachedTeam(String teamFullName) {
        return TEAM_MEMBERS.get("ghTeam-" + teamFullName);
    }

    static GHTeam putCachedTeam(String teamFullName, GHTeam team) {
        if (team != null) {
            TEAM_MEMBERS.put("ghTeam-" + teamFullName, team);
        }
        return team;
    }

    static void resetCachedTeam(String teamFullName) {
        TEAM_MEMBERS.invalidate("ghTeam-" + teamFullName);
    }

    static Set<GHUser> getCachedTeamMembers(String teamFullName) {
        return TEAM_MEMBERS.get(teamFullName);
    }

    static Set<GHUser> putCachedTeamMembers(String teamFullName, Set<GHUser> members) {
        if (members != null) {
            TEAM_MEMBERS.put(teamFullName, members);
        }
        return members;
    }

    static void resetCachedTeamMembers(String teamFullName) {
        TEAM_MEMBERS.invalidate(teamFullName);
    }

    static Set<String> getCachedCollaborators(String repoFullName) {
        return COLLABORATORS.get(repoFullName);
    }

    static Set<String> putCachedCollaborators(String repoFullName, Set<String> members) {
        if (members != null) {
            COLLABORATORS.put(repoFullName, members);
        }
        return members;
    }

    public static void refreshCollaborators(String repoFullName) {
        COLLABORATORS.invalidate(repoFullName);
    }

    /**
     * Invalidate the cache for the specified team to force
     * a refresh on the next access.
     *
     * @param org
     * @param ghTeam
     */
    public static void refreshTeam(GHOrganization org, GHTeam ghTeam) {
        // Normalize team name to include org name
        String teamFullName = getFullTeamName(org, ghTeam);
        refreshTeam(teamFullName);
    }

    /**
     * Invalidate the cache for the specified team to force
     * a refresh on the next access.
     *
     * @param org
     * @param ghTeam
     */
    public static void refreshTeam(String teamFullName) {
        resetCachedTeam(teamFullName);
        resetCachedTeamMembers(teamFullName);
    }

    public static String getFullTeamName(GHOrganization org, GHTeam ghTeam) {
        String relativeName = ghTeam.getName().replace(org.getLogin() + "/", "");
        return org.getLogin() + "/" + relativeName;
    }

    /**
     * This is an indirect lookup: list of teams is fetched first,
     * then the team is found by name.
     *
     * @param qc QueryContext
     * @param org GHOrganization
     * @param relativeName name relative to organization
     * @return GHTeam or null if not found
     */
    public GHTeam getTeam(QueryContext qc, GHOrganization org, String relativeName) {
        String fullName = toFullName(org.getLogin(), relativeName);
        GHTeam team = getCachedTeam(fullName);
        if (team == null) {
            team = qc.execGitHubSync((gh, dryRun) -> {
                GHTeam result = org.getTeamByName(relativeName);
                return result;
            });
            qc.clearNotFound();
            team = putCachedTeam(fullName, team);
        }
        return team;
    }

    /**
     * @param qc QueryContext
     * @param teamFullName
     * @return
     */
    @Nonnull
    public Set<GHUser> getTeamMembers(QueryContext qc, String teamFullName) {
        String orgName = toOrganizationName(teamFullName);
        String relativeName = toRelativeName(orgName, teamFullName);

        GHOrganization org = qc.getOrganization(orgName);
        Set<GHUser> members = getCachedTeamMembers(teamFullName);
        if (members == null) {
            GHTeam ghTeam = getTeam(qc, org, relativeName);
            if (ghTeam == null) {
                return Set.of();
            } else {
                members = qc.execGitHubSync((gh, dryRun) -> {
                    return ghTeam.getMembers();
                });
                if (qc.hasErrors() || members == null) {
                    qc.clearNotFound();
                    members = Set.of();
                }
            }
            members = putCachedTeamMembers(teamFullName, members);
        }
        return members;
    }

    /**
     * @param qc QueryContext
     * @param teamFullName
     * @return
     */
    @Nonnull
    public Set<String> getTeamLogins(QueryContext qc, String teamFullName) {
        Set<GHUser> members = getTeamMembers(qc, teamFullName);
        return members.stream().map(GHUser::getLogin).collect(Collectors.toSet());
    }

    /**
     * @param qc QueryContext
     * @param teamFullName
     * @return
     */
    @Nonnull
    public TeamList getTeamList(QueryContext qc, String teamFullName) {
        Set<GHUser> members = getTeamMembers(qc, teamFullName);
        TeamList teamList = new TeamList(teamFullName, members);
        Log.debugf("[%s] %s members: %s", qc, qc.getLogId(), teamList.name, teamList.members);
        return teamList;
    }

    /**
     * @param qc QueryContext
     * @param user
     * @param teamFullName
     * @return
     */
    public boolean isTeamMember(QueryContext qc, GHUser user, String teamFullName) {
        Set<GHUser> members = getTeamMembers(qc, teamFullName);
        Log.debugf("%s members: %s", teamFullName, members.stream().map(GHUser::getLogin).toList());
        return members != null && members.contains(user);
    }

    /**
     * Check if a login is included in specified groups.
     *
     * @param qc QueryContext
     * @param login GitHub login to check
     * @param groups list of groups to check
     * @return true if login is included in groups or groups is null
     */
    public boolean isLoginIncluded(QueryContext qc, String login, List<String> groups) {
        if (groups == null) {
            return true;
        } else if (groups.isEmpty()) {
            return false;
        }
        for (var g : groups) {
            if (g.startsWith("@")) {
                TeamList team = getTeamList(qc, g.substring(1));
                if (team.isEmpty()) {
                    return false;
                }
                return team.members.stream().anyMatch(m -> m.login.equals(login));
            }
            return g.equals(login);
        }
        return false;
    }

    /**
     * Add a single user to a team.
     *
     * @param qc QueryContext
     * @param user
     * @param teamFullName
     */
    public void addTeamMember(QueryContext qc, GHUser user, String teamFullName) {
        if (qc.isDryRun()) {
            Log.debugf("[%s] addTeamMember would add %s to %s", qc.getLogId(), user.getLogin(), teamFullName);
            return;
        }
        String orgName = toOrganizationName(teamFullName);
        String relativeName = toRelativeName(orgName, teamFullName);
        GHOrganization org = qc.getOrganization(orgName);

        qc.execGitHubSync((gh, dryRun) -> {
            GHTeam ghTeam = org.getTeamByName(relativeName);
            ghTeam.add(user);
            return null;
        });
        qc.clearNotFound();
        refreshTeam(teamFullName);
    }

    /**
     * Get repository collaborators.
     *
     * @param qc QueryContext
     * @param repoFullName full repository name
     * @return set of collaborator logins or null if repository not found
     */
    @Nonnull
    public Set<String> getCollaborators(QueryContext qc, String repoFullName) {
        GHRepository repo = qc.getRepository(repoFullName);
        return repo == null ? Set.of() : getCollaborators(qc, repo);
    }

    /**
     * Get repository collaborators.
     *
     * @param qc QueryContext
     * @param repoFullName full repository name
     * @return set of collaborator logins or null if repository not found
     */
    @Nonnull
    public Set<String> getCollaborators(QueryContext qc, GHRepository repository) {
        if (repository == null) {
            return Set.of();
        }
        String repoFullName = repository.getFullName();
        Set<String> collaborators = getCachedCollaborators(repoFullName);
        if (collaborators == null) {
            collaborators = qc.execGitHubSync((gh, dryRun) -> {
                return repository.getCollaboratorNames();
            });
            if (qc.hasErrors() || collaborators == null) {
                qc.clearNotFound();
                collaborators = Set.of();
            }
            Log.debugf("%s members: %s", repoFullName, collaborators);
            putCachedCollaborators(repoFullName, collaborators);
        }
        return collaborators;
    }

    /**
     * @param qc QueryContext
     * @param repo
     * @param user
     */
    public void addCollaborators(QueryContext qc, GHRepository repo, List<GHUser> user) {
        if (qc.isDryRun()) {
            Log.debugf("[%s] addCollaborators would add %s to %s",
                    qc.getLogId(),
                    user.stream().map(GHUser::getLogin).toList(),
                    repo.getFullName());
            return;
        }
        qc.execGitHubSync((gh, dryRun) -> {
            repo.addCollaborators(user,
                    GHOrganization.RepositoryRole.from(GHOrganization.Permission.PULL));
            return null;
        });
        qc.clearNotFound();
        refreshCollaborators(repo.getFullName());
    }

    /**
     * @param qc QueryContext
     * @param user
     * @param repoName
     * @return
     */
    public boolean isCollaborator(QueryContext qc, GHUser user, String repoName) {
        Set<String> collaborators = getCollaborators(qc, repoName);
        return collaborators.contains(user.getLogin());
    }

    /**
     * Synchronize repository collaborators from a set of expected logins.
     *
     * @param qc QueryContext
     * @param repository The repository to update collaborators for
     * @param role The role to assign to new collaborators
     * @param expectedLogins Set of logins that should be collaborators
     * @param ignoreUsers List of users to ignore (not add or remove)
     * @param isDryRun Whether to perform actions or just report what would happen
     * @param addresses Email notification addresses
     */
    public void syncCollaborators(QueryContext qc, GHRepository repository, RepositoryRole role,
            Set<String> expectedLogins, List<String> ignoreUsers,
            boolean isDryRun, EmailNotification addresses) {

        String repoFullName = repository.getFullName();
        Set<String> currentLogins = getCollaborators(qc, repository);

        // Determine logins to add and remove
        MembershipChanges changes = computeMemberChanges(true,
                repoFullName, currentLogins, expectedLogins, ignoreUsers);

        if (changes.toAdd().isEmpty() && changes.toRemove().isEmpty()) {
            Log.debugf("[%s] syncCollaborators: No changes needed for repository %s",
                    qc.getLogId(), repository.getFullName());
            return;
        }

        if (isDryRun) {
            sendNotificationEmail(qc, changes, true, Collections.emptySet(), addresses);
        } else {
            final Set<String> errors = new HashSet<>();

            // Execute changes to team
            qc.execGitHubSync((gh, globalDryRunMode) -> {
                if (globalDryRunMode || isDryRun) {
                    return null;
                }

                if (!changes.toRemove().isEmpty()) {
                    List<GHUser> toRemove = new ArrayList<>();
                    // Process removals first
                    for (String login : changes.toRemove()) {
                        try {
                            toRemove.add(gh.getUser(login));
                        } catch (IOException e) {
                            String errorMsg = String.format("Failed to find collaborator %s: %s", login, e.getMessage());
                            Log.errorf(e, "[%s] syncCollaborators: %s", qc.getLogId(), errorMsg);
                            errors.add(errorMsg);
                        }
                    }

                    try {
                        repository.removeCollaborators(toRemove);
                    } catch (IOException e) {
                        String errorMsg = String.format("Failed to remove collaborators: %s", e.getMessage());
                        Log.errorf(e, "[%s] syncCollaborators: %s", qc.getLogId(), errorMsg);
                        errors.add(errorMsg);
                    }
                }

                if (!changes.toAdd().isEmpty()) {
                    List<GHUser> toAdd = new ArrayList<>();
                    for (String login : changes.toAdd()) {
                        try {
                            toAdd.add(gh.getUser(login));
                        } catch (IOException e) {
                            String errorMsg = String.format("Failed to find collaborator %s: %s", login, e.getMessage());
                            Log.errorf(e, "[%s] syncCollaborators: %s", qc.getLogId(), errorMsg);
                            errors.add(errorMsg);
                        }
                    }

                    try {
                        repository.addCollaborators(toAdd, role);
                    } catch (IOException e) {
                        String errorMsg = String.format("Failed to add collaborators: %s", e.getMessage());
                        Log.errorf(e, "[%s] syncCollaborators: %s", qc.getLogId(), errorMsg);
                        errors.add(errorMsg);
                    }
                }

                return null;
            });

            // invalidate cache to force refresh/re-fetch
            refreshCollaborators(repoFullName);

            // Send notification with results
            sendNotificationEmail(qc, changes, false, errors, addresses);
        }

        Log.infof("[%s] syncCollaborators: finished syncing %s; %s added; %s removed",
                qc.getLogId(), repoFullName, changes.toAdd().size(), changes.toRemove().size());
    }

    /**
     * Core method to synchronize team members across GitHub teams.
     * Used by both organization-centric and project-centric implementations.
     *
     * @param qc QueryContext
     * @param targetTeam full team name
     * @param expectedLogins set of expected logins
     * @param ignoreUsers list of users to ignore
     * @param isDryRun dry run mode / local override based on team sync configuration
     * @param addresses email notification addresses
     */
    public void syncMembers(QueryContext qc, String targetTeam,
            Set<String> expectedLogins, List<String> ignoreUsers,
            boolean isDryRun, EmailNotification addresses) {

        String teamOrgName = QueryContext.toOrganizationName(targetTeam);
        String relativeTeamName = QueryContext.toRelativeName(teamOrgName, targetTeam);

        // Get the org and team
        GHOrganization org = qc.getOrganization(teamOrgName);
        if (org == null) {
            Log.warnf("[%s] syncMembers: organization %s not found", qc.getLogId(), teamOrgName);
            return;
        }
        GHTeam team = getTeam(qc, org, relativeTeamName);
        if (team == null) {
            Log.warnf("[%s] syncMembers: team %s not found in %s", qc.getLogId(), relativeTeamName, teamOrgName);
            return;
        }

        // Use GraphQL to get the immediate team members (exclude child teams)
        List<String> currentLogins = DataTeam.queryImmediateTeamMemberLogin(qc,
                teamOrgName, relativeTeamName);

        MembershipChanges changes = computeMemberChanges(false,
                targetTeam, new HashSet<>(currentLogins), expectedLogins, ignoreUsers);

        if (changes.toAdd().isEmpty() && changes.toRemove().isEmpty()) {
            Log.debugf("[%s] syncMembers: No changes needed for team %s", qc.getLogId(), targetTeam);
            return;
        }

        if (isDryRun) {
            sendNotificationEmail(qc, changes, true, Collections.emptySet(), addresses);
        } else {
            final Set<String> errors = new HashSet<>();

            // Execute changes to team
            qc.execGitHubSync((gh, globalDryRunMode) -> {
                if (globalDryRunMode || isDryRun) {
                    return null;
                }

                // Process removals first
                for (String login : changes.toRemove()) {
                    try {
                        GHUser user = gh.getUser(login);
                        team.remove(user);
                        Log.infof("[%s] syncMembers: removed %s from %s", qc.getLogId(), login, targetTeam);
                    } catch (IOException e) {
                        String errorMsg = String.format("Failed to remove user %s: %s", login, e.getMessage());
                        Log.errorf(e, "[%s] syncMembers: %s", qc.getLogId(), errorMsg);
                        errors.add(errorMsg);
                    }
                }

                // Then handle additions
                for (String login : changes.toAdd()) {
                    try {
                        GHUser user = gh.getUser(login);
                        team.add(user);
                        Log.infof("[%s] syncMembers: added %s to %s", qc.getLogId(), login, targetTeam);
                    } catch (IOException e) {
                        String errorMsg = String.format("Failed to add user %s: %s", login, e.getMessage());
                        Log.errorf(e, "[%s] syncMembers: %s", qc.getLogId(), errorMsg);
                        errors.add(errorMsg);
                    }
                }
                return null;
            });

            // invalidate cache to force refresh/re-fetch
            refreshTeam(targetTeam);

            // Send notification with results
            sendNotificationEmail(qc, changes, false, errors, addresses);
        }

        Log.infof("[%s] syncMembers: finished syncing %s; %s added; %s removed",
                qc.getLogId(), targetTeam, changes.toAdd().size(), changes.toRemove().size());
    }

    private MembershipChanges computeMemberChanges(boolean collaborators, String resourceName,
            Set<String> currentLogins, Set<String> expectedLogins, List<String> ignoreUsers) {
        Set<String> toAdd = new HashSet<>(expectedLogins);
        toAdd.removeAll(currentLogins);
        toAdd.removeAll(ignoreUsers);

        Set<String> toRemove = new HashSet<>(currentLogins);
        toRemove.removeAll(expectedLogins);
        toRemove.removeAll(ignoreUsers);

        Set<String> finalLogins = new HashSet<>(currentLogins);
        finalLogins.addAll(toAdd);
        finalLogins.removeAll(toRemove);

        return new MembershipChanges(collaborators, resourceName, currentLogins, toAdd, toRemove, finalLogins);
    }

    // Record for team changes
    public record MembershipChanges(
            boolean collaborators,
            String fullName,
            Set<String> previousMembers,
            Set<String> addedMembers,
            Set<String> removedMembers,
            Set<String> finalMembers) {

        public Set<String> toAdd() {
            return addedMembers;
        }

        public Set<String> toRemove() {
            return removedMembers;
        }
    }

    // Send notification email (works for both dry run and audit)
    private void sendNotificationEmail(QueryContext qc, MembershipChanges changes,
            boolean isDryRun, Set<String> errors, EmailNotification addresses) {

        String[] recipients = isDryRun ? addresses.dryRun() : addresses.audit();

        if (recipients == null || recipients.length == 0) {
            Log.infof("[%s] sendNotificationEmail: No recipients configured. Changes for %s: Add=%s, Remove=%s",
                    qc.getLogId(), changes.fullName(), changes.addedMembers(), changes.removedMembers());
            return;
        }

        String unit = changes.collaborators() ? "Outside collaborators for repository" : "Team";

        String title = String.format("%s %s changes for %s (%s)",
                errors.isEmpty() ? "✅" : "⚠️",
                changes.collaborators() ? "Outside collaborator" : "Team membership",
                changes.fullName(),
                isDryRun ? "Dry Run" : "Audit");

        StringBuilder txtBody = new StringBuilder();
        if (isDryRun) {
            txtBody.append(String.format("%s %s would have the following changes:\n\n", unit, changes.fullName()));
        } else {
            txtBody.append(String.format("%s %s has been updated with the following changes:\n\n", unit, changes.fullName()));
        }

        txtBody.append(String.format("""
                Members %s (%d):
                %s

                Members %s (%d):
                %s

                %s membership (%d):
                %s

                %s membership (%d):
                %s
                """,
                isDryRun ? "to be added" : "added",
                changes.addedMembers().size(),
                formatMembers(changes.addedMembers()),

                isDryRun ? "to be removed" : "removed",
                changes.removedMembers().size(),
                formatMembers(changes.removedMembers()),

                isDryRun ? "Current" : "Previous",
                changes.previousMembers().size(),
                formatMembers(changes.previousMembers()),

                isDryRun ? "Final" : "Current",
                changes.finalMembers().size(),
                formatMembers(changes.finalMembers())));

        qc.sendEmail(qc.getLogId(), title, txtBody.toString(), recipients);

        if (!errors.isEmpty()) {
            txtBody.append("\nErrors encountered during sync:\n");
            for (String error : errors) {
                txtBody.append("- ").append(error).append("\n");
            }

            // Note the scope for this error report: team sync scope first.
            qc.sendEmail(qc.getLogId(), title + " finished with errors", txtBody.toString(),
                    qc.getErrorAddresses(addresses));
        }
    }

    // Format a list of members for email display
    private String formatMembers(Collection<String> members) {
        if (members == null || members.isEmpty()) {
            return "None";
        }
        return String.join(", ", members);
    }
}
