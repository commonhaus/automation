package org.commonhaus.automation.opencollective;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;

import org.commonhaus.automation.ContextService;
import org.commonhaus.automation.GraphQLQueryContext;
import org.commonhaus.automation.PackagedException;
import org.commonhaus.automation.QueryCache;
import org.commonhaus.automation.config.BotConfig;
import org.commonhaus.automation.config.BotConfig.OpenCollectiveConfig;
import org.commonhaus.automation.opencollective.OpenCollectiveData.Account;
import org.commonhaus.automation.opencollective.OpenCollectiveData.OpenCollectiveFields;

import io.smallrye.graphql.client.Response;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClientBuilder;

public class OpenCollectiveQueryContext extends GraphQLQueryContext {
    private static final String ME = "oc-qc";

    private static final String AUTHORIZATION_HEADER = "Personal-Token";
    private static final AtomicReference<Optional<String>> personalToken = new AtomicReference<>();

    private static final QueryCache OC_CONNECTION = QueryCache.create("oc-connection",
            builder -> builder.expireAfterAccess(5, java.util.concurrent.TimeUnit.MINUTES));

    protected final OpenCollectiveConfig config;
    protected DynamicGraphQLClient graphQLClient;
    protected String accountId;

    public OpenCollectiveQueryContext(ContextService contextService, BotConfig botConfig) {
        super(contextService);
        config = botConfig.openCollective().orElse(null);
        if (config != null && config.collectiveSlug().isEmpty()) {
            throw new IllegalArgumentException("No collective slug provided");
        }
    }

    @Override
    public String getLogId() {
        return ME;
    }

    @Override
    public DynamicGraphQLClient getGraphQLClient() {
        if (graphQLClient == null && config != null) {
            graphQLClient = createGraphQLClient();
        }
        return graphQLClient;
    }

    @Override
    protected void cleanupAuthenticationError() {
        this.graphQLClient = null;
        OC_CONNECTION.invalidateAll();
    }

    public List<String> getGitContributorLogins() {
        List<Account> contributors = getContributors();
        if (contributors == null || contributors.isEmpty()) {
            return null;
        }
        return contributors.stream()
                .filter(x -> x.socialLinks != null && !x.socialLinks.isEmpty())
                .flatMap(x -> x.socialLinks.stream())
                .filter(x -> "GITHUB".equalsIgnoreCase(x.type))
                .map(x -> x.url.replace("https://github.com/", ""))
                .toList();
    }

    public List<Account> getContributors() {
        Map<String, Object> variables = new HashMap<>();
        variables.put("slug", config.collectiveSlug().get());

        int totalCount = 0;
        int offset = 0;
        int limit = 0;

        List<Account> contributors = new ArrayList<>();
        do {
            variables.put("offset", offset);
            Response response = execQuerySync(OpenCollectiveData.BACKERS_QUERY, variables);
            if (hasErrors() || response == null) {
                checkRemoveNotFound();
                return null;
            }
            JsonObject account = OpenCollectiveFields.account.jsonObjectFrom(response.getData());
            JsonObject members = OpenCollectiveFields.members.jsonObjectFrom(account);
            if (offset == 0) {
                totalCount = OpenCollectiveFields.totalCount.integerFrom(members);
                if (totalCount == 0) {
                    break;
                }
                limit = OpenCollectiveFields.limit.integerFrom(members);
            }
            JsonArray nodes = OpenCollectiveFields.nodes.jsonArrayFrom(members);
            contributors.addAll(nodes.stream()
                    .map(JsonObject.class::cast)
                    .map(o -> {
                        Account contributor = OpenCollectiveFields.account.accountFrom(o);
                        return contributor;
                    })
                    .toList());

            if (contributors.size() < totalCount) {
                offset += limit;
            }
        } while (contributors.size() < totalCount);
        contributors.removeIf(x -> x.id.equals(accountId));
        return contributors;
    }

    /**
     * Construct the GraphQL client for OpenCollective.
     * If a personal token is not provided, it will connect anonymously.
     */
    private DynamicGraphQLClient createGraphQLClient() {
        DynamicGraphQLClient dql = OC_CONNECTION.get(ME);
        if (dql != null) {
            return dql;
        }

        Optional<String> installationToken = personalToken.get();
        if (installationToken == null) {
            if (config.personalToken().isPresent()) {
                installationToken = Optional.of(config.personalToken().get());
            } else {
                installationToken = Optional.empty();
            }
            personalToken.set(installationToken);
        }

        // Create the GraphQL client
        DynamicGraphQLClientBuilder builder = DynamicGraphQLClientBuilder.newBuilder()
                .url(config.apiEndpoint());

        // Set the authorization header if a personal token is provided
        if (installationToken.isPresent()) {
            builder.header(AUTHORIZATION_HEADER, installationToken.get());
        }

        // Execute a basic query to check if the client is working and the collective is known.
        try {
            dql = builder.build();

            Map<String, Object> variables = Map.of("slug", config.collectiveSlug().get());
            Response response = dql.executeSync(OpenCollectiveData.BASIC_QUERY, variables);
            if (response.hasError()) {
                throw new PackagedException(List.of(), response.getErrors());
            }
            Account account = OpenCollectiveFields.account.accountFrom(response.getData());
            if (account == null) {
                throw new IllegalStateException("No account found for slug: " + config.collectiveSlug().get());
            }
            accountId = account.id;

            // If the query was successful, store the client in the cache and return it
            OC_CONNECTION.put(ME, dql);
            return dql;
        } catch (Exception e) {
            throw new PackagedException(List.of(e), null);
        }
    }
}
