package org.commonhaus.automation.github.context;

import org.kohsuke.github.GitHub;

import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;

public interface ContextService {

    boolean isDryRun();

    Class<?> getConfigType();

    String getConfigFileName();

    boolean isDiscoveryEnabled();

    String[] botErrorEmailAddress();

    GitHub getInstallationClient(long installationId);

    DynamicGraphQLClient getInstallationGraphQLClient(long installationId);

    void updateConnection(long installationId, GitHub gh);

    void updateConnection(long installationId, DynamicGraphQLClient gql);

    void logAndSendEmail(String logId, String title, Throwable t, String[] addresses);

    void logAndSendEmail(String logId, String title, String body, Throwable t, String[] addresses);

    void sendEmail(String logId, String title, String body, String htmlBody, String[] addresses);
}
