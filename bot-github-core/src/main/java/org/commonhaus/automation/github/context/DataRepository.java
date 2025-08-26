package org.commonhaus.automation.github.context;

import static org.commonhaus.automation.github.context.GitHubQueryContext.isBetween;
import static org.commonhaus.automation.github.context.GitHubQueryContext.toOrganizationName;
import static org.commonhaus.automation.github.context.GitHubQueryContext.toRelativeName;

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

import org.kohsuke.github.GHRelease;
import org.kohsuke.github.GHRepository;

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

    private static final String PAGED_STARGAZERS = """
            query ($org: String!, $repo: String!, $after: String) {
                repository(owner: $org, name: $repo) {
                    stargazers(first: 100, after: $after, orderBy: {field: STARRED_AT, direction: DESC}) {
                        pageInfo {
                            endCursor
                            hasNextPage
                        }
                        edges {
                            starredAt
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

        if (releases == null) {
            qc.logAndSendContextErrors("Unable to list releases for " + repo.getFullName());
            return null;
        }

        for (GHRelease release : releases) {
            releaseDates.add(release.getPublished_at().toInstant());
        }
        return releaseDates;
    }

    public static List<Instant> starHistory(GitHubQueryContext qc, GHRepository repo) {
        Map<String, Object> variables = new HashMap<>();
        List<Instant> stargazerDates = new java.util.ArrayList<>();
        variables.put("org", repo.getOwnerName());
        variables.put("repo", repo.getName());

        DataPageInfo pageInfo = new DataPageInfo(null, false);
        do {
            variables.put("after", pageInfo.cursor());
            Response response = qc.execQuerySync(PAGED_STARGAZERS, variables);
            if (qc.hasErrors()) {
                qc.checkRemoveNotFound();
                return null;
            }
            JsonObject stargazers = JsonAttribute.stargazers.jsonObjectFrom(response.getData());
            JsonArray edges = JsonAttribute.edges.extractArrayFrom(stargazers);
            if (edges != null && !edges.isEmpty()) {
                for (var edge : edges) {
                    Instant starredAt = JsonAttribute.starredAt.instantFrom(edge.asJsonObject());
                    stargazerDates.add(starredAt);
                }
            }
            pageInfo = JsonAttribute.pageInfo.pageInfoFrom(stargazers);
        } while (pageInfo.hasNextPage());
        return stargazerDates;
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

        return new Collaborators(members);
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

    public record Collaborators(Set<Collaborator> members) {
        public Set<String> logins() {
            return members.stream()
                    .map(Collaborator::login)
                    .collect(Collectors.toSet());
        }

        public Set<String> adminLogins() {
            return members.stream()
                    .filter(c -> "ADMIN".equals(c.permission))
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
