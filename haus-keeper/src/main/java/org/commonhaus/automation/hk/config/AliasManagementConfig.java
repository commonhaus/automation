package org.commonhaus.automation.hk.config;

import static org.commonhaus.automation.github.context.GitHubQueryContext.toOrganizationName;
import static org.commonhaus.automation.github.context.GitHubQueryContext.toRelativeName;

import org.commonhaus.automation.config.RepoSource;

import com.fasterxml.jackson.annotation.JsonAutoDetect;

import io.quarkus.logging.Log;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class AliasManagementConfig {
    public static final AliasManagementConfig DISABLED = new AliasManagementConfig() {
        @Override
        public boolean isDisabled() {
            return true;
        }
    };

    Boolean enabled;
    RepoSource projectList;
    String repoPrefix;

    public boolean isDisabled() {
        return enabled != null && !enabled;
    }

    public RepoSource projectList() {
        return projectList;
    }

    public String repoPrefix() {
        return repoPrefix;
    }

    public String toProjectName(String repoFullName) {
        String orgName = toOrganizationName(repoFullName);
        String repoName = toRelativeName(orgName, repoFullName);
        if (repoPrefix != null && repoName.startsWith(repoPrefix)) {
            repoName = repoName.substring(repoPrefix.length());
        }
        Log.debugf("orgName: %s, repoPrefix: %s, repoName: %s", repoFullName, repoPrefix, repoName);
        return repoName;
    }

    @Override
    public String toString() {
        return "AliasManagementConfig [enabled=" + enabled + ", projectList=" + projectList + ", repoPrefix=" + repoPrefix
                + "]";
    }
}
