package org.commonhaus.automation.github.context;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;

import io.smallrye.graphql.client.Response;

public class DataTeam extends DataCommonType {

    public final String name;
    public final String privacy;
    public final String slug;

    public DataTeam(JsonObject object) {
        super(object);
        this.name = JsonAttribute.name.stringFrom(object);
        this.privacy = JsonAttribute.privacy.stringFrom(object);
        this.slug = JsonAttribute.slug.stringFrom(object);
    }

    // @formatter:off
    static final String QUERY_TEAM_MEMBERSHIP = """
            query($login: String!, $slug: String!, $after: String) {
                organization(login: $login) {
                    team(slug: $slug) {
                        members(first: 100, membership: IMMEDIATE, after: $after) {
                            nodes {
                                login
                            }
                            pageInfo {
                                endCursor
                                hasNextPage
                            }
                        }
                    }
                }
            }
            """.stripIndent();
    // @formatter:on

    public static List<String> queryImmediateTeamMemberLogin(QueryContext qc, String orgName, String teamSlug) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("login", orgName);
        variables.put("slug", teamSlug);

        List<String> logins = new ArrayList<>();
        JsonObject pageInfo;
        String cursor = null;
        do {
            variables.put("after", cursor);
            Response response = qc.execQuerySync(QUERY_TEAM_MEMBERSHIP, variables);
            if (qc.hasErrors() || response == null) {
                break;
            }

            JsonObject data = response.getData();
            JsonObject organization = data.getJsonObject("organization");
            JsonObject team = organization.getJsonObject("team");
            JsonObject members = team.getJsonObject("members");
            JsonArray nodes = JsonAttribute.nodes.jsonArrayFrom(members);
            if (nodes == null) {
                break;
            }
            logins.addAll(nodes.stream()
                    .map(JsonObject.class::cast)
                    .map(JsonAttribute.login::stringFrom)
                    .toList());

            pageInfo = JsonAttribute.pageInfo.jsonObjectFrom(members);
            cursor = JsonAttribute.endCursor.stringFrom(pageInfo);
        } while (JsonAttribute.hasNextPage.booleanFromOrFalse(pageInfo));
        return logins;
    }
}
