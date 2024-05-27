package org.commonhaus.automation.admin.github;

import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import org.commonhaus.automation.github.context.QueryCache;

import com.cronutils.Function;
import com.github.benmanes.caffeine.cache.Caffeine;

public enum AdminDataCache {
    MEMBER_SESSION(b -> b.expireAfterAccess(1, TimeUnit.HOURS)),

    COMMONHAUS_DATA(b -> b.expireAfterAccess(1, TimeUnit.HOURS)),

    USER_CONNECTION(b -> b.expireAfterAccess(30, TimeUnit.MINUTES)),

    KNOWN_USER(b -> b.expireAfterWrite(2, TimeUnit.DAYS));

    private QueryCache cache = null;

    AdminDataCache(Consumer<Caffeine<Object, Object>> config) {
        this.cache = QueryCache.create(name(), config);
    }

    public <T> T computeIfAbsent(String key, Function<String, T> mappingFunction) {
        return (T) cache.computeIfAbsent(key, mappingFunction);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) cache.get(key);
    }

    <T> void put(String key, T value) {
        cache.put(key, value);
    }

    public <T> T compute(String key, BiFunction<String, Object, T> mappingFunction) {
        return cache.compute(key, mappingFunction);
    }

    public <T> T putIfAbsent(String key, T value) {
        return cache.putIfAbsent(key, value);
    }

    public void invalidateAll() {
        cache.invalidateAll();
    }

    public void invalidate(String login) {
        cache.invalidate(login);
    }
}