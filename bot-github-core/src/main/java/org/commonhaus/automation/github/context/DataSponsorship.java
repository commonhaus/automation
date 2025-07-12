package org.commonhaus.automation.github.context;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;

import io.quarkus.logging.Log;
import io.smallrye.graphql.client.Response;

public class DataSponsorship extends DataCommonType {

    // @formatter:off
    static final String QUERY_RECENT_SPONSORS = """
            query($login: String!) {
                organization(login: $login) {
                    sponsorshipsAsMaintainer(first: 50, activeOnly: false, orderBy: {field: CREATED_AT, direction: DESC}) {
                        totalCount
                        nodes {
                            isActive
                            isOneTimePayment
                            sponsorEntity {
                                ... on User {
                                    login
                                }
                                ... on Organization {
                                    login
                                }
                            }
                        }
                        pageInfo {
                            hasNextPage
                            endCursor
                        }
                    }
                }
            }
            """.stripIndent();
    // @formatter:on

    public final boolean isActive;
    public final Instant createdAt;
    public final DataActor sponsorable;
    public final DataActor sponsorEntity;
    public final DataTier tier;

    DataSponsorship(JsonObject object) {
        super(object);

        // common with webhook
        this.createdAt = JsonAttribute.createdAt.instantFrom(object);
        this.sponsorable = JsonAttribute.sponsorable.actorFrom(object);
        this.sponsorEntity = JsonAttribute.sponsorEntity.actorFrom(object);
        this.tier = JsonAttribute.tier.tierFrom(object);

        // graphql only
        this.isActive = JsonAttribute.isActive.booleanFromOrFalse(object);
    }

    public String sponsorLogin() {
        return sponsorEntity.login;
    }

    @Override
    public String toString() {
        return "DataSponsorship [isActive=" + isActive + ", sponsorable=" + sponsorable + ", sponsorEntity="
                + sponsorEntity + "]";
    }

    public static List<DataSponsorship> queryRecentSponsors(GitHubQueryContext qc, String login) {
        if (qc.hasErrors()) {
            Log.debugf("[%s] queryRecentSponsors for sponsorable %s; skipping modify (errors)", qc.getLogId(), login);
            return null;
        }
        Log.debugf("[%s] queryRecentSponsors for sponsorable %s", qc.getLogId(), login);

        Map<String, Object> variables = new HashMap<>();
        variables.put("login", login);
        Response response = qc.execQuerySync(QUERY_RECENT_SPONSORS, variables);
        if (qc.hasErrors() || response == null || response.getData() == null) {
            return null;
        }

        JsonObject organization = JsonAttribute.organization.jsonObjectFrom(response.getData());
        JsonObject sponsorshipsAsMaintainer = JsonAttribute.sponsorshipsAsMaintainer.jsonObjectFrom(organization);
        JsonArray nodes = JsonAttribute.nodes.jsonArrayFrom(sponsorshipsAsMaintainer);
        if (nodes == null) {
            return List.of();
        }
        return nodes.stream()
                .map(JsonObject.class::cast)
                .map(DataSponsorship::new)
                .toList();
    }
}
