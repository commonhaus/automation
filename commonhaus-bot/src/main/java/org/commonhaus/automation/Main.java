package org.commonhaus.automation;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

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
        AppConfig botConfig;

        @Override
        public int run(String... args) {
            System.out.println("cronExpr=" + botConfig.cronExpr().orElse("N/A"));
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
