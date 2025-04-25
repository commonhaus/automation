package org.commonhaus.automation.hk.github;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.commonhaus.automation.ContextService;
import org.commonhaus.automation.config.BotConfig;
import org.commonhaus.automation.github.discovery.BootstrapDiscoveryEvent;
import org.commonhaus.automation.hk.AdminDataCache;
import org.commonhaus.automation.hk.data.CommonhausUser;
import org.commonhaus.automation.hk.github.DatastoreEvent.QueryEvent;
import org.commonhaus.automation.hk.github.DatastoreEvent.UpdateEvent;
import org.commonhaus.automation.hk.member.MemberInfo;
import org.commonhaus.automation.queue.PeriodicUpdateQueue;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHContentBuilder;
import org.kohsuke.github.GHContentUpdateResponse;
import org.kohsuke.github.GHRepository;

import com.fasterxml.jackson.core.type.TypeReference;

import io.quarkus.logging.Log;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;

@ApplicationScoped
public class CommonhausDatastore {
    static final String ME = "🗃️";
    static final TypeReference<Map<String, CommonhausUser>> USER_JOURNAL_TYPE = new TypeReference<>() {
    };

    @Inject
    AppContextService ctx;

    @Inject
    BotConfig botConfig;

    @Inject
    PeriodicUpdateQueue updateQueue;

    private final Map<String, CommonhausUser> pendingJournal = new ConcurrentHashMap<>();
    private Path journalFile;
    private boolean journalInitialized = false;

    protected void startup(@Observes StartupEvent ev) {
        if (LaunchMode.current() == LaunchMode.TEST) {
            return;
        }
        initializeJournal();
    }

    protected void shutdown(@Observes ShutdownEvent ev) {
        if (!journalInitialized || LaunchMode.current() == LaunchMode.TEST) {
            return;
        }
        persistJournal();
    }

    public void onBootstrapDiscoveryEvent(@Observes BootstrapDiscoveryEvent ev) {
        if (!journalInitialized || LaunchMode.current() == LaunchMode.TEST) {
            return;
        }
        processJournalEntries();
    }

    /**
     * Get Commonhaus user data using login and ID from the session.
     * The cached record will not be refreshed, and the record will not be created if missing.
     *
     * @see #getCommonhausUser(MemberInfo, boolean, boolean)
     */
    public CommonhausUser getCommonhausUser(MemberInfo session) {
        return getCommonhausUser(session, false, false);
    }

    /**
     * Get or create Commonhaus user data using login and ID from the session.
     *
     * @see #getCommonhausUser(String, long, boolean, boolean)
     */
    public CommonhausUser getCommonhausUser(MemberInfo session, boolean resetCache, boolean create) {
        return getCommonhausUser(session.login(), session.id(), resetCache, create);
    }

    /**
     * Retrieve Commonhaus user data from the repository using the GitHub
     * bot's login and id (dqc)
     *
     * @param login User login
     * @param id User ID
     * @param resetCache True if the cached record should be discarded (re-fetch)
     * @param create True if the record should be created if it does not exist
     * @return fetched commonhaus user
     * @throws org.commonhaus.automation.PackagedException if the query context is not available or an
     *         irrecoverable error occurs (propagate to REST client)
     */
    public CommonhausUser getCommonhausUser(String login, long id, boolean resetCache, boolean create) {
        final QueryEvent query = new QueryEvent(login, id, resetCache, create);
        final String userKey = getKey(login, id);

        DatastoreQueryContext dqc = ensureQueryContext().withLogId(userKey);
        CommonhausUser result = readCommonhausUser(dqc, userKey, query);

        // If there are errors at this point, they're actual errors, not just NotFound
        if (dqc.hasErrors()) {
            throw dqc.bundleExceptions(); // Propagate to REST client
        }
        return result;
    }

    /**
     * Get user data: will return null on IOException (including not found)
     * Note: Exceptions are collected in the query context and are not thrown.
     *
     * @param dqc DatastoreQueryContext with repository context; collects exceptions and errors
     * @return CommonhausUser or null
     */
    private CommonhausUser readCommonhausUser(DatastoreQueryContext dqc, String userKey, QueryEvent event) {
        // Get or create a _cache entry_ for this user
        DatastoreCacheEntry entry = AdminDataCache.COMMONHAUS_DATA
                .computeIfAbsent(userKey, k -> new DatastoreCacheEntry(userKey));

        if (entry.hasUserData() && !event.refresh()) {
            // User data is already present
            return entry.getUserData();
        }

        CommonhausUser result = null;

        // Let QueryContext handle the errors from the fetch
        String userDataPath = dataPath(event.id());
        GHContent content = dqc.readSourceFile(dqc.getRepository(), userDataPath);
        if (content != null) {
            // if this throws, it will be captured in the query context
            result = dqc.readYamlContent(content, CommonhausUser.class);
            result.sha(content.getSha());
        }
        if (result == null && event.create()) {
            // Create a new user if requested and not found
            result = CommonhausUser.create(event.login(), event.id());
        }
        if (result != null) {
            entry.refreshUserData(result);
        }
        return result;
    }

    /**
     * REST API driven update of Commonhaus user data.
     *
     * @param updateEvent UpdateEvent with user data
     * @return valid user data
     * @throws org.commonhaus.automation.PackagedException if the query context is not available or an
     *         irrecoverable error occurs (propagate to REST client)
     */
    public CommonhausUser setCommonhausUser(UpdateEvent updateEvent) {
        if (updateEvent == null || updateEvent.user() == null) {
            throw new IllegalArgumentException("Update event with user is required");
        }
        final String userKey = getKey(updateEvent);

        DatastoreQueryContext dqc = ensureQueryContext().withLogId(userKey); // throws

        // Get or create a _cache entry_ for this user
        DatastoreCacheEntry entry = AdminDataCache.COMMONHAUS_DATA
                .computeIfAbsent(userKey, k -> new DatastoreCacheEntry(userKey));

        if (!entry.hasUserData()) {
            // we're being asked to store an update, create a user record if it is missing
            readCommonhausUser(dqc, userKey, new QueryEvent(updateEvent));
            if (dqc.hasErrors()) {
                throw dqc.bundleExceptions();
            }
            if (!entry.hasUserData()) {
                throw new IllegalStateException("Failed to load or create user: " + updateEvent.login());
            }
        }
        // Update the working/cached user data
        CommonhausUser result = entry.applyUpdate(ctx, updateEvent);

        // Offload persistence to the update queue
        updateQueue.queueReconciliation(userKey, () -> persistUserToGitHub(userKey, 0));

        // Respond with the updated user data
        return result;
    }

    public void clearCachedUser(CommonhausUser user) {
        String userKey = getKey(user.login(), user.id());
        AdminDataCache.COMMONHAUS_DATA.invalidate(userKey);
    }

    /**
     * Reconcile action driven from the single-threaded update queue.
     * This will lag behind user-driven events and can batch updates.
     * This is a self-contained/isolated task: exceptions should be handled.
     *
     * @param userKey key for the user
     * @param retryCount Number of retries attempted
     */
    private void persistUserToGitHub(String userKey, int retryCount) {
        // Retrieve the user data from the cache (should be present; prevent/defer expiry)
        DatastoreCacheEntry entry = AdminDataCache.COMMONHAUS_DATA.get(userKey);
        DatastoreQueryContext dqc = ensureQueryContext().withLogId(entry.userKey());

        CommonhausUser user = entry.beginUpdate();
        if (dqc.isDryRun() || user == null) {
            // nothing to do.
            return;
        }

        String content = dqc.writeYamlValue(user);
        if (dqc.hasErrors()) {
            // Can't proceed without content
            dqc.logAndSendContextErrors("Failed to flatten user data for persistence");
            return;
        }
        pendingJournal.put(userKey, user);

        GHRepository repo = dqc.getRepository();
        GHContentBuilder update = repo.createContent()
                .path(dataPath(user.id()))
                .message("🤖 [%s] %s".formatted(user.id(), entry.commitMessage()))
                .content(content);

        // include previous sha in the update if available
        if (user.sha() != null) {
            update.sha(user.sha());
        }

        // Commit/Push the change to GitHub
        GHContentUpdateResponse response = dqc.execGitHubSync((gh3, dryRun3) -> update.commit());
        // Handle any errors that occurred during the GitHub operation
        if (dqc.hasErrors()) {
            handlePersistenceError(dqc, entry, user, retryCount);
            return;
        }

        GHContent responseContent = response.getContent();
        final CommonhausUser updated = dqc.readYamlContent(responseContent, CommonhausUser.class);
        if (updated != null) {
            updated.sha(responseContent.getSha()); // update sha in the user data
            entry.finishUpdate(updated);
            pendingJournal.remove(userKey); // remove from journal
        } else {
            handlePersistenceError(dqc, entry, user, retryCount);
        }
    }

    /**
     * Centralized error handling for persistence operations
     * Do not throw exceptions; log and schedule retries as needed.
     *
     * @param dqc DatastoreQueryContext with repository context; collects exceptions and errors
     */
    private void handlePersistenceError(DatastoreQueryContext dqc, DatastoreCacheEntry entry,
            CommonhausUser user, int retryCount) {

        // Check for conflicts first
        final String userKey = entry.userKey();
        if (dqc.checkRemoveConflict()) {
            Log.debugf("[%s|%s] Conflict updating Commonhaus user data", dqc.getLogId(), user.login());

            // Fetch the latest from GitHub
            CommonhausUser latestUser = readCommonhausUser(dqc, userKey,
                    new QueryEvent(user.login(), user.id(), true, false));
            if (latestUser != null) {
                // Merge the changes
                entry.handleConflict(ctx, latestUser);

                // Queue a fresh start with updated/rebased content
                updateQueue.queueReconciliation(userKey, () -> persistUserToGitHub(userKey, 0));
                return;
            }
            // Fall through to retry logic if we couldn't fetch latest
        }
        // If it is a retriable network error, schedule a retry
        // dqc errors are logged when they occur
        if (dqc.hasRetriableNetworkError()) {
            Log.infof("[%s|%s] Network error during persistence, scheduling retry #%d",
                    dqc.getLogId(), user.login(), retryCount + 1);

            updateQueue.scheduleReconciliationRetry(userKey,
                    count -> persistUserToGitHub(userKey, count), retryCount);
        } else {
            dqc.logAndSendContextErrors(
                    "Non-retriable error during GitHub persistence. Data may be lost (retry #%d)".formatted(retryCount + 1));
        }
    }

    private DatastoreQueryContext ensureQueryContext() {
        DatastoreQueryContext dqc = ctx.getDatastoreContext();
        if (dqc == null || dqc.getRepository() == null) {
            var e = new IllegalStateException("Bad query context for the datastore");
            ctx.logAndSendEmail("ensureQueryContext",
                    "Unable to get datastore query context",
                    e);
            throw e;
        }
        return dqc;
    }

    /**
     * Create a deep copy of the user data to avoid concurrent modifications.
     * Static/stateless for access from DatastoreCacheEntry.
     * Should be a rare event.
     */
    static CommonhausUser deepCopy(CommonhausUser user) {
        if (user == null) {
            return null;
        }
        CommonhausUser copy = new CommonhausUser.Builder().copy(user).build();
        copy.sha(user.sha());
        return copy;
    }

    void initializeJournal() {
        String directory = botConfig.queue().stateDirectory().orElse(null);
        if (directory == null) {
            Log.debugf("%s journal / init: No state directory configured", ME);
            return;
        }
        Path journalDir = Path.of(directory);
        journalFile = journalDir.resolve("commonhaus-journal.yaml");
        try {
            // Ensure parent directory exists
            Files.createDirectories(journalDir);
            if (Files.exists(journalFile)) {
                String yaml = Files.readString(journalFile);
                if (yaml.isBlank()) {
                    Log.debugf("%s journal / init: Journal is empty, no pending updates (%s)", ME, journalFile);
                } else {
                    Map<String, CommonhausUser> journaledUsers = ctx.parseYamlContent(yaml, USER_JOURNAL_TYPE);
                    pendingJournal.putAll(journaledUsers);
                }
            }
            journalInitialized = true;
        } catch (IOException e) {
            ctx.logAndSendEmail(ME, "Datastore journal can not be read: %s".formatted(journalFile), e);
        }
    }

    void processJournalEntries() {
        if (pendingJournal.isEmpty()) {
            Log.debugf("%s journal / process: Journal is empty, no pending updates", ME);
            return;
        }
        for (var entry : pendingJournal.entrySet()) {
            String userKey = entry.getKey();
            CommonhausUser pendingUser = entry.getValue();

            // Prime the cache with journaled version before queueing update
            DatastoreCacheEntry cacheEntry = AdminDataCache.COMMONHAUS_DATA
                    .computeIfAbsent(userKey, k -> new DatastoreCacheEntry(userKey));
            cacheEntry.refreshUserData(pendingUser);

            updateQueue.queue(entry.getKey(), () -> {
                setCommonhausUser(new UpdateEvent(pendingUser,
                        (c, u) -> {
                            u.merge(pendingUser);
                        },
                        "Apply pending updates",
                        false,
                        true));
            });
        }
    }

    void persistJournal() {
        Log.debugf("%s journal / persist: %s pending updates", ME, pendingJournal.size());
        try {
            // Write the current state of the journal (including if empty)
            String yaml = ContextService.yamlMapper.writeValueAsString(pendingJournal);
            Files.writeString(journalFile, yaml);
        } catch (IOException e) {
            Log.errorf("%s Failed to save journal on shutdown: %s", ME, e);
        }
    }

    public static String dataPath(long id) {
        return "data/users/" + id + ".yaml";
    }

    public static String getKey(DatastoreEvent event) {
        return getKey(event.login(), event.id());
    }

    public static String getKey(String login, long id) {
        return login + ":" + id;
    }
}
