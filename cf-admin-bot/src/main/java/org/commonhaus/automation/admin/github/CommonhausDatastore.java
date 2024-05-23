package org.commonhaus.automation.admin.github;

import java.util.concurrent.Executor;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.commonhaus.automation.admin.api.CommonhausUser;
import org.commonhaus.automation.admin.api.CommonhausUser.MemberStatus;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHContentBuilder;
import org.kohsuke.github.GHContentUpdateResponse;
import org.kohsuke.github.GHRepository;

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

    public record QueryMessage(String login, long id) {
    };

    public record UpdateMessage(CommonhausUser user, String message) {
    };

    /**
     * GET Commonhaus user data
     *
     * @param login
     * @param id
     * @return
     */
    public CommonhausUser getCommonhausUser(String login, long id) {
        QueryMessage query = new QueryMessage(login, id);
        Message<CommonhausUser> response = ctx.getBus().requestAndAwait(CommonhausDatastore.READ, query);
        Log.debugf("[getCommonhausUser|%s] Get Commonhaus user data: %s", id, response.body());
        return response.body();
    }

    /**
     * PUT Commonhaus user data
     *
     * @param login
     * @param id
     * @return
     */
    public CommonhausUser setCommonhausUser(CommonhausUser user, String message) {
        UpdateMessage update = new UpdateMessage(user, message);
        Message<CommonhausUser> response = ctx.getBus().requestAndAwait(CommonhausDatastore.WRITE, update);
        Log.debugf("[setCommonhausUser|%s] Update Commonhaus user data: %s", user.id(), response.body());
        return response.body();
    }

    /**
     * Retrieve Commonhaus user data from the repository using
     * the GitHub bot's login and id.
     *
     * @param query
     * @return
     */
    @ConsumeEvent(value = READ)
    public Uni<CommonhausUser> fetchCommonhausUser(QueryMessage query) {
        final String key = query.login + ":" + query.id;

        CommonhausUser result = AdminDataCache.COMMONHAUS_DATA.get(key);
        AdminQueryContext qc = ctx.getAdminQueryContext();
        if (result == null && qc != null) {
            GHRepository repo = qc.getRepository();
            result = qc.execGitHubSync((gh, dryRun) -> {
                GHContent content = repo.getFileContent(dataPath(query.id));
                if (content == null) {
                    return null;
                }
                CommonhausUser response = CommonhausUser.parseFile(qc, content);
                return AdminDataCache.COMMONHAUS_DATA.computeIfAbsent(key, k -> response);
            });

            if (result == null && qc.hasNotFound()) {
                qc.clearErrors();
                result = AdminDataCache.COMMONHAUS_DATA.computeIfAbsent(key,
                        k -> CommonhausUser.create(query.login, query.id, MemberStatus.UNKNOWN));
            }

            if (qc.hasErrors()) {
                RuntimeException e = qc.bundleExceptions();
                Log.errorf("[%s|%s] Failed to fetch Commonhaus user data: %s", qc.getLogId(), query.id, e);
                return Uni.createFrom().failure(e);
            }
        }

        Log.debugf("[%s|%s] Fetched Commonhaus user data: %s", qc.getLogId(), query.id, result);
        final CommonhausUser r = result;
        return Uni.createFrom().item(() -> r).emitOn(executor);
    }

    /**
     * Update Commonhaus user data in the repository using
     * the GitHub bot's login and id.
     *
     * @param query
     * @return
     */
    @Blocking
    @ConsumeEvent(value = WRITE)
    public Uni<CommonhausUser> pushCommonhausUser(UpdateMessage event) {
        final CommonhausUser user = event.user();
        final String key = user.login() + ":" + user.id();

        AdminQueryContext qc = ctx.getAdminQueryContext();
        CommonhausUser result = user;
        if (qc != null) {
            GHRepository repo = qc.getRepository();
            result = AdminDataCache.COMMONHAUS_DATA.compute(key, (k, v) -> {
                // GHContentUpdateResponse response = update.commit();
                return qc.execGitHubSync((gh, dryRun) -> {
                    String content = qc.writeValue(user);
                    GHContentBuilder update = repo.createContent()
                            .message(event.message())
                            .content(content);

                    CommonhausUser oldValue = (CommonhausUser) v;
                    if (oldValue != null) {
                        update.sha(oldValue.sha());
                    }
                    GHContentUpdateResponse response = update.commit();
                    GHContent responseContent = response.getContent();
                    CommonhausUser newValue = qc.parseFile(responseContent, CommonhausUser.class);
                    return newValue;
                });
            });

            if (qc.hasErrors()) {
                RuntimeException e = qc.bundleExceptions();
                Log.errorf("[%s|%s] Failed to fetch Commonhaus user data: %s", qc.getLogId(), user.id(), e);
                return Uni.createFrom().failure(e);
            }
        } else {
            result = null;
        }

        Log.debugf("[%s|%s] Updated Commonhaus user data: %s", qc.getLogId(), user.id(), result);
        final CommonhausUser u = result;
        return Uni.createFrom().item(() -> u).emitOn(executor);
    }

    private String dataPath(long id) {
        return "data/logins/" + id + ".json";
    }
}
