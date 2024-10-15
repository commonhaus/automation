package org.commonhaus.automation.admin.github;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.commonhaus.automation.admin.AdminDataCache;
import org.commonhaus.automation.admin.api.CommonhausUser;
import org.commonhaus.automation.admin.api.MemberSession;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHContentBuilder;
import org.kohsuke.github.GHContentUpdateResponse;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.HttpException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.logging.Log;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.mutiny.core.eventbus.Message;

@ApplicationScoped
public class CommonhausDatastore {
    public static final String READ = "commonhaus-read";
    public static final String CREATE = "commonhaus-create";
    public static final String WRITE = "commonhaus-write";

    @Inject
    AppContextService ctx;

    @Inject
    ObjectMapper mapper;

    Executor executor = Infrastructure.getDefaultWorkerPool();

    interface DatastoreEvent {
        long id();

        String login();

        boolean create();
    }

    public record QueryEvent(String login, long id, boolean refresh,
            boolean create) implements DatastoreEvent {
    }

    /**
     * Update Commonhaus user data
     *
     * @param user Commonhaus user object
     * @param updateUser Function to apply changes to the user. This function will not have access to the MemberSession.
     * @param message Commit message
     * @param history Whether to add the message to the user's history
     * @param retry Whether to retry the update if there is a conflict
     */
    public record UpdateEvent(
            CommonhausUser user,
            BiConsumer<AppContextService, CommonhausUser> updateUser,
            String message,
            boolean history,
            boolean retry) implements DatastoreEvent {

        @Override
        public long id() {
            return user.id();
        }

        @Override
        public String login() {
            return user.login();
        }

        @Override
        public boolean create() {
            return true;
        }

        public void applyChanges(AppContextService ctx, CommonhausUser user) {
            updateUser.accept(ctx, user);
        }

        static UpdateEvent retryEvent(UpdateEvent initial, CommonhausUser revisedUser) {
            return new UpdateEvent(revisedUser, initial.updateUser(), initial.message(), initial.history(), false);
        }
    }

    public CommonhausUser getCommonhausUser(MemberSession session) {
        return getCommonhausUser(session, false, false);
    }

    /**
     * GET Commonhaus user data
     *
     * @return A Commonhaus user object (never null)
     * @throws RuntimeException if GitHub or other API query fails
     */
    public CommonhausUser getCommonhausUser(MemberSession session, boolean resetCache, boolean create) {
        return getCommonhausUser(session.login(), session.id(), resetCache, create);
    }

    /**
     * Async: ensure a Commonhaus User exists.
     */
    public void asyncEnsureCommonhausUser(GHUser user) {
        QueryEvent query = new QueryEvent(user.getLogin(), user.getId(), false, true);
        ctx.getBus().send(CommonhausDatastore.CREATE, query); // fire and forget
    }

    /**
     * GET Commonhaus user data
     *
     * @return A Commonhaus user object (never null)
     * @throws RuntimeException if GitHub or other API query fails
     */
    public CommonhausUser getCommonhausUser(String login, long id, boolean resetCache, boolean create) {
        QueryEvent query = new QueryEvent(login, id, resetCache, create);
        Message<CommonhausUser> response = ctx.getBus().requestAndAwait(CommonhausDatastore.READ, query);
        return response.body();
    }

    /**
     * Update Commonhaus user data
     *
     * @param updateEvent Update message containing the user and commit message
     *
     * @throws RuntimeException if GitHub or other API query fails
     */
    public CommonhausUser setCommonhausUser(UpdateEvent updateEvent) {
        Message<CommonhausUser> response = ctx.getBus().requestAndAwait(CommonhausDatastore.WRITE, updateEvent);
        return response.body();
    }

    @Blocking
    @ConsumeEvent(CREATE)
    public void createCommonhausUser(QueryEvent query) {
        Message<CommonhausUser> response = ctx.getBus().requestAndAwait(CommonhausDatastore.READ, query);
        CommonhausUser cfUser = response.body();
        if (cfUser != null && cfUser.isNew()) {
            ctx.getBus().send(CommonhausDatastore.WRITE, new UpdateEvent(cfUser,
                    (ctx, u) -> {
                    },
                    "Created by bot", true, false));
        }
    }

    /**
     * Retrieve Commonhaus user data from the repository using
     * the GitHub bot's login and id.
     *
     * @param event Query message containing the login and id
     * @return A Commonhaus user object (never null)
     * @throws RuntimeException if GitHub or other API query fails
     */
    @ConsumeEvent(READ)
    public Uni<CommonhausUser> fetchCommonhausUser(QueryEvent event) {
        final String key = getKey(event);

        DatastoreQueryContext dqc = ctx.getDatastoreContext();
        if (dqc == null) {
            return Uni.createFrom().failure(new IllegalStateException("No admin query context"));
        }

        CommonhausUser result = event.refresh()
                ? null
                : deepCopy(AdminDataCache.COMMONHAUS_DATA.get(key));

        if (result == null) {
            GHRepository repo = dqc.getRepository();
            result = readCommonhausUser(dqc, repo, event, key);
        }
        if (result == null && dqc.clearNotFound() && event.create()) {
            // create a new user if not found and no errors
            result = CommonhausUser.create(event.login(), event.id());
        }

        // any other kind of error (including parse errors) will be logged and returned
        if (dqc.hasErrors()) {
            Throwable e = dqc.bundleExceptions();
            dqc.clearErrors();
            Log.errorf(e, "[%s|%s] Unable to fetch user data for %s", dqc.getLogId(), event.login(), e);
            return Uni.createFrom().failure(e);
        } else if (result == null && event.create()) {
            Exception e = new IllegalStateException("No result for user after fetch with create");
            dqc.logAndSendEmail("Failed to update Commonhaus user", e);
            return Uni.createFrom().failure(e);
        }

        Log.debugf("[%s|%s] Fetched Commonhaus user data: %s", dqc.getLogId(), event.login(), result);
        final CommonhausUser r = result;
        return Uni.createFrom().item(() -> r).emitOn(executor);
    }

    /**
     * Update Commonhaus user data in the repository using
     * the GitHub bot's login and id.
     *
     * @param event Update message containing the user and commit message
     * @return Updated Commonhaus user object (never null)
     * @throws RuntimeException if GitHub or other API query fails
     */
    @Blocking
    @ConsumeEvent(WRITE)
    public Uni<CommonhausUser> pushCommonhausUser(UpdateEvent event) {
        final CommonhausUser user = event.user();

        CommonhausUser result = null;
        DatastoreQueryContext dqc = ctx.getDatastoreContext();
        if (dqc == null) {
            Exception e = new IllegalStateException("No query context");
            ctx.logAndSendEmail("pushCommonhausUser", "Unable to get datastore query context", e, null);
            return Uni.createFrom().failure(e);
        } else if (!dqc.hasErrors()) {
            result = updateCommonhausUser(dqc, event);
        }

        if (dqc.hasErrors()) {
            Throwable e = dqc.bundleExceptions();
            dqc.clearErrors();
            dqc.logAndSendEmail("Failed to update Commonhaus user", e);
            return Uni.createFrom().failure(e);
        }

        Log.debugf("[%s|%s] Updated Commonhaus user data: %s", dqc.getLogId(), user.login(), result);
        final CommonhausUser u = result;
        return Uni.createFrom().item(() -> u).emitOn(executor);
    }

    private CommonhausUser updateCommonhausUser(DatastoreQueryContext dqc, UpdateEvent event) {
        GHRepository repo = dqc.getRepository();
        CommonhausUser user = deepCopy(event.user()); // leave original alone
        String key = getKey(user);

        // Callback: Apply changes to the user
        event.applyChanges(ctx, user);
        if (event.history()) {
            user.addHistory(event.message());
        }

        if (dqc.isDryRun()) {
            return user;
        }

        String content = writeUser(dqc, user);
        if (content == null) {
            // If it can't be serialized, bail and return the original
            return event.user();
        }

        GHContentBuilder update = repo.createContent()
                .path(dataPath(user.id()))
                .message("🤖 [%s] %s".formatted(user.id(), event.message()))
                .content(content);

        if (user.sha() != null) {
            update.sha(user.sha());
        }

        GHContentUpdateResponse response = dqc.execGitHubSync((gh3, dryRun3) -> update.commit());
        HttpException ex = dqc.getConflict();
        if (ex != null) {
            dqc.clearConflict();
            Log.debugf("[%s|%s] Conflict updating Commonhaus user data", dqc.getLogId(), user.login());

            // we're here after a save conflict; re-read the data
            user = readCommonhausUser(dqc, repo, event, key);
            if (user != null) {
                if (event.retry()) {
                    // retry the update
                    return updateCommonhausUser(dqc, UpdateEvent.retryEvent(event, user));
                } else {
                    // allow caller to handle unresolved conflict
                    user.setConflict(true);
                }
            }
        } else if (!dqc.hasErrors()) {
            GHContent responseContent = response.getContent();
            user = parseUser(dqc, responseContent);
            if (user != null) {
                AdminDataCache.COMMONHAUS_DATA.put(key, deepCopy(user));
            }
        }
        // Caller should be check for query context errors
        return user;
    }

    /** Get user data: will return null on IOException (including not found) */
    private CommonhausUser readCommonhausUser(DatastoreQueryContext dqc, GHRepository repo,
            DatastoreEvent event, String key) {

        CommonhausUser response = dqc.execGitHubSync((gh, dryRun) -> {
            GHContent content = repo.getFileContent(dataPath(event.id()));
            return content == null
                    ? null
                    : CommonhausUser.parseFile(dqc, content);
        });

        if (dqc.hasErrors() || response == null) {
            Log.debugf("[%s|%s] Commonhaus user data not found or could not be parsed",
                    dqc.getLogId(), event.login());
            if (dqc.clearNotFound() || event.create()) {
                // create a new user
                return CommonhausUser.create(event.login(), event.id());
            }
            return null;
        }

        AdminDataCache.COMMONHAUS_DATA.put(key, deepCopy(response));
        return response;
    }

    private CommonhausUser deepCopy(CommonhausUser user) {
        if (user == null) {
            return null;
        }
        // create a disconnected copy of the essential data.
        try {
            String json = mapper.writeValueAsString(user);
            CommonhausUser copy = mapper.readValue(json, CommonhausUser.class);
            copy.sha(user.sha());
        } catch (JsonProcessingException e) {
            ctx.logAndSendEmail("CommonhausDatastore.deepCopy", "Unable to copy Commonbaus user", e, null);
        }
        return user;
    }

    private CommonhausUser parseUser(DatastoreQueryContext dqc, GHContent responseContent) {
        try {
            return CommonhausUser.parseFile(dqc, responseContent);
        } catch (IOException e) {
            dqc.addException(e);
            return null;
        }
    }

    private String writeUser(DatastoreQueryContext dqc, CommonhausUser input) {
        try {
            return dqc.writeYamlValue(input);
        } catch (IOException e) {
            dqc.addException(e);
            return null;
        }
    }

    private String dataPath(long id) {
        return "data/users/" + id + ".yaml";
    }

    public static String getKey(QueryEvent event) {
        return event.login() + ":" + event.id();
    }

    public static String getKey(CommonhausUser user) {
        return user.login() + ":" + user.id();
    }
}
