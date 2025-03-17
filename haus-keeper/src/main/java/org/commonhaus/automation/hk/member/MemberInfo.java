package org.commonhaus.automation.hk.member;

import java.util.Optional;
import java.util.Set;

public interface MemberInfo {
    long id();

    String login();

    String name();

    Set<String> roles();

    String url();

    Optional<String> notificationEmail();
}
