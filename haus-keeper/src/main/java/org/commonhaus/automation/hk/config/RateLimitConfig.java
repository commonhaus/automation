package org.commonhaus.automation.hk.config;

import java.time.Duration;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "automation.hausKeeper.rateLimit")
public interface RateLimitConfig {

    /** Shared budget for the 7 interceptor-based member endpoints. Default: 10 requests / 1 minute. */
    BudgetConfig memberEndpoints();

    /** Dedicated budget for /member/commonhaus/status. Default: 10 requests / 1 minute. */
    BudgetConfig statusEndpoint();

    interface BudgetConfig {
        @WithDefault("10")
        int limit();

        @WithDefault("1m")
        Duration window();
    }
}
