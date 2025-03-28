package org.commonhaus.automation.hm;

import static org.commonhaus.automation.github.context.QueryContext.toOrganizationName;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.commonhaus.automation.config.EmailNotification;
import org.commonhaus.automation.config.RouteSupplier;
import org.commonhaus.automation.github.context.DataSponsorship;
import org.commonhaus.automation.github.scopes.ScopedQueryContext;
import org.commonhaus.automation.github.watchers.MembershipWatcher.MembershipUpdate;
import org.commonhaus.automation.hm.config.LatestOrgConfig;
import org.commonhaus.automation.hm.config.OrganizationConfig;
import org.commonhaus.automation.hm.config.OrganizationConfig.SponsorsConfig;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHRepository;

import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;

@ApplicationScoped
public class SponsorManager extends GroupCoordinator {
    static final String ME = "sponsorManager";
    private static volatile String lastRun = "";

    @Inject
    LatestOrgConfig latestOrgConfig;

    void startup(@Observes StartupEvent startup) {
        RouteSupplier.registerSupplier("Sponsor management refreshed", () -> lastRun);
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
    public void refreshSponsors() {
        Log.info("⏰ Scheduled: refresh sponsors");

        periodicSync.queueReconciliation(ME, () -> reconcile());
    }

    public void reconcile() {
        lastRun = DateTimeFormatter.ISO_INSTANT.format(Instant.now());

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

        teamService.syncCollaborators(qc, repo, role, sponsorLogins,
                sponsors.ignoreUsers(), sponsors.dryRun(),
                config.emailNotifications());

        Log.debugf("[%s] reconcile: end %s", ME, sponsors);
    }

    private Set<String> getSponsors(OrganizationConfig config) {
        String sponsorable = config.sponsors().sponsorable();
        EmailNotification notifications = config.emailNotifications();

        // Extract sponsorable entity from source
        String sponsorableAccount = toOrganizationName(sponsorable);
        ScopedQueryContext qc = ctx.getOrgScopedQueryContext(sponsorableAccount);
        if (qc == null) {
            Log.warnf("[%s] getSponsors: no query context for %s", ME, sponsorable);
            return null;
        }

        // Query for sponsors
        List<DataSponsorship> recentSponsors = DataSponsorship.queryRecentSponsors(qc, sponsorable);
        if (qc.hasErrors() || recentSponsors == null) {
            qc.logAndSendContextErrors("[%s] getSponsors: unable to retrieve sponsors %s"
                    .formatted(ME, OrganizationConfig.PATH, sponsorable),
                    notifications);
            return null;
        }

        Set<String> sponsorLogins = recentSponsors.stream()
                .map(DataSponsorship::sponsorLogin)
                .collect(Collectors.toSet());

        Log.debugf("[%s] getSponsors: found sponsors for %s: %s", ME,
                sponsorable, sponsorLogins);
        return sponsorLogins;
    }

}
