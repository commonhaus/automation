package org.commonhaus.automation.hk;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.commonhaus.automation.config.BotConfig;

import io.quarkus.logging.Log;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.runtime.annotations.QuarkusMain;

@QuarkusMain
public class Main {

    public static void main(String... args) {
        Quarkus.run(ApplicationRoot.class, args);
    }

    public static class ApplicationRoot implements QuarkusApplication {

        @Inject
        BotConfig botConfig;

        @Override
        public int run(String... args) {
            java.security.Security.setProperty("networkaddress.cache.negative.ttl", "0");

            System.out.println("discoveryEnabled=" + botConfig.isDiscoveryEnabled());
            System.out.println("dryRun=" + botConfig.isDryRun());
            System.out.println("replyTo=" + botConfig.replyTo().orElse("N/A"));

            // Reminder: stop can happen elsewhere with Quarkus.asyncExit()
            Quarkus.waitForExit();
            return 0;
        }

        void onStart(@Observes StartupEvent ev) {
            Log.infof("Bot started. dryRun=%s", botConfig.isDryRun());
        }

        void onStop(@Observes ShutdownEvent ev) {
            Log.infof("Bot stopping. dryRun=%s", botConfig.isDryRun());
        }
    }
}
