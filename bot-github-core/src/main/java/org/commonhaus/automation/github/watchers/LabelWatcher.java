package org.commonhaus.automation.github.watchers;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.commonhaus.automation.github.context.ActionType;
import org.commonhaus.automation.github.context.ContextService;
import org.commonhaus.automation.github.context.DataLabel;
import org.commonhaus.automation.github.context.EventData;
import org.commonhaus.automation.github.context.QueryContext;
import org.kohsuke.github.GHEventPayload;

import io.quarkiverse.githubapp.GitHubEvent;
import io.quarkiverse.githubapp.event.Label;
import io.quarkus.arc.properties.UnlessBuildProperty;
import io.quarkus.logging.Log;

@ApplicationScoped
@UnlessBuildProperty(name = "automation.build.disable.label", stringValue = "true")
class LabelWatcher {

    @Inject
    Instance<ContextService> ctxInstance;

    /**
     * Called when there is event.
     *
     * @param event GitHubEvent (raw payload)
     * @param github GitHub API (connection instance)
     * @param graphQLClient GraphQL client
     * @param labelPayload GitHub API parsed payload
     */
    void onRepositoryLabelChange(GitHubEvent event, @Label GHEventPayload.Label labelPayload) {
        if (labelPayload.getRepository() == null || ctxInstance.isUnsatisfied()) {
            return;
        }

        final EventData initialData = new EventData(event, labelPayload);
        DataLabel label = new DataLabel(labelPayload.getLabel());
        String cacheId = labelPayload.getRepository().getNodeId();
        ActionType actionType = ActionType.fromString(event.getAction());

        Log.debugf("[%s] LabelChanges: repository %s changed label %s", initialData.getLogId(), cacheId, label);

        QueryContext qc = new QueryContext(ctxInstance.get(), event.getInstallationId());
        qc.modifyLabels(cacheId, label, actionType);
    }
}
