package org.commonhaus.automation.github.context;

import org.kohsuke.github.GitHub;

import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;

public interface ContextService {

    boolean isDryRun();

    Class<?> getConfigType();

    String getConfigFileName();

    boolean isDiscoveryEnabled();

    String errorEmailAddress();

    GitHub getInstallationClient(long installationId);

    DynamicGraphQLClient getInstallationGraphQLClient(long installationId);

    void logAndSendEmail(String logId, String title, Throwable t);

    void logAndSendEmail(String logId, String title, String body, Throwable t);

    void sendEmail(String logId, String title, String body, String htmlBody, String[] addresses);
}
