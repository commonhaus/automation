package org.commonhaus.automation.github.context;

import java.util.function.BiFunction;
import java.util.function.Consumer;

import com.cronutils.Function;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.quarkus.logging.Log;

public class QueryCache {

    public static QueryCache create(String name, Consumer<Caffeine<Object, Object>> config) {
        Caffeine<Object, Object> builder = Caffeine.newBuilder();
        config.accept(builder);
        return new QueryCache(name, builder.build());
    }

    private final Cache<String, Object> cache;
    private final String name;

    QueryCache(String name, Cache<String, Object> cache) {
        this.cache = cache;
        this.name = name;
    }

    @SuppressWarnings("unchecked")
    public <T> T getCachedValue(String key) {
        T result = (T) cache.getIfPresent(key);
        if (result != null) {
            Log.debugf(":: HIT %s/%s ::: ", name, key);
        } else {
            Log.debugf(":: MISS %s/%s ::: ", name, key);
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
        Log.debugf(":: PUT %s/%s ::: ", name, key);
        if (value == null) {
            cache.invalidate(key);
        } else {
            cache.put(key, value);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public <T> T computeIfAbsent(String key, Function<String, T> mappingFunction) {
        Log.debugf(":: UPDATE %s/%s ::: ", name, key);
        return (T) cache.asMap().computeIfAbsent(key, mappingFunction::apply);
    }

    @SuppressWarnings("unchecked")
    public <T> T computeIfPresent(String key, BiFunction<String, Object, T> mappingFunction) {
        Log.debugf(":: UPDATE %s/%s ::: ", name, key);
        return (T) cache.asMap().computeIfPresent(key, mappingFunction);
    }

    public void invalidate(String key) {
        Log.debugf(":: INVALIDATE %s/%s ::: ", name, key);
        cache.invalidate(key);
    }

    public void invalidateAll() {
        Log.debugf(":: INVALIDATE ALL %s ::: ", name);
        cache.invalidateAll();
    }
}
