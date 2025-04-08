package org.commonhaus.automation.hk.member;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

import org.kohsuke.github.GHUser;

public class MemberInfoAdapter implements MemberInfo {

    final GHUser user;
    final String notificationEmail;

    public MemberInfoAdapter(GHUser user, String notificationEmail) {
        this.user = user;
        this.notificationEmail = notificationEmail;
    }

    @Override
    public long id() {
        return user.getId();
    }

    @Override
    public String login() {
        return user.getLogin();
    }

    @Override
    public String name() {
        try {
            return user.getName();
        } catch (IOException e) {
            return user.getLogin();
        }
    }

    @Override
    public Set<String> roles() {
        return Set.of();
    }

    @Override
    public String url() {
        return user.getUrl().toString();
    }

    @Override
    public Optional<String> notificationEmail() {
        return Optional.ofNullable(notificationEmail);
    }
}
