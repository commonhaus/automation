package org.commonhaus.automation;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.commonhaus.automation.github.AppContextService;

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
        AppContextService ctx;

        @Override
        public int run(String... args) {
            System.out.println("discoveryEnabled=" + ctx.isDiscoveryEnabled());
            System.out.println("dryRun=" + ctx.isDryRun());
            System.out.println("replyTo=" + ctx.replyTo().orElse("N/A"));

            // Reminder: stop can happen elsewhere with Quarkus.asyncExit()
            Quarkus.waitForExit();
            return 0;
        }

        void onStart(@Observes StartupEvent ev) {
            Log.infof("Bot started. dryRun=%s", ctx.isDryRun());
        }

        void onStop(@Observes ShutdownEvent ev) {
            Log.infof("Bot stopping. dryRun=%s", ctx.isDryRun());
        }
    }
}
