package org.commonhaus.automation.config;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class RouteSupplier {
    private static final Map<String, Supplier<String>> suppliers = new HashMap<>();

    public static void registerSupplier(String key, Supplier<String> supplier) {
        suppliers.put(key, supplier);
    }

    public static Map<String, String> attributes() {
        return suppliers.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get()));
    }
}
