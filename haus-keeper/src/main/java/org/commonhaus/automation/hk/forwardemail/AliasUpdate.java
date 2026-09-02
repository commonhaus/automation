package org.commonhaus.automation.hk.forwardemail;

import java.util.Set;

public record AliasUpdate(Set<String> recipients, boolean has_imap) {
}
