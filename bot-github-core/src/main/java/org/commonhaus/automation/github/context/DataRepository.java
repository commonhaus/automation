package org.commonhaus.automation.github.context;

import static org.commonhaus.automation.github.context.GitHubQueryContext.toOrganizationName;
import static org.commonhaus.automation.github.context.GitHubQueryContext.toRelativeName;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.json.JsonObject;

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

    DataRepository(JsonObject object) {
        super(object);
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
}
