package org.commonhaus.automation.github.context;

import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import org.commonhaus.automation.QueryCache;
import org.kohsuke.github.GitHub;

import com.cronutils.Function;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;

public enum BaseQueryCache {
    CONNECTION(b -> b.expireAfterWrite(15, TimeUnit.MINUTES)),

    LABELS(b -> b.expireAfterWrite(1, TimeUnit.DAYS)),
    TEAM_MEMBERS(b -> b.expireAfterWrite(1, TimeUnit.DAYS)),
    COLLABORATORS(b -> b.expireAfterWrite(1, TimeUnit.DAYS)),

    BOT_LOGIN(b -> b.expireAfterWrite(6, TimeUnit.HOURS)),

    RECENT_BOT_CONTENT(b -> b.expireAfterWrite(6, TimeUnit.HOURS));

    private QueryCache cache = null;

    BaseQueryCache(Consumer<Caffeine<Object, Object>> config) {
        this.cache = QueryCache.create(name(), config);
    }

    public <T> T compute(String key, BiFunction<String, Object, T> mappingFunction) {
        return cache.compute(key, mappingFunction);
    }

    public <T> T computeIfAbsent(String key, Function<String, T> mappingFunction) {
        return (T) cache.computeIfAbsent(key, mappingFunction);
    }

    public <T> T computeIfPresent(String key, BiFunction<String, Object, T> mappingFunction) {
        return (T) cache.computeIfPresent(key, mappingFunction);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) cache.get(key);
    }

    public <T> void put(String key, T value) {
        cache.put(key, value);
    }

    public void invalidateAll() {
        cache.invalidateAll();
    }

    public void invalidate(String login) {
        cache.invalidate(login);
    }

    public static GitHub getCachedUserConnection(String nodeId) {
        return BaseQueryCache.CONNECTION.get("user-" + nodeId);
    }

    public static GitHub putCachedUserConnection(String nodeId, GitHub gh) {
        BaseQueryCache.CONNECTION.put("user-" + nodeId, gh);
        return gh;
    }

    public static GitHub getCachedGitHubClient(long installationId) {
        return BaseQueryCache.CONNECTION.get("gh-" + installationId);
    }

    public static GitHub putCachedGithubClient(long installationId, GitHub gh) {
        BaseQueryCache.CONNECTION.put("gh-" + installationId, gh);
        return gh;
    }

    public static DynamicGraphQLClient getCachedGraphQLClient(long installationId) {
        return BaseQueryCache.CONNECTION.get("graphQL-" + installationId);
    }

    public static DynamicGraphQLClient putCachedGraphQLClient(long installationId, DynamicGraphQLClient graphQLClient) {
        BaseQueryCache.CONNECTION.put("graphQL-" + installationId, graphQLClient);
        return graphQLClient;
    }

    public static void resetCachedClients(long installationId) {
        if (installationId < 0) { // user session
            return;
        }
        BaseQueryCache.CONNECTION.invalidate("gh-" + installationId);
        BaseQueryCache.CONNECTION.invalidate("graphQL-" + installationId);
    }
}
