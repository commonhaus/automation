package org.commonhaus.automation.github.model;

import java.util.Collection;
import java.util.List;

import org.commonhaus.automation.AppConfig;
import org.commonhaus.automation.github.EventData;
import org.commonhaus.automation.github.model.QueryHelper.QueryContext;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.ReactionContent;

import io.quarkiverse.githubapp.GitHubClientProvider;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;

/**
 * QueryContext is a helper class to encapsulate the GitHub API and GraphQL
 * client.
 * <p>
 * It is intended to be used as a short-lived object (minutes) to execute
 * queries.
 * <p>
 * It is not thread-safe.
 */
public class EventQueryContext extends QueryContext {

    /**
     * Event data used to construct this query context
     */
    private final EventData evt;

    /** Package private. Constructed by QueryHelper */
    EventQueryContext(QueryHelper helper, AppConfig botConfig, GitHubClientProvider gitHubClientProvider,
            EventData event) {
        super(helper, botConfig, gitHubClientProvider);

        // unpack Json-B from string
        this.evt = event;
    }

    public EventData getEventData() {
        return evt;
    }

    public String getLogId() {
        return evt.getLogId();
    }

    public long installationId() {
        return evt.installationId();
    }

    public String getRepositoryId() {
        return evt.getRepositoryId();
    }

    public GHRepository getRepository() {
        return evt.getRepository();
    }

    public GHOrganization getOrganization() {
        return evt.getOrganization();
    }

    public EventQueryContext addExisting(GitHub github) {
        super.addExisting(github);
        return this;
    }

    public EventQueryContext addExisting(DynamicGraphQLClient graphQLClient) {
        super.addExisting(graphQLClient);
        return this;
    }

    /**
     * Add label by name or id to event item
     *
     * @param eventData EventData
     * @param label Label name or id
     * @return updated collection of labels for the item, or null if label not found
     */
    public Collection<DataLabel> addLabel(EventData eventData, String label) {
        return addLabels(eventData, List.of(label));
    }

    /**
     * Add label by name or id to event item
     *
     * @param eventData EventData
     * @param labels Collection of Label names or ids
     * @return updated collection of labels for the item, or null if no labels were found
     */
    public Collection<DataLabel> addLabels(EventData eventData, List<String> labels) {
        return super.addLabels(eventData.getNodeId(), labels);
    }

    public void addBotReaction(ReactionContent reaction) {
        super.addBotReaction(evt.getNodeId(), reaction);
    }

    public void removeBotReaction(ReactionContent reaction) {
        super.removeBotReaction(evt.getNodeId(), reaction);
    }

    public DataCommonComment updateBotComment(String commentBody, Integer commentIdFromBody) {
        return super.updateBotComment(this.evt.getEventType(), this.evt.getNodeId(), commentBody, commentIdFromBody);
    }

    public void updateItemDescription(String bodyString) {
        super.updateItemDescription(this.evt.getEventType(), this.evt.getNodeId(), bodyString);
    }
}
