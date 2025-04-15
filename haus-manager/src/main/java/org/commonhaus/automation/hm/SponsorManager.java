package org.commonhaus.automation.hm;

import static org.commonhaus.automation.github.context.GitHubQueryContext.toOrganizationName;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.commonhaus.automation.config.BotConfig;
import org.commonhaus.automation.config.EmailNotification;
import org.commonhaus.automation.config.RouteSupplier;
import org.commonhaus.automation.github.context.DataSponsorship;
import org.commonhaus.automation.github.scopes.ScopedQueryContext;
import org.commonhaus.automation.github.watchers.MembershipWatcher.MembershipUpdate;
import org.commonhaus.automation.hm.config.LatestOrgConfig;
import org.commonhaus.automation.hm.config.OrganizationConfig;
import org.commonhaus.automation.hm.config.OrganizationConfig.SponsorsConfig;
import org.commonhaus.automation.opencollective.OpenCollectiveQueryContext;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHRepository;

import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;

@ApplicationScoped
public class SponsorManager extends GroupCoordinator {
    static final String ME = "💸-sponsor";

    @Inject
    protected BotConfig baseBotConfig;

    @Inject
    LatestOrgConfig latestOrgConfig;

    void startup(@Observes StartupEvent startup) {
        RouteSupplier.registerSupplier("Sponsor management refreshed", () -> lastRun);
        // If the OrganizationConfig changes, we'll be notified
        latestOrgConfig.notifyOnUpdate(ME, this::reconcile);
    }

    @Override
    protected void processMembershipUpdate(String taskGroup, MembershipUpdate update) {
        // No action needed for sponsorship updates
        Log.debugf("[%s] processMembershipUpdate: no action needed for %s", ME, update);
    }

    /**
     * Periodically refresh/re-synchronize sponsor collaborators
     */
    @Scheduled(cron = "${automation.hausManager.cron.sponsor:0 47 1 */3 * ?}")
    public void scheduledRefresh() {
        try {
            Log.infof("[%s] ⏰ Scheduled: refresh sponsors", ME);
            refreshSponsors(false);
        } catch (Throwable t) {
            ctx.logAndSendEmail(ME, "💸 ⏰ Error running scheduled sponsors refresh", t);
        }
    }

    public void refreshSponsors(boolean userTriggered) {
        if (!userTriggered && !taskState.shouldRun(ME, Duration.ofHours(6))) {
            Log.infof("[%s]: skip scheduled sponsored refresh (last run: %s)", ME, lastRun);
            return;
        }
        recordRun();
        updateQueue.queueReconciliation(ME, () -> reconcile());
    }

    public void reconcile() {
        OrganizationConfig config = latestOrgConfig.getConfig();
        if (config == null || config.sponsors() == null) {
            Log.debugf("[%s] refreshSponsors: configuration not available or sponsors not enabled", ME);
            return;
        }

        SponsorsConfig sponsors = config.sponsors();
        Log.debugf("[%s] reconcile: start %s", ME, sponsors);

        Set<String> sponsorLogins = getSponsors(config);
        if (sponsorLogins == null) {
            Log.warnf("[%s] reconcile: unable to retrieve sponsors for %s", ME, sponsors);
            return;
        }
        if (sponsorLogins.isEmpty()) {
            Log.debugf("[%s] reconcile: no sponsors found for %s", ME, sponsors);
            return;
        }

        String repoFullName = sponsors.targetRepository();
        String orgName = toOrganizationName(repoFullName);
        ScopedQueryContext qc = ctx.getOrgScopedQueryContext(orgName);
        if (qc == null) {
            Log.debugf("[%s] reconcile: no query context for target repository", ME, sponsors);
            return;
        }
        GHOrganization org = qc.getOrganization(orgName);
        if (org == null) {
            Log.warnf("[%s] reconcile: organization %s not found", ME, orgName);
            return;
        }
        GHRepository repo = qc.getRepository(repoFullName);
        if (repo == null) {
            Log.warnf("[%s] reconcile: repository %s not found", ME, repoFullName);
            return;
        }

        GHOrganization.RepositoryRole role = toRole("reconcile", sponsors.role(),
                config.emailNotifications(), sponsors);

        teamService.addExpectedCollaborators(qc, repo, role, sponsorLogins,
                sponsors.ignoreUsers(), sponsors.dryRun(),
                config.emailNotifications());

        Log.debugf("[%s] reconcile: end %s", ME, sponsors);
    }

    private Set<String> getSponsors(OrganizationConfig config) {
        String sponsorable = config.sponsors().sponsorable();
        EmailNotification notifications = config.emailNotifications();

        Set<String> sponsorLogins = new HashSet<>();

        // Extract sponsorable entity from source
        String sponsorableAccount = toOrganizationName(sponsorable);
        ScopedQueryContext qc = ctx.getOrgScopedQueryContext(sponsorableAccount);
        if (qc == null) {
            Log.warnf("[%s] getSponsors: no query context for %s", ME, sponsorable);
        } else {
            // Query for GitHub sponsors
            List<DataSponsorship> recentSponsors = DataSponsorship.queryRecentSponsors(qc, sponsorable);
            if (recentSponsors != null) {
                sponsorLogins.addAll(recentSponsors.stream()
                        .map(DataSponsorship::sponsorLogin).toList());
            }
        }
        if (qc.hasErrors()) {
            qc.logAndSendContextErrors("[%s] getSponsors: unable to retrieve sponsors %s"
                    .formatted(ME, sponsorable),
                    notifications);
        }
        Log.debugf("[%s] getSponsors: found sponsors for %s: %s", ME,
                sponsorable, sponsorLogins);

        // Find github sponsors from OpenCollective
        OpenCollectiveQueryContext ocQc = new OpenCollectiveQueryContext(ctx, baseBotConfig);
        List<String> openCollectiveSponsors = ocQc.getGitContributorLogins();
        if (ocQc.hasErrors() || openCollectiveSponsors == null) {
            ocQc.logAndSendContextErrors("[%s] getSponsors: unable to retrieve OpenCollective sponsors"
                    .formatted(ME),
                    notifications);
        } else {
            Log.debugf("[%s] getSponsors: found sponsors from OpenCollective: %s", ME,
                    openCollectiveSponsors);

            sponsorLogins.addAll(openCollectiveSponsors);
        }

        return sponsorLogins;
    }

    @Override
    public String me() {
        return ME;
    }
}
