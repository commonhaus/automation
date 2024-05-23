package org.commonhaus.automation.admin;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import io.smallrye.config.ConfigMapping;

@ConfigMapping(prefix = "automation.admin")
public interface AdminConfig {
    Optional<String> teamSyncCron();

    String dataStore();

    MemberConfig member();

    AttestationConfig attestations();

    interface MemberConfig {
        URI home();

        Optional<List<String>> organizations();

        Optional<List<String>> collaborators();
    }

    public interface AttestationConfig {
        String path();

        String repo();
    }
}
