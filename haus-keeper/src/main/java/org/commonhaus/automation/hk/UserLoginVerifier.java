package org.commonhaus.automation.hk;

import static org.commonhaus.automation.ContextService.yamlMapper;

import java.io.BufferedInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.commonhaus.automation.config.RouteSupplier;
import org.commonhaus.automation.github.discovery.RepositoryDiscoveryEvent.RdePriority;
import org.commonhaus.automation.hk.data.CommonhausUser;
import org.commonhaus.automation.hk.github.AppContextService;
import org.commonhaus.automation.hk.github.CommonhausDatastore;
import org.commonhaus.automation.hk.github.DatastoreQueryContext;
import org.commonhaus.automation.queue.PeriodicUpdateQueue;
import org.commonhaus.automation.queue.ScheduledService;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHUser;

import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;

@ApplicationScoped
public class UserLoginVerifier extends ScheduledService {
    private static final String ME = "👍-login";

    @Inject
    ActiveHausKeeperConfig hkConfig;

    @Inject
    AppContextService ctx;

    @Inject
    CommonhausDatastore datastore;

    @Inject
    PeriodicUpdateQueue updateQueue;

    // Fire this event when a login change is detected
    @Inject
    Event<LoginChangeEvent> loginChangeEvent;

    void startup(@Observes @Priority(value = RdePriority.APP_DISCOVERY) StartupEvent startup) {
        RouteSupplier.registerSupplier("User logins verified", () -> lastRun);
    }

    @Scheduled(cron = "${automation.hausKeeper.cron.verifyLogins:0 15 3 * * ?}")
    public void scheduledLoginVerification() {
        try {
            Log.infof("[%s] ⏰ Scheduled: begin login verification", ME);
            verifyAllUserLogins(false);
        } catch (Throwable t) {
            ctx.logAndSendEmail(ME, "⏰ 👍 Error running scheduled login verification", t);
        }
    }

    /**
     * Allow manual trigger by admin endpoint
     */
    public void verifyAllUserLogins(boolean userTriggered) {
        if (!userTriggered && !taskState.shouldRun(ME, Duration.ofHours(6))) {
            Log.infof("[%s]: skip scheduled login verification (last run: %s)", ME, lastRun);
            return;
        }
        recordRun();

        // Get all user IDs from datastore
        DatastoreQueryContext dqc = ctx.getDatastoreContext();
        GHRepository repo = dqc.getRepository();
        if (repo == null) {
            Log.error("Failed to get repository for user login verification");
            return;
        }

        try {
            // Create a temporary directory for the files
            Path tempDir = Files.createTempDirectory(ME);

            // Read files from the input stream
            try (ZipInputStream zs = repo.readZip((inputstream) -> new ZipInputStream(new BufferedInputStream(inputstream)),
                    null)) {
                ZipEntry entry;
                while ((entry = zs.getNextEntry()) != null) {
                    String entryName = entry.getName();
                    try {
                        if (entryName.startsWith("data/users/") && entryName.endsWith(".yaml")) {
                            String fileName = entryName.substring(entryName.lastIndexOf('/') + 1);
                            // Create a file path for this entry
                            Path filePath = tempDir.resolve(fileName);
                            // Write the entry to a file
                            Files.copy(zs, filePath, StandardCopyOption.REPLACE_EXISTING);

                            // queue verification; last file out will clean up the temp dir
                            updateQueue.queueReconciliation(fileName,
                                    () -> verifyUser(tempDir, filePath, fileName));
                        }
                    } finally {
                        zs.closeEntry();
                    }
                }
            }
        } catch (Exception e) {
            Log.errorf(e, "[%s] Failed to create temporary directory for user login verification: %s", ME, e);
            // FIXME: cleanup temp dir
            return;
        }
    }

    private void verifyUser(Path tempDir, Path filePath, String fileName) {
        DatastoreQueryContext dqc = ctx.getDatastoreContext();
        try {
            // Read the file and parse the user data
            CommonhausUser user = yamlMapper.readValue(filePath.toFile(), CommonhausUser.class);
            if (user == null) {
                Log.errorf("[%s] Failed to parse user file: %s", ME, fileName);
                return;
            }

            // There is no method currently on the API to look a user up by their id.
            // All we can do is flag if a login isn't found, or if the id doesn't match.
            GHUser ghUser = dqc.getUser(user.login());
            if (dqc.hasErrors()) {
                dqc.logAndSendContextErrors("Unable to verify user login");
            } else if (ghUser == null || ghUser.getId() != user.id()) {
                // Send email to administrator that login has changed
                ctx.sendEmail(ME, "GitHub user not found or has mismatched id", """
                        User %s not found or has mismatched id

                        Expected user id: %s
                        GitHub user %s

                        %s
                        """.formatted(
                        user.login(),
                        user.id(),
                        ghUser == null ? "not found" : "id is %s".formatted(ghUser.getId()),
                        dqc.writeYamlValue(user)),
                        dqc.getErrorAddresses(hkConfig.getAddresses()));

                // Send event to notifiy project owners that login has changed
                loginChangeEvent.fire(new LoginChangeEvent(user.login(), Optional.empty(), user.projects()));

                return;
            }
        } catch (Exception e) {
            dqc.addException(e);
            dqc.logAndSendContextErrors("Unable to verify user login");
        } finally {
            // Cleanup the temporary directory
            try {
                Files.deleteIfExists(filePath);
                if (Files.list(tempDir).count() == 0) {
                    Files.deleteIfExists(tempDir);
                }
            } catch (Exception e) {
                Log.errorf(e, "[%s] Failed to delete temporary file %s: %s", ME, filePath, e);
            }
        }
    }

    @Override
    protected String me() {
        return ME;
    }

    public record LoginChangeEvent(String oldLogin, Optional<String> newLogin, List<String> projects) {
    }
}
