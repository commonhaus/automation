package org.commonhaus.automation.github.hr.voting;

import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import org.commonhaus.automation.github.context.QueryCache;

import com.cronutils.Function;
import com.github.benmanes.caffeine.cache.Caffeine;

public enum VoteQueryCache {
    ALT_ACTORS(b -> b.expireAfterWrite(1, TimeUnit.DAYS)),
    MANUAL_RESULT_COMMENT_ID(b -> b.expireAfterWrite(3, TimeUnit.HOURS));

    private QueryCache cache = null;

    VoteQueryCache(Consumer<Caffeine<Object, Object>> config) {
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
}
