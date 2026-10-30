package org.commonhaus.automation.github.context;

import static org.commonhaus.automation.github.context.GitHubQueryContext.isBetween;
import static org.commonhaus.automation.github.context.GitHubQueryContext.toOrganizationName;
import static org.commonhaus.automation.github.context.GitHubQueryContext.toRelativeName;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;

import org.kohsuke.github.GHInvitation;
import org.kohsuke.github.GHRelease;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHUser;

import io.quarkus.logging.Log;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.smallrye.graphql.client.Response;

public class DataRepository extends DataCommonType {

    static final String QUERY_ADMINS = """
            query($owner: String!, $name: String!, $after: String) {
                repository(owner: $owner, name: $name) {
                    collaborators(first: 100, after: $after) {
                        edges {
                            node {
                                login
                            }
                            permission
                            permissionSources {
                                source {
                                    __typename
                                }
                                permission
                            }
                        }
                    }
                }
            }
            """.stripIndent();

    private static final String RANGED_ITEM_STATISTICS = """
            query($query: String!, $searchType: SearchType!, $after: String) {
                search(query: $query, type: $searchType, first: 100, after: $after) {
                    pageInfo {
                        endCursor
                        hasNextPage
                    }
                    nodes {
                        ... on Issue {
                            closed
                            createdAt
                            closedAt
                        }
                        ... on PullRequest {
                            closed
                            createdAt
                            closedAt
                            merged
                        }
                        ... on Discussion {
                            answerChosenAt
                            closed
                            createdAt
                            closedAt
                        }
                    }
                }
            }
            """.stripIndent();

    DataRepository(JsonObject object) {
        super(object);
    }

    public static List<Instant> releaseHistory(GitHubQueryContext qc, GHRepository repo) {
        List<Instant> releaseDates = new java.util.ArrayList<>();

        List<GHRelease> releases = qc.execGitHubSync((gh, dr) -> {
            return repo.listReleases().toList();
        });

        if (qc.hasErrors()) {
            qc.checkRemoveNotFound();
            return null;
        }

        for (GHRelease release : releases) {
            releaseDates.add(release.getPublished_at().toInstant());
        }
        return releaseDates;
    }

    /**
     * GitHub restricted the stargazers listing endpoints (both {@link GHRepository#listStargazers()}
     * and {@link GHRepository#listStargazers2()}) to repo admins/collaborators in July 2026, and the
     * GraphQL {@code stargazers} field is not accessible to GitHub App installation tokens at all.
     * The privacy-safe replacement ({@code GET /repos/{owner}/{repo}/stargazers/history}) has no typed
     * support yet in org.kohsuke:github-api (1.330, or the RC-only 2.x line). Star history is disabled
     * until one of those paths is available.
     */
    public static List<Instant> starHistory(GitHubQueryContext qc, GHRepository repo) {
        return List.of();
    }

    private static int countStargazersInRange(GitHubQueryContext qc, GHRepository repo,
            LocalDate from, LocalDate toExclusive) {
        List<Instant> allStars = starHistory(qc, repo);
        return filterRange(allStars, from, toExclusive);
    }

    private static int countReleasesInRange(GitHubQueryContext qc, GHRepository repo,
            LocalDate from, LocalDate toExclusive) {
        List<Instant> allReleases = releaseHistory(qc, repo);
        return filterRange(allReleases, from, toExclusive);
    }

    private static int filterRange(List<Instant> list, LocalDate from, LocalDate toExclusive) {
        if (list == null) {
            return 0;
        }
        return (int) list.stream()
                .filter(releaseTime -> isBetween(releaseTime, from, toExclusive))
                .count();
    }

    public static void itemHistory(GitHubQueryContext qc, GHRepository repo,
            Map<LocalDate, WeeklyStatisticsBuilder> history) {

        Map<String, Object> variables = new HashMap<>();
        variables.put("query", "repo:" + repo.getFullName());

        for (var type : List.of("ISSUE", "DISCUSSION")) {
            variables.put("searchType", type);
            DataPageInfo pageInfo = new DataPageInfo(null, false);
            do {
                variables.put("after", pageInfo.cursor());
                Response response = qc.execQuerySync(RANGED_ITEM_STATISTICS, variables);
                if (qc.hasErrors()) {
                    break;
                }
                JsonObject search = JsonAttribute.search.jsonObjectFrom(response.getData());
                JsonArray nodes = JsonAttribute.nodes.jsonArrayFrom(search);

                for (var node : nodes) {
                    var createdAt = instantToStartDate(JsonAttribute.createdAt.instantFrom(node.asJsonObject()));
                    var closedAt = instantToStartDate(JsonAttribute.closedAt.instantFrom(node.asJsonObject()));
                    boolean closed = JsonAttribute.closed.booleanFromOrFalse(node.asJsonObject());

                    history.computeIfAbsent(createdAt, WeeklyStatisticsBuilder::new).addNewItem(node.asJsonObject());

                    // count active weeks
                    LocalDate endDate = closed ? closedAt : startOfWeekSunday(LocalDate.now());
                    for (LocalDate week = createdAt; !week.isAfter(endDate); week = week.plusDays(7)) {
                        if (week.equals(createdAt) || (closed && week.equals(endDate))) {
                            continue;
                        }
                        history.computeIfAbsent(week, WeeklyStatisticsBuilder::new).addActiveItem(node.asJsonObject());
                    }

                    if (closed) {
                        history.computeIfAbsent(closedAt, WeeklyStatisticsBuilder::new).addClosedItem(node.asJsonObject());
                    }
                }

                pageInfo = JsonAttribute.pageInfo.pageInfoFrom(search);
            } while (pageInfo.hasNextPage());
        }
    }

    public static WeeklyStatistics collectStatistics(GitHubQueryContext qc, GHRepository repo,
            LocalDate from, LocalDate toExclusive,
            boolean includeStargazers, boolean includeReleases) {

        // Git date ranges are inclusive, so we need to adjust the end date
        String baseQuery = "repo:%s updated:%s..%s"
                .formatted(repo.getFullName(), from, toExclusive.minusDays(1));

        Count issues = queryType(qc, baseQuery + " is:issue", "ISSUE", from, toExclusive);
        Count prs = queryType(qc, baseQuery + " is:pr", "ISSUE", from, toExclusive);
        Count discussions = queryType(qc, baseQuery + " is:discussion", "DISCUSSION", from, toExclusive);

        int stargazers = includeStargazers
                ? countStargazersInRange(qc, repo, from, toExclusive)
                : 0;

        int releaseCount = includeReleases
                ? countReleasesInRange(qc, repo, from, toExclusive)
                : 0;

        return new WeeklyStatistics(
                from,
                toExclusive,
                issues.newItem,
                issues.openItem,
                issues.closedItem,
                prs.newItem,
                prs.openItem,
                prs.closedItem,
                discussions.newItem,
                discussions.openItem,
                discussions.closedItem,
                stargazers,
                releaseCount);
    }

    static Count queryType(GitHubQueryContext qc, String queryString, String searchType,
            LocalDate from, LocalDate toExclusive) {
        Map<String, Object> variables = new HashMap<>();

        variables.put("query", queryString);
        variables.put("searchType", searchType);

        Count count = new Count();
        count.type = searchType;
        DataPageInfo pageInfo = new DataPageInfo(null, false);
        do {
            variables.put("after", pageInfo.cursor());
            Response response = qc.execRepoQuerySync(RANGED_ITEM_STATISTICS, variables);
            if (qc.hasErrors()) {
                break;
            }
            JsonObject search = JsonAttribute.search.jsonObjectFrom(response.getData());
            JsonArray nodes = JsonAttribute.nodes.jsonArrayFrom(search);

            for (var node : nodes) {
                var createdAt = JsonAttribute.createdAt.instantFrom(node.asJsonObject());
                var closedAt = JsonAttribute.closedAt.instantFrom(node.asJsonObject());
                boolean closed = JsonAttribute.closed.booleanFromOrFalse(node.asJsonObject());

                if (closed && isBetween(closedAt, from, toExclusive)) {
                    count.closedItem++;
                } else if (isBetween(createdAt, from, toExclusive)) {
                    count.newItem++;
                } else if (!closed) {
                    count.openItem++;
                } // otherwise modified for some reason
            }
            pageInfo = JsonAttribute.pageInfo.pageInfoFrom(search);
        } while (pageInfo.hasNextPage());
        return count;
    }

    /**
     * {@link GHInvitation} in {@code org.kohsuke.github-api} defines {@code private GHUser invitee}
     * which Jackson populates directly during deserialization of {@code GET /repos/{owner}/{repo}/invitations}
     * (https://docs.github.com/en/rest/collaborators/invitations#list-repository-invitations),
     * but {@link GHInvitation} does not expose a public getter for the invitee (or other fields;
     * see e.g. https://github.com/hub4j/github-api/issues/1598, https://github.com/hub4j/github-api/pull/2343).
     * Reflection is used here to read the populated invitee user and expired status.
     */
    static final Field INVITEE_FIELD;
    static final Field EXPIRED_FIELD;
    static final Method GET_INVITEE_METHOD;
    static final Method IS_EXPIRED_METHOD;
    static {
        Field inviteeField = null;
        Field expiredField = null;
        Method getInviteeMethod = null;
        Method isExpiredMethod = null;
        try {
            inviteeField = GHInvitation.class.getDeclaredField("invitee");
            inviteeField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            Log.warn("Failed to find 'invitee' field on GHInvitation", e);
        }
        try {
            expiredField = GHInvitation.class.getDeclaredField("expired");
            expiredField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            // Not present in older github-api versions; see https://github.com/hub4j/github-api/pull/2343
        }
        try {
            getInviteeMethod = GHInvitation.class.getMethod("getInvitee");
        } catch (NoSuchMethodException e) {
            // Not present in older github-api versions; see https://github.com/hub4j/github-api/pull/2343
        }
        try {
            isExpiredMethod = GHInvitation.class.getMethod("isExpired");
        } catch (NoSuchMethodException e) {
            // Not present in older github-api versions; see https://github.com/hub4j/github-api/pull/2343
        }
        INVITEE_FIELD = inviteeField;
        EXPIRED_FIELD = expiredField;
        GET_INVITEE_METHOD = getInviteeMethod;
        IS_EXPIRED_METHOD = isExpiredMethod;
    }

    public static Set<String> queryPendingInvitations(GitHubQueryContext qc, String repoFullName) {
        GHRepository repo = qc.getRepository(repoFullName);
        if (repo == null) {
            return Set.of();
        }
        return queryPendingInvitations(qc, repo);
    }

    public static Set<String> queryPendingInvitations(GitHubQueryContext qc, GHRepository repo) {
        if (INVITEE_FIELD == null && GET_INVITEE_METHOD == null) {
            return Set.of();
        }
        List<GHInvitation> invitations = qc.execGitHubSync((gh, dr) -> {
            var list = repo.listInvitations();
            return list == null ? List.of() : list.toList();
        });
        if (invitations == null || qc.hasErrors()) {
            qc.checkRemoveNotFound();
            return Set.of();
        }
        Set<String> invitedLogins = new HashSet<>();
        for (GHInvitation invitation : invitations) {
            if (isExpired(invitation)) {
                continue;
            }
            GHUser user = getInvitee(invitation);
            if (user != null && user.getLogin() != null) {
                invitedLogins.add(user.getLogin());
            }
        }
        return invitedLogins;
    }

    private static boolean isExpired(GHInvitation invitation) {
        if (IS_EXPIRED_METHOD != null) {
            try {
                return (boolean) IS_EXPIRED_METHOD.invoke(invitation);
            } catch (Exception e) {
                Log.warnf("Failed to invoke 'isExpired' method on GHInvitation: %s", e.getMessage());
            }
        }
        if (EXPIRED_FIELD != null) {
            try {
                return EXPIRED_FIELD.getBoolean(invitation);
            } catch (IllegalAccessException e) {
                Log.warnf("Failed to access 'expired' field on GHInvitation: %s", e.getMessage());
            }
        }
        return false;
    }

    private static GHUser getInvitee(GHInvitation invitation) {
        if (GET_INVITEE_METHOD != null) {
            try {
                return (GHUser) GET_INVITEE_METHOD.invoke(invitation);
            } catch (Exception e) {
                Log.warnf("Failed to invoke 'getInvitee' method on GHInvitation: %s", e.getMessage());
            }
        }
        if (INVITEE_FIELD != null) {
            try {
                return (GHUser) INVITEE_FIELD.get(invitation);
            } catch (IllegalAccessException e) {
                Log.warnf("Failed to access 'invitee' field on GHInvitation: %s", e.getMessage());
            }
        }
        return null;
    }

    public static Collaborators queryCollaborators(GitHubQueryContext qc, String repoFullName) {
        Map<String, Object> variables = new HashMap<>();
        String org = toOrganizationName(repoFullName);
        String name = toRelativeName(org, repoFullName);
        variables.putIfAbsent("owner", org);
        variables.putIfAbsent("name", name);

        Set<Collaborator> members = new HashSet<>();
        DataPageInfo pageInfo = new DataPageInfo(null, false);
        do {
            variables.put("after", pageInfo.cursor());
            Response response = qc.execQuerySync(QUERY_ADMINS, variables);
            if (qc.hasErrors()) {
                qc.checkRemoveNotFound();
                break;
            }

            var data = response.getData();
            var collaborators = JsonAttribute.collaborators.extractObjectFrom(data, JsonAttribute.repository);
            List<Collaborator> collaboratorList = JsonAttribute.edges.jsonArrayFrom(collaborators)
                    .stream()
                    .map(edge -> {
                        var obj = edge.asJsonObject();
                        var node = JsonAttribute.node.jsonObjectFrom(obj);
                        var login = JsonAttribute.login.stringFrom(node);
                        var permission = JsonAttribute.permission.stringFrom(obj);
                        var permissionSources = JsonAttribute.permissionSources.jsonArrayFrom(obj)
                                .stream()
                                .map(ps -> {
                                    var psObj = ps.asJsonObject();
                                    var source = JsonAttribute.source.jsonObjectFrom(psObj);
                                    var type = JsonAttribute.typeName.stringFrom(source);
                                    var perm = JsonAttribute.permission.stringFrom(psObj);
                                    return new CollaboratorPermission(perm, type);
                                })
                                .collect(Collectors.toList());
                        return new Collaborator(login, permission, permissionSources);
                    })
                    .collect(Collectors.toList());

            members.addAll(collaboratorList);
            pageInfo = JsonAttribute.pageInfo.pageInfoFrom(collaborators);
        } while (pageInfo.hasNextPage());

        Set<String> pendingInvitations = queryPendingInvitations(qc, repoFullName);

        return new Collaborators(members, pendingInvitations);
    }

    public record CollaboratorPermission(
            String permission,
            String permissionSourceType) {
    };

    public record Collaborator(
            String login,
            String permission,
            List<CollaboratorPermission> permissionSources) {
    }

    public record Collaborators(Set<Collaborator> members, Set<String> pendingInvitations) {
        public Collaborators(Set<Collaborator> members) {
            this(members, Set.of());
        }

        public Set<String> pendingInvitations() {
            return pendingInvitations == null ? Set.of() : pendingInvitations;
        }

        public Set<String> logins() {
            return members.stream()
                    .map(Collaborator::login)
                    .collect(Collectors.toSet());
        }

        public Set<String> adminLogins() {
            return members.stream()
                    .filter(c -> "ADMIN".equalsIgnoreCase(c.permission))
                    .map(Collaborator::login)
                    .collect(Collectors.toSet());
        }

        public Set<String> teamGrantedAccessLogins() {
            return members.stream()
                    .filter(collaborator -> collaborator.permissionSources().stream()
                            .noneMatch(source -> "Repository".equalsIgnoreCase(source.permissionSourceType())))
                    .map(Collaborator::login)
                    .collect(Collectors.toSet());
        }

        public Set<String> directCollaboratorLogins() {
            return members.stream()
                    .filter(collaborator -> collaborator.permissionSources().stream()
                            .anyMatch(source -> "Repository".equalsIgnoreCase(source.permissionSourceType())))
                    .map(Collaborator::login)
                    .collect(Collectors.toSet());
        }
    }

    static class Count {
        String type;
        int newItem;
        int closedItem;
        int openItem;
    }

    @RegisterForReflection
    public static record WeeklyStatistics(
            LocalDate from,
            LocalDate toExclusive,
            int newIssues,
            int activeIssues,
            int closedIssues,
            int newPRs,
            int activePRs,
            int closedPRs,
            int newDiscussions,
            int activeDiscussions,
            int closedDiscussions,
            int stargazers,
            int releaseCount) {
    }

    public static class WeeklyStatisticsBuilder {
        public final LocalDate weekStart;
        int newIssues;
        int activeIssues;
        int closedIssues;
        int newPRs;
        int activePRs;
        int closedPRs;
        int newDiscussions;
        int activeDiscussions;
        int closedDiscussions;
        int stargazers;
        int releaseCount;

        public WeeklyStatisticsBuilder(LocalDate weekStart) {
            this.weekStart = weekStart;
        }

        public void addClosedItem(JsonObject node) {
            if (JsonAttribute.answerChosenAt.valueFrom(node) != null) {
                this.closedDiscussions++;
            } else if (JsonAttribute.merged.existsIn(node)) {
                this.closedPRs++;
            } else {
                this.closedIssues++;
            }
        }

        public void addActiveItem(JsonObject node) {
            if (JsonAttribute.answerChosenAt.valueFrom(node) != null) {
                this.activeDiscussions++;
            } else if (JsonAttribute.merged.existsIn(node)) {
                this.activePRs++;
            } else {
                this.activeIssues++;
            }
        }

        public void addNewItem(JsonObject node) {
            if (JsonAttribute.answerChosenAt.valueFrom(node) != null) {
                this.newDiscussions++;
            } else if (JsonAttribute.merged.existsIn(node)) {
                this.newPRs++;
            } else {
                this.newIssues++;
            }
        }

        public WeeklyStatisticsBuilder addStar() {
            this.stargazers++;
            return this;
        }

        public WeeklyStatisticsBuilder addRelease() {
            this.releaseCount++;
            return this;
        }

        public WeeklyStatistics build() {
            return new WeeklyStatistics(
                    weekStart,
                    weekStart.plusDays(7),
                    newIssues,
                    activeIssues,
                    closedIssues,
                    newPRs,
                    activePRs,
                    closedPRs,
                    newDiscussions,
                    activeDiscussions,
                    closedDiscussions,
                    stargazers,
                    releaseCount);
        }
    }

    private static LocalDate instantToStartDate(Instant date) {
        if (date == null) {
            return null;
        }
        return startOfWeekSunday(date.atZone(ZoneOffset.UTC).toLocalDate());
    }

    private static LocalDate startOfWeekSunday(LocalDate date) {
        return date.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.SUNDAY));
    }
}
