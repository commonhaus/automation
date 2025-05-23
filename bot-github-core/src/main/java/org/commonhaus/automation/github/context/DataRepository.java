package org.commonhaus.automation.github.context;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;

import org.kohsuke.github.GHRepository;

import io.quarkus.runtime.annotations.RegisterForReflection;
import io.smallrye.graphql.client.Response;

public class DataRepository extends DataCommonType {

    private static final String RANGED_STATISTICS = """
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
                watchers(first:1) {
                  totalCount
                }
                forkCount
                stargazerCount
                isArchived
                isPrivate
                latestRelease {
                  name
                  tagName
                  publishedAt
                }
              }
            }
            """.stripIndent();

    DataRepository(DataCommonType other) {
        super(other);
    }

    public static ItemStatistics collectStatistics(GitHubQueryContext qc, GHRepository repo,
            LocalDate from, LocalDate toExclusive) {
        String baseQuery = "repo:%s created:%s..%s"
                .formatted(repo.getFullName(), from, toExclusive.minusDays(1));

        Count issues = queryType(qc, baseQuery + " is:issue", "ISSUE", from, toExclusive);
        Count prs = queryType(qc, baseQuery + " is:pr", "ISSUE", from, toExclusive);
        Count discussions = queryType(qc, baseQuery + " is:discussion", "DISCUSSION", from, toExclusive);

        return new ItemStatistics(
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
                discussions.closedItem);
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
            Response response = qc.execRepoQuerySync(RANGED_STATISTICS, variables);
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
    public static record ItemStatistics(
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
            int closedDiscussions) {
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

    private static boolean isBetween(Instant d, LocalDate start, LocalDate end) {
        return d != null && !d.isBefore(start.atStartOfDay().toInstant(ZoneOffset.UTC))
                && d.isBefore(end.atStartOfDay().toInstant(ZoneOffset.UTC));
    }
}
