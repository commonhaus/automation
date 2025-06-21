package org.commonhaus.automation.github.context;

import static org.commonhaus.automation.github.context.GitHubQueryContext.isBetween;
import static org.commonhaus.automation.github.context.GitHubQueryContext.toOrganizationName;
import static org.commonhaus.automation.github.context.GitHubQueryContext.toRelativeName;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;

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
                        }
                        ... on Discussion {
                            closed
                            createdAt
                            closedAt
                        }
                    }
                }
            }
            """.stripIndent();

    private static final String REPO_STATISTICS = """
            query ($org: String!, $repo: String!) {
                repository(owner: $org, name: $repo) {
                    collaborators (first:1) {
                        totalCount
                    }
                    description
                    discussions(first:1, states:[OPEN]) {
                        totalCount
                    }
                    issues(first:1, states:[OPEN]) {
                        totalCount
                    }
                    pullRequests(first:1, states:[OPEN]) {
                        totalCount
                    }
                    releases(first:1) {
                        totalCount
                    }
                    latestRelease {
                        name
                        tagName
                        publishedAt
                    }
                    watchers(first:1) {
                        totalCount
                    }
                    forkCount
                    stargazerCount
                    isArchived
                    isPrivate
                }
            }
            """.stripIndent();

    DataRepository(JsonObject object) {
        super(object);
    }

    public static List<Instant> starHistory(GitHubQueryContext qc, GHRepository repo) {
        Map<String, Object> variables = new HashMap<>();
        List<Instant> stargazerDates = new java.util.ArrayList<>();

        DataPageInfo pageInfo = new DataPageInfo(null, false);
        do {
            variables.put("after", pageInfo.cursor());
            Response response = qc.execRepoQuerySync(PAGED_STARGAZERS, variables);
            if (qc.hasErrors()) {
                qc.checkRemoveNotFound();
                return null;
            }
            JsonObject stargazers = JsonAttribute.stargazers.jsonObjectFrom(response.getData());
            JsonArray edges = JsonAttribute.edges.extractArrayFrom(stargazers);
            for (var edge : edges) {
                Instant starredAt = JsonAttribute.starredAt.instantFrom(edge.asJsonObject());
                stargazerDates.add(starredAt);
            }
            pageInfo = JsonAttribute.pageInfo.pageInfoFrom(stargazers);
        } while (pageInfo.hasNextPage());
        return stargazerDates;
    }

    private static int countStargazersInRange(GitHubQueryContext qc, GHRepository repo,
            LocalDate from, LocalDate toExclusive) {
        List<Instant> allStars = starHistory(qc, repo);
        if (allStars == null) {
            return 0;
        }

        return (int) allStars.stream()
                .filter(starTime -> isBetween(starTime, from, toExclusive))
                .count();
    }

    public static WeeklyStatistics collectStatistics(GitHubQueryContext qc, GHRepository repo,
            LocalDate from, LocalDate toExclusive) {

        // Git date ranges are inclusive, so we need to adjust the end date
        String baseQuery = "repo:%s created:%s..%s"
                .formatted(repo.getFullName(), from, toExclusive.minusDays(1));

        Count issues = queryType(qc, baseQuery + " is:issue", "ISSUE", from, toExclusive);
        Count prs = queryType(qc, baseQuery + " is:pr", "ISSUE", from, toExclusive);
        Count discussions = queryType(qc, baseQuery + " is:discussion", "DISCUSSION", from, toExclusive);
        int stargazers = countStargazersInRange(qc, repo, from, toExclusive);

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
                stargazers);
    }

    public static Count queryType(GitHubQueryContext qc, String queryString, String searchType,
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

    public static ActivitySnapshot collectLiveStatistics(GitHubQueryContext qc, GHRepository repo) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("org", repo.getOwnerName());
        variables.put("repo", repo.getName());

        Response response = qc.execQuerySync(REPO_STATISTICS, variables);
        if (qc.hasErrors() || response == null) {
            return null;
        }
        JsonObject data = response.getData();
        JsonObject repository = data.getJsonObject("repository");
        JsonObject latestRelease = JsonAttribute.latestRelease.jsonObjectFrom(repository);

        return new ActivitySnapshot(
                JsonAttribute.description.stringFrom(repository),
                JsonAttribute.collaborators.totalCountFrom(repository),
                JsonAttribute.discussions.totalCountFrom(repository),
                JsonAttribute.issues.totalCountFrom(repository),
                JsonAttribute.pullRequests.totalCountFrom(repository),
                JsonAttribute.releases.totalCountFrom(repository),
                JsonAttribute.watchers.totalCountFrom(repository),
                JsonAttribute.forkCount.integerFrom(repository),
                JsonAttribute.stargazerCount.integerFrom(repository),
                JsonAttribute.isArchived.booleanFromOrFalse(repository),
                JsonAttribute.isPrivate.booleanFromOrFalse(repository),
                JsonAttribute.name.stringFrom(latestRelease),
                JsonAttribute.tagName.stringFrom(latestRelease),
                JsonAttribute.publishedAt.instantFrom(latestRelease));
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
            int stargazers) {
    }

    @RegisterForReflection
    public static record ActivitySnapshot(
            String description,
            int collaborators,
            int openDiscussions,
            int openIssues,
            int openPRs,
            int releases,
            int watchers,
            int forks,
            int stargazers,
            boolean archived,
            boolean privateRepo,
            String latestReleaseName,
            String latestReleaseTag,
            Instant latestReleaseDate) {
    }

    public static class WeeklyStatisticsBuilder {
        public final LocalDate weekStart;
        WeeklyStatistics initial;
        int stargazerCount;

        public WeeklyStatisticsBuilder(LocalDate weekStart, WeeklyStatistics initial) {
            this.weekStart = weekStart;
            this.initial = initial;
        }

        public WeeklyStatisticsBuilder addStar() {
            this.stargazerCount++;
            return this;
        }

        public WeeklyStatistics build() {
            return new WeeklyStatistics(
                    initial.from,
                    initial.toExclusive,
                    initial.newIssues,
                    initial.activeIssues,
                    initial.closedIssues,
                    initial.newPRs,
                    initial.activePRs,
                    initial.closedPRs,
                    initial.newDiscussions,
                    initial.activeDiscussions,
                    initial.closedDiscussions,
                    stargazerCount);
        }
    }
}
