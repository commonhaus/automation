package org.commonhaus.automation.hk;

import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import org.commonhaus.automation.github.context.QueryCache;

import com.cronutils.Function;
import com.github.benmanes.caffeine.cache.Caffeine;

public enum AdminDataCache {
    /** Cached user application state. Update events may be replayed as part of user persistence */
    APPLICATION_STATE(b -> b.expireAfterAccess(3, TimeUnit.HOURS)),

    /** Retrievable GitHub connections that can be used to renew connection */
    MEMBER_SESSION(b -> b.expireAfterAccess(15, TimeUnit.MINUTES)),

    /** Cached user record state. Stores state for immedate update and deferred/batched persistence */
    COMMONHAUS_DATA(b -> b.expireAfterAccess(3, TimeUnit.HOURS)),

    /** Cache if a user is known or not to avoid recomputation of groups membership */
    KNOWN_USER(b -> b.expireAfterAccess(6, TimeUnit.HOURS)),

    /** Cache forward email aliases to reduce API calls */
    ALIASES(b -> b.expireAfterAccess(6, TimeUnit.HOURS)),

    ;

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

    public <T> void put(String key, T value) {
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
