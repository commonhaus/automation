package org.commonhaus.automation.github.rules;

import org.junit.jupiter.api.Assertions;

public class RuleHelper {
    public static void assertCacheValue(String paths) {
        Assertions.assertNotNull(MatchPaths.GLOB.get(paths),
                paths + " GLOB cache should exist");
    }
}
