package org.commonhaus.automation;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;

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
        System.out.println("cwd=" + Path.of("").toAbsolutePath());

        try {
            String hostName = "api.github.com";

            // Resolving host name to IP address
            InetAddress address = InetAddress.getByName(hostName);
            System.out.println("IP address of " + hostName + ": " + address.getHostAddress());
        } catch (UnknownHostException e) {
            System.out.println("Could not resolve host: " + e.getMessage());
        }

        Quarkus.run(ApplicationRoot.class, args);
    }

    public static class ApplicationRoot implements QuarkusApplication {

        @Inject
        AppConfig botConfig;

        @Override
        public int run(String... args) {
            System.out.println("dryRun=" + botConfig.isDryRun());

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
