package org.commonhaus.automation.github.discovery;

import java.util.List;

public record BootstrapDiscoveryEvent(
        List<Long> installations) {
}
