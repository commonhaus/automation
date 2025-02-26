package org.commonhaus.automation.github.watchers;

import org.commonhaus.automation.github.context.ContextHelper;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@GitHubAppTest
public class FileWatcherTest extends ContextHelper {

    // @Test
    // void testFileWatcherNotification() throws IOException {
    //     // Set up test repository and installation
    //     MockInstallation mockInstall = setupCommonMocks(defaultValues);

    //     // Create a callback counter
    //     AtomicInteger callbackCounter = new AtomicInteger(0);
    //     AtomicReference<FileUpdateType> updateTypeRef = new AtomicReference<>();

    //     // Register file watcher
    //     fileWatcher.watchFile("testGroup", mockInstall.installationId(),
    //             mockInstall.repository().getFullName(), "test-path.yml",
    //             update -> {
    //                 callbackCounter.incrementAndGet();
    //                 updateTypeRef.set(update.updateType());
    //             });

    //     // Create a file event
    //     FilePushEvent pushEvent = new FilePushEvent(
    //             createMockPushEvent("test-path.yml", FileUpdateType.ADDED),
    //             mockInstall.installationId(),
    //             mockInstall.repository(),
    //             mockInstall.github());

    //     // Trigger the event
    //     fileWatcher.handleEvent(pushEvent);

    //     // Wait and verify
    //     await().atMost(5, SECONDS).until(() -> callbackCounter.get() > 0);
    //     assertThat(callbackCounter.get()).isEqualTo(1);
    //     assertThat(updateTypeRef.get()).isEqualTo(FileUpdateType.ADDED);
    // }
}
