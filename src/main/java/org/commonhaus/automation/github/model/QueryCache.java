package org.commonhaus.automation.github.model;

import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import com.cronutils.Function;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.quarkus.logging.Log;

public enum QueryCache {
    LABELS(Caffeine.newBuilder()
            .expireAfterWrite(6, TimeUnit.HOURS)
            .build()),
    GLOB(Caffeine.newBuilder()
            .maximumSize(200)
            .build()),
    RECENT_BOT_CONTENT(Caffeine.newBuilder()
            .expireAfterWrite(6, TimeUnit.HOURS)
            .build()),
    RECENT_VOTE_CHECK(Caffeine.newBuilder()
            .expireAfterAccess(6, TimeUnit.HOURS)
            .build()),
    TEAM(Caffeine.newBuilder()
            .expireAfterWrite(6, TimeUnit.HOURS)
            .build());

    private final Cache<String, Object> cache;

    QueryCache(Cache<String, Object> cache) {
        this.cache = cache;
    }

    @SuppressWarnings("unchecked")
    public <T> T getCachedValue(String key) {
        T result = (T) cache.getIfPresent(key);
        if (result != null) {
            Log.debugf(":: HIT %s/%s ::: ", this.name(), key);
        } else {
            Log.debugf(":: MISS %s/%s ::: ", this.name(), key);
        }
        return result;
    }

    /**
     * Put a value into the cache
     *
     * @param key lookup key
     * @param value to be cached
     * @return new value
     */
    public <T> T putCachedValue(String key, T value) {
        Log.debugf(":: PUT %s/%s ::: ", this.name(), key);
        if (value == null) {
            cache.invalidate(key);
        } else {
            cache.put(key, value);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public <T> T computeIfAbsent(String key, Function<String, T> mappingFunction) {
        Log.debugf(":: UPDATE %s/%s ::: ", this.name(), key);
        return (T) cache.asMap().computeIfAbsent(key, mappingFunction::apply);
    }

    @SuppressWarnings("unchecked")
    public <T> T computeIfPresent(String key, BiFunction<String, Object, T> mappingFunction) {
        Log.debugf(":: UPDATE %s/%s ::: ", this.name(), key);
        return (T) cache.asMap().computeIfPresent(key, mappingFunction);
    }

    public void invalidate(String key) {
        Log.debugf(":: INVALIDATE %s/%s ::: ", this.name(), key);
        cache.invalidate(key);
    }

    public void invalidateAll() {
        Log.debugf(":: INVALIDATE ALL %s ::: ", this.name());
        cache.invalidateAll();
    }
}
