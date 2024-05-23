package org.commonhaus.automation.github.context;

import static org.commonhaus.automation.github.context.BaseQueryCache.REPO_CONFIG;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Optional;

import jakarta.enterprise.event.Observes;

import org.commonhaus.automation.config.BotConfig;
import org.commonhaus.automation.github.discovery.RepositoryDiscoveryEvent;
import org.commonhaus.automation.mail.MailEvent;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import io.quarkiverse.githubapp.ConfigFile.Source;
import io.quarkiverse.githubapp.GitHubClientProvider;
import io.quarkiverse.githubapp.GitHubConfigFileProvider;
import io.quarkus.logging.Log;
import io.quarkus.mailer.MailTemplate.MailTemplateInstance;
import io.quarkus.qute.CheckedTemplate;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;
import io.vertx.mutiny.core.eventbus.EventBus;

public abstract class BaseContextService implements ContextService {
    @CheckedTemplate
    static class Templates {
        public static native MailTemplateInstance basicEmail(String title, String body, String htmlBody);
    }

    private final GitHubClientProvider gitHubClientProvider;
    private final GitHubConfigFileProvider configProvider;
    private final EventBus bus;
    private final BotConfig data;
    private final String[] errorAddress;

    public BaseContextService(BotConfig data, GitHubClientProvider gitHubClientProvider,
            GitHubConfigFileProvider configProvider, EventBus bus) {
        this.gitHubClientProvider = gitHubClientProvider;
        this.configProvider = configProvider;
        this.data = data;
        this.bus = bus;
        errorAddress = data.errorEmailAddress().isPresent()
                ? new String[] { data.errorEmailAddress().get() }
                : null;
    }

    public EventBus getBus() {
        return bus;
    }

    public Optional<String> replyTo() {
        return data.replyTo();
    }

    public boolean isDiscoveryEnabled() {
        Optional<Boolean> discoveryEnabled = data.discoveryEnabled();
        return discoveryEnabled.isEmpty() || discoveryEnabled.get();
    }

    public boolean isDryRun() {
        Optional<Boolean> dryRun = data.dryRun();
        return dryRun.isPresent() && dryRun.get();
    }

    public String[] botErrorEmailAddress() {
        return errorAddress;
    }

    public GitHub getInstallationClient(long installationId) {
        return gitHubClientProvider.getInstallationClient(installationId);
    }

    public DynamicGraphQLClient getInstallationGraphQLClient(long installationId) {
        return gitHubClientProvider.getInstallationGraphQLClient(installationId);
    }

    protected void repositoryDiscovered(@Observes RepositoryDiscoveryEvent repoEvent) {
        if (repoEvent.removed()) {
            REPO_CONFIG.invalidate(repoEvent.repository().getNodeId());
        } else {
            // Update repo config cache on discovery event
            Optional<?> repoConfig = repoEvent.getRepositoryConfig();
            REPO_CONFIG.put(repoEvent.repository().getNodeId(), repoConfig);
        }
    }

    public <F> void updateConfiguration(GHRepository repo, F repositoryConfig) {
        REPO_CONFIG.put(repo.getNodeId(), Optional.of(repositoryConfig));
    }

    public <F> F getConfiguration(GHRepository repo) {
        Optional<F> repoConfig = REPO_CONFIG.get(repo.getNodeId());
        if (repoConfig == null) {
            // unless we haven't had an event for a repo in a long while, this should more
            // or less never happen
            String configFileName = getConfigFileName();
            @SuppressWarnings("unchecked")
            Class<F> configType = (Class<F>) getConfigType();
            repoConfig = configProvider.fetchConfigFile(repo, configFileName, Source.DEFAULT, configType);
            REPO_CONFIG.put(repo.getNodeId(), repoConfig);
        }
        return repoConfig.orElse(null);
    }

    public void sendEmail(String logId, String title, String body, String htmlBody, String[] addresses) {
        MailEvent event = new MailEvent(logId, Templates.basicEmail(title, body, htmlBody), title, addresses);
        if (event.hasAddresses()) {
            bus.send(MailEvent.ADDRESS, event);
        }
    }

    public void logAndSendEmail(String logId, String title, Throwable t, String[] addresses) {
        Log.errorf(t, "[%s] %s: %s", logId, title, t.toString());
        if (Log.isDebugEnabled()) {
            t.printStackTrace();
        }
        MailEvent event = createErrorMailEvent(logId, title, "", t, addresses);
        if (event.hasAddresses()) {
            bus.send(MailEvent.ADDRESS, event);
        }
    }

    public void logAndSendEmail(String logId, String title, String body, Throwable t, String[] addresses) {
        if (t == null) {
            Log.errorf(t, "[%s] %s: %s", logId, title, body);
        } else {
            Log.errorf(t, "[%s] %s: %s; %s", logId, title, t.toString(), body);
            if (Log.isDebugEnabled()) {
                t.printStackTrace();
            }
        }
        MailEvent event = createErrorMailEvent(logId, title, body, t, addresses);
        if (event.hasAddresses()) {
            bus.send(MailEvent.ADDRESS, event);
        }
    }

    MailEvent createErrorMailEvent(String logId, String title, String body, Throwable e, String[] addresses) {
        // If configured to do so, email the error_email_address
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        if (body != null) {
            pw.println(body);
            pw.println();
        }
        if (e != null) {
            e.printStackTrace(pw);
            pw.println();
        }

        String messageBody = sw.toString();
        String htmlBody = messageBody.replace("\n", "<br/>\n");

        MailTemplateInstance mail = Templates.basicEmail(title, messageBody, htmlBody);
        return new MailEvent(logId, mail, title,
                addresses == null ? botErrorEmailAddress() : addresses);
    }
}
