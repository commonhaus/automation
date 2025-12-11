package org.commonhaus.automation;

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

    @SuppressWarnings({ "null", "unchecked", "unused" })
    public <T> T get(String key) {
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
    @SuppressWarnings("null")
    public <T> T put(String key, T value) {
        if (value == null) {
            invalidate(key);
        } else {
            Log.debugf(":: PUT %s/%s ::: ", name, key);
            cache.put(key, value);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public <T> T computeIfAbsent(String key, Function<String, T> mappingFunction) {
        return (T) cache.asMap().computeIfAbsent(key, k -> {
            Log.debugf(":: PUT_IF_ABSENT %s/%s ::: ", name, key);
            return mappingFunction.apply(k);
        });
    }

    @SuppressWarnings("unchecked")
    public <T> T computeIfPresent(String key, BiFunction<String, Object, T> mappingFunction) {
        return (T) cache.asMap().computeIfPresent(key, (k, v) -> {
            Log.debugf(":: PUT_IF_PRESENT %s/%s ::: ", name, key);
            return mappingFunction.apply(k, v);
        });
    }

    @SuppressWarnings("unchecked")
    public <T> T compute(String key, BiFunction<String, Object, T> mappingFunction) {
        return (T) cache.asMap().compute(key, (k, v) -> {
            Log.debugf(":: PUT %s/%s ::: ", name, key);
            return mappingFunction.apply(k, v);
        });
    }

    @SuppressWarnings("unchecked")
    public <T> T putIfAbsent(String key, T value) {
        return (T) cache.asMap().putIfAbsent(key, value);
    }

    @SuppressWarnings("null")
    public void invalidate(String key) {
        Log.debugf(":: INVALIDATE %s/%s ::: ", name, key);
        cache.invalidate(key);
    }

    public void invalidateAll() {
        Log.debugf(":: INVALIDATE ALL %s ::: ", name);
        cache.invalidateAll();
    }
}
