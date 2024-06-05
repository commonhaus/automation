package org.commonhaus.automation.admin.github;

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

    Executor executor = Infrastructure.getDefaultWorkerPool();

    interface DatastoreEvent {
        long id();

        String login();

        Set<String> roles();
    }

    public record QueryEvent(String login, long id, Set<String> roles, boolean refresh) implements DatastoreEvent {
    }

    public record UpdateEvent(CommonhausUser user, String message, Set<String> roles) implements DatastoreEvent {
        @Override
        public long id() {
            return user.id();
        }

        @Override
        public String login() {
            return user.login();
        }
    }

    public CommonhausUser getCommonhausUser(MemberSession session) {
        return getCommonhausUser(session, false);
    }

    /**
     * GET Commonhaus user data
     *
     * @return A Commonhaus user object (never null)
     * @throws RuntimeException if GitHub or other API query fails
     */
    public CommonhausUser getCommonhausUser(MemberSession session, boolean resetCache) {
        QueryEvent query = new QueryEvent(session.login(), session.id(), session.roles(), resetCache);
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
    public CommonhausUser setCommonhausUser(CommonhausUser user, Set<String> roles, String message) {
        UpdateEvent update = new UpdateEvent(user, message, roles);
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
        final String key = event.login() + ":" + event.id();

        CommonhausUser result = event.refresh() ? null : AdminDataCache.COMMONHAUS_DATA.get(key);
        ScopedQueryContext qc = ctx.getDatastoreContext();
        if (qc == null) {
            return Uni.createFrom().failure(new IllegalStateException("No admin query context"));
        } else if (result == null) {
            GHRepository repo = qc.getRepository();
            result = readCommonhausUser(qc, repo, event, key);

            if (qc.hasErrors()) {
                Throwable e = qc.bundleExceptions();
                Log.errorf("[%s|%s] Failed to fetch Commonhaus user data: %s", qc.getLogId(), event.id(), e);
                return Uni.createFrom().failure(e);
            }
        }

        Log.debugf("[%s|%s] Fetched Commonhaus user data: %s", qc.getLogId(), event.id(), result);
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
        final String key = user.login() + ":" + user.id();

        CommonhausUser result;
        ScopedQueryContext qc = ctx.getDatastoreContext();
        if (qc == null) {
            return Uni.createFrom().failure(new IllegalStateException("No admin query context"));
        } else {
            GHRepository repo = qc.getRepository();
            result = updateCommonhausUser(qc, repo, user, event, key);

            if (qc.hasErrors()) {
                Throwable e = qc.bundleExceptions();
                Log.errorf("[%s|%s] Failed to fetch Commonhaus user data: %s", qc.getLogId(), user.id(), e);
                return Uni.createFrom().failure(e);
            }
        }

        Log.debugf("[%s|%s] Updated Commonhaus user data: %s", qc.getLogId(), user.id(), result);
        final CommonhausUser u = result;
        return Uni.createFrom().item(() -> u).emitOn(executor);
    }

    private CommonhausUser updateCommonhausUser(ScopedQueryContext qc, GHRepository repo,
            CommonhausUser input, UpdateEvent event, String key) {

        return qc.execGitHubSync((gh, dryRun) -> {
            CommonhausUser result;

            String content = qc.writeValue(input);
            GHContentBuilder update = repo.createContent()
                    .path(dataPath(input.id()))
                    .message(event.message())
                    .content(content);
            if (input.sha() != null) {
                update.sha(input.sha());
            }
            GHContentUpdateResponse response = update.commit();

            if (qc.hasConflict()) {
                HttpException ex = qc.getConflict();
                qc.clearConflict();
                Log.debugf("[%s|%s] Conflict updating Commonhaus user data: %s",
                        qc.getLogId(), input.id(), ex.getResponseMessage());

                // we're here after a save conflict; re-read the data
                result = readCommonhausUser(qc, repo, event, key);
                result.setConflict(true);
            } else {
                GHContent responseContent = response.getContent();
                result = CommonhausUser.parseFile(qc, responseContent);
                AdminDataCache.COMMONHAUS_DATA.put(key, result);
            }

            return result;
        });
    }

    private CommonhausUser readCommonhausUser(ScopedQueryContext qc, GHRepository repo,
            DatastoreEvent event, String key) {

        CommonhausUser response = qc.execGitHubSync((gh, dryRun) -> {
            GHContent content = repo.getFileContent(dataPath(event.id()));
            if (content != null) {
                return CommonhausUser.parseFile(qc, content);
            }
            return null;
        });

        if (qc.clearNotFound() || response == null) {
            Log.debugf("[%s|%s] Commonhaus user data not found or could not be parsed",
                    qc.getLogId(), event.id());
            response = CommonhausUser.create(event.login(), event.id());
        }
        AdminDataCache.COMMONHAUS_DATA.put(key, response);
        return response;
    }

    private String dataPath(long id) {
        return "data/users/" + id + ".yaml";
    }
}
