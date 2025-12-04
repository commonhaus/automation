package org.commonhaus.automation.hm.github;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import org.commonhaus.automation.github.context.ContextHelper.DefaultValues;
import org.commonhaus.automation.github.scopes.ScopedInstallationMap;

import io.quarkus.test.Mock;

@Mock
@Alternative
@Priority(1)
@ApplicationScoped
public class TestScopedInstallationMap extends ScopedInstallationMap {

    public void addTestOrg(DefaultValues defaultValues) {
        super.updateInstallationMap(
                defaultValues.installId(),
                defaultValues.repoFullName());
    }

    public void addTestOrg(long installId, String repoFullName) {
        super.updateInstallationMap(installId, repoFullName);
    }

    public void reset() {
        installationsById.clear();
        installationsByScope.clear();
    }
}
