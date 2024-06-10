package org.commonhaus.automation.admin.github;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.Executor;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.commonhaus.automation.admin.AdminDataCache;
import org.commonhaus.automation.admin.api.CommonhausUser;
import org.commonhaus.automation.admin.api.MemberSession;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHContentBuilder;
import org.kohsuke.github.GHContentUpdateResponse;
import org.kohsuke.github.GHRepository;
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
    public static final String WRITE = "commonhaus-write";

    @Inject
    AppContextService ctx;

    @Inject
    ObjectMapper mapper;

    Executor executor = Infrastructure.getDefaultWorkerPool();

    interface DatastoreEvent {
        long id();

        String login();

        Set<String> roles();

        boolean create();
    }

    public record QueryEvent(String login, long id, Set<String> roles, boolean refresh,
            boolean create) implements DatastoreEvent {
    }

    public record UpdateEvent(CommonhausUser user, String message, Set<String> roles,
            boolean history) implements DatastoreEvent {
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
        QueryEvent query = new QueryEvent(session.login(), session.id(), session.roles(), resetCache, create);
        Message<CommonhausUser> response = ctx.getBus().requestAndAwait(CommonhausDatastore.READ, query);
        Log.debugf("[getCommonhausUser|%s] Get Commonhaus user data: %s", session.id(), response.body());
        return response.body();
    }

    /**
     * Update Commonhaus user data
     *
     * @param user Commonhaus user object
     * @param message Commit message
     *
     * @throws RuntimeException if GitHub or other API query fails
     */
    public CommonhausUser setCommonhausUser(CommonhausUser user, Set<String> roles, String message, boolean history) {
        UpdateEvent update = new UpdateEvent(user, message, roles, history);
        Message<CommonhausUser> response = ctx.getBus().requestAndAwait(CommonhausDatastore.WRITE, update);
        Log.debugf("[setCommonhausUser|%s] Update Commonhaus user data: %s", user.id(), response.body());
        return response.body();
    }

    /**
     * Retrieve Commonhaus user data from the repository using
     * the GitHub bot's login and id.
     *
     * @param event Query message containing the login and id
     * @return A Commonhaus user object (never null)
     * @throws RuntimeException if GitHub or other API query fails
     */
    @ConsumeEvent(value = READ)
    public Uni<CommonhausUser> fetchCommonhausUser(QueryEvent event) {
        final String key = getKey(event);

        ScopedQueryContext qc = ctx.getDatastoreContext();
        if (qc == null) {
            return Uni.createFrom().failure(new IllegalStateException("No admin query context"));
        }

        CommonhausUser result = event.refresh()
                ? null
                : deepCopy(AdminDataCache.COMMONHAUS_DATA.get(key));

        if (result == null) {
            GHRepository repo = qc.getRepository();
            result = readCommonhausUser(qc, repo, event, key);
        }

        if (qc.clearNotFound() && event.create()) {
            // create a new user
            result = CommonhausUser.create(event.login(), event.id());
        }
        if (qc.hasErrors()) {
            Throwable e = qc.bundleExceptions();
            qc.clearErrors();
            Log.errorf(e, "[%s|%s] Unable to fetch user data for %s", qc.getLogId(), event.login(), e);
            return Uni.createFrom().failure(e);
        }

        Log.debugf("[%s|%s] Fetched Commonhaus user data: %s", qc.getLogId(), event.login(), result);
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
    @ConsumeEvent(value = WRITE)
    public Uni<CommonhausUser> pushCommonhausUser(UpdateEvent event) {
        final CommonhausUser user = event.user();
        final String key = getKey(user);

        CommonhausUser result = null;
        ScopedQueryContext qc = ctx.getDatastoreContext();
        if (qc == null) {
            Exception e = new IllegalStateException("No query context");
            ctx.logAndSendEmail("pushCommonhausUser", "Unable to get datastore query context", e, null);
            return Uni.createFrom().failure(e);
        } else if (!qc.hasErrors()) {
            GHRepository repo = qc.getRepository();
            result = updateCommonhausUser(qc, repo, user, event, key);
        }

        if (qc.hasErrors()) {
            Throwable e = qc.bundleExceptions();
            qc.clearErrors();
            ctx.logAndSendEmail(qc.getLogId(), "Failed to update Commonhaus user", e, null);
            return Uni.createFrom().failure(e);
        }

        Log.debugf("[%s|%s] Updated Commonhaus user data: %s", qc.getLogId(), user.id(), result);
        final CommonhausUser u = result;
        return Uni.createFrom().item(() -> u).emitOn(executor);
    }

    private CommonhausUser updateCommonhausUser(ScopedQueryContext qc, GHRepository repo,
            CommonhausUser input, UpdateEvent event, String key) {

        CommonhausUser result;

        if (event.history()) {
            input.addHistory(event.message());
        }

        String content = writeUser(qc, input);
        if (content == null) {
            // If it can't be serialized, bail.
            return input;
        }
        GHContentBuilder update = repo.createContent()
                .path(dataPath(input.id()))
                .message("🤖 [%s] %s".formatted(input.id(), event.message()))
                .content(content);

        if (input.sha() != null) {
            update.sha(input.sha());
        }

        GHContentUpdateResponse response = qc.execGitHubSync((gh3, dryRun3) -> {
            if (dryRun3) {
                return null;
            }
            return update.commit();
        });

        HttpException ex = qc.getConflict();
        if (ex != null) {
            qc.clearConflict();
            Log.debugf("[%s|%s] Conflict updating Commonhaus user data: %s",
                    qc.getLogId(), input.id(), ex.getResponseMessage());

            // we're here after a save conflict; re-read the data
            result = readCommonhausUser(qc, repo, event, key);
            if (result != null) {
                // recovered from conflict w/o further IOException
                // Otherwise, query context will still contain errors, see caller
                result.setConflict(true);
            }
        } else {
            GHContent responseContent = response.getContent();
            result = parseUser(qc, input, responseContent);
        }
        return result;
    }

    /** Get user data: will return null on IOException (including not found) */
    private CommonhausUser readCommonhausUser(ScopedQueryContext qc, GHRepository repo,
            DatastoreEvent event, String key) {

        CommonhausUser response = qc.execGitHubSync((gh, dryRun) -> {
            GHContent content = repo.getFileContent(dataPath(event.id()));
            return content == null
                    ? null
                    : CommonhausUser.parseFile(qc, content);
        });

        if (qc.hasErrors() || response == null) {
            Log.debugf("[%s|%s] Commonhaus user data not found or could not be parsed",
                    qc.getLogId(), event.login());
            if (qc.clearNotFound() || event.create()) {
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

    private CommonhausUser parseUser(ScopedQueryContext qc, CommonhausUser user, GHContent responseContent) {
        try {
            return CommonhausUser.parseFile(qc, responseContent);
        } catch (IOException e) {
            // unlikely, but safer to keep what we had
            // send an email to the admin because this shouldn't happen.
            ctx.logAndSendEmail("CommonhausDatastore.parseUser", "Unable to deserialize Commonhaus user", e, null);
            return user;
        }
    }

    private String writeUser(ScopedQueryContext qc, CommonhausUser input) {
        try {
            return qc.writeValue(input);
        } catch (IOException e) {
            // unlikely, but allow us to fail fast without exceptions everyplace
            // send an email to the admin because this shouldn't happen.
            ctx.logAndSendEmail("CommonhausDatastore.writeUser", "Unable to serialize Commonhaus user", e, null);
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

    public static String getKey(MemberSession session) {
        return session.login() + ":" + session.nodeId();
    }
}
