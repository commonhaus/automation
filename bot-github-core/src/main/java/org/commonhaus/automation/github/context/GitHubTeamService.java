package org.commonhaus.automation.github.context;

import static org.commonhaus.automation.github.context.BaseQueryCache.COLLABORATORS;
import static org.commonhaus.automation.github.context.BaseQueryCache.TEAM_MEMBERS;
import static org.commonhaus.automation.github.context.QueryContext.toFullName;
import static org.commonhaus.automation.github.context.QueryContext.toOrganizationName;
import static org.commonhaus.automation.github.context.QueryContext.toRelativeName;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;

import org.commonhaus.automation.config.EmailNotification;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHTeam;
import org.kohsuke.github.GHUser;

import io.quarkus.logging.Log;

@ApplicationScoped
public class GitHubTeamService {

    GHTeam getCachedTeam(String teamFullName) {
        return TEAM_MEMBERS.get("ghTeam-" + teamFullName);
    }

    GHTeam putCachedTeam(GHTeam team, String teamFullName) {
        if (team != null) {
            TEAM_MEMBERS.put("ghTeam-" + teamFullName, team);
        }
        return team;
    }

    void resetCachedTeam(String teamFullName) {
        TEAM_MEMBERS.invalidate("ghTeam-" + teamFullName);
    }

    Set<GHUser> getCachedTeamMembers(String teamFullName) {
        return TEAM_MEMBERS.get(teamFullName);
    }

    Set<GHUser> putCachedTeamMembers(String teamFullName, Set<GHUser> members) {
        if (members != null) {
            TEAM_MEMBERS.put(teamFullName, members);
        }
        return members;
    }

    void resetCachedTeamMembers(String teamFullName) {
        TEAM_MEMBERS.invalidate(teamFullName);
    }

    /**
     * Invalidate the cache for the specified team to force
     * a refresh on the next access.
     *
     * @param org
     * @param ghTeam
     */
    public void refreshTeam(GHOrganization org, GHTeam ghTeam) {
        // Normalize team name to include org name
        String relativeName = ghTeam.getName().replace(org.getLogin() + "/", "");
        String teamFullName = org.getLogin() + "/" + relativeName;
        refreshTeam(org, teamFullName);
    }

    /**
     * Invalidate the cache for the specified team to force
     * a refresh on the next access.
     *
     * @param org
     * @param ghTeam
     */
    public void refreshTeam(GHOrganization org, String teamFullName) {
        resetCachedTeam(teamFullName);
        resetCachedTeamMembers(teamFullName);
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
            team = putCachedTeam(team, fullName);
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
        refreshTeam(org, teamFullName);
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
        Set<String> collaborators = COLLABORATORS.get(repoFullName);
        if (collaborators == null) {
            collaborators = qc.execGitHubSync((gh, dryRun) -> {
                GHRepository repo = gh.getRepository(repoFullName);
                return repo == null
                        ? null
                        : repo.getCollaboratorNames();
            });
            if (qc.hasErrors() || collaborators == null) {
                qc.clearNotFound();
                collaborators = Set.of();
            }
            Log.debugf("%s members: %s", repoFullName, collaborators);
            COLLABORATORS.put(repoFullName, collaborators);
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
        COLLABORATORS.invalidate(repo.getFullName());
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
            Log.warnf("[%s] syncTeamMembers: organization %s not found", qc.getLogId(), teamOrgName);
            return;
        }
        GHTeam team = getTeam(qc, org, relativeTeamName);
        if (team == null) {
            Log.warnf("[%s] syncTeamMembers: team %s not found in %s", qc.getLogId(), relativeTeamName, teamOrgName);
            return;
        }

        // Use GraphQL to get the immediate team members (exclude child teams)
        List<String> currentLogins = DataTeam.queryImmediateTeamMemberLogin(qc,
                teamOrgName, relativeTeamName);

        Set<String> toAdd = new HashSet<>(expectedLogins);
        toAdd.removeAll(currentLogins);
        toAdd.removeAll(ignoreUsers);

        Set<String> toRemove = new HashSet<>(currentLogins);
        toRemove.removeAll(expectedLogins);
        toRemove.removeAll(ignoreUsers);

        Set<String> finalLogins = new HashSet<>(currentLogins);
        finalLogins.addAll(toAdd);
        finalLogins.removeAll(toRemove);

        if (toAdd.isEmpty() && toRemove.isEmpty()) {
            Log.debugf("[%s] syncTeamMembers: No changes needed for team %s", qc.getLogId(), targetTeam);
            return;
        }

        // Create a record of changes
        TeamChanges changes = new TeamChanges(
                targetTeam,
                new ArrayList<>(currentLogins),
                new ArrayList<>(toAdd),
                new ArrayList<>(toRemove),
                new ArrayList<>(finalLogins));

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
                for (String login : toRemove) {
                    try {
                        GHUser user = gh.getUser(login);
                        team.remove(user);
                        Log.infof("[%s] syncTeamMembers: removed %s from %s", qc.getLogId(), login, targetTeam);
                    } catch (IOException e) {
                        String errorMsg = String.format("Failed to remove user %s: %s", login, e.getMessage());
                        Log.errorf(e, "[%s] syncTeamMembers: %s", qc.getLogId(), errorMsg);
                        errors.add(errorMsg);
                    }
                }

                // Then handle additions
                for (String login : toAdd) {
                    try {
                        GHUser user = gh.getUser(login);
                        team.add(user);
                        Log.infof("[%s] syncTeamMembers: added %s to %s", qc.getLogId(), login, targetTeam);
                    } catch (IOException e) {
                        String errorMsg = String.format("Failed to add user %s: %s", login, e.getMessage());
                        Log.errorf(e, "[%s] syncTeamMembers: %s", qc.getLogId(), errorMsg);
                        errors.add(errorMsg);
                    }
                }
                return null;
            });

            // invalidate cache to force refresh/re-fetch
            refreshTeam(org, targetTeam);

            // Send notification with results
            sendNotificationEmail(qc, changes, false, errors, addresses);
        }

        Log.infof("[%s] syncTeamMembers: finished syncing %s; %s added; %s removed",
                qc.getLogId(), targetTeam, toAdd.size(), toRemove.size());
    }

    // Record for team changes
    public record TeamChanges(
            String teamName,
            List<String> previousMembers,
            List<String> addedMembers,
            List<String> removedMembers,
            List<String> finalMembers) {
    }

    // Send notification email (works for both dry run and audit)
    private void sendNotificationEmail(QueryContext qc, TeamChanges changes,
            boolean isDryRun, Set<String> errors,
            EmailNotification addresses) {

        String[] recipients = isDryRun ? addresses.dryRun() : addresses.audit();

        if (recipients == null || recipients.length == 0) {
            Log.infof("[%s] sendNotificationEmail: No recipients configured. Changes for %s: Add=%s, Remove=%s",
                    qc.getLogId(), changes.teamName(), changes.addedMembers(), changes.removedMembers());
            return;
        }

        String actionPrefix = isDryRun ? "Dry Run" : "Audit";
        String statusIndicator = (!isDryRun && !errors.isEmpty()) ? "⚠️" : "✅";
        String title = String.format("%s %s: Team Member Sync for %s",
                statusIndicator, actionPrefix, changes.teamName());

        StringBuilder txtBody = new StringBuilder();
        if (isDryRun) {
            txtBody.append(String.format("Team %s would have the following changes:\n\n", changes.teamName()));
        } else {
            txtBody.append(String.format("Team %s has been updated with the following changes:\n\n", changes.teamName()));
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

        String emailType = isDryRun ? "TeamSync|DryRun" : "TeamSync|Audit";
        qc.sendEmail(emailType, title, txtBody.toString(), recipients);

        if (!errors.isEmpty()) {
            txtBody.append("\nErrors encountered during sync:\n");
            for (String error : errors) {
                txtBody.append("- ").append(error).append("\n");
            }

            // Note the scope for this error report: team sync scope first.
            qc.sendEmail(emailType, title + " finished with errors", txtBody.toString(),
                    getErrorAddresses(addresses, qc));
        }
    }

    private String[] getErrorAddresses(EmailNotification notifications, QueryContext qc) {
        Set<String> addresses = new HashSet<>();
        if (notifications != null) {
            Collections.addAll(addresses, notifications.errors());
        }
        Collections.addAll(addresses, qc.getErrorAddresses());
        return addresses.toArray(new String[0]);
    }

    // Format a list of members for email display
    private String formatMembers(List<String> members) {
        if (members == null || members.isEmpty()) {
            return "None";
        }
        return String.join(", ", members);
    }
}
