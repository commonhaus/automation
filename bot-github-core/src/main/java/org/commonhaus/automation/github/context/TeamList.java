package org.commonhaus.automation.github.context;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.kohsuke.github.GHUser;

public class TeamList {
    public final String name;
    public final Set<DataActor> members;
    public final boolean initialMembers;

    public TeamList(String name, Collection<GHUser> members) {
        this(name, members == null
                ? null
                : members.stream().map(DataActor::new).collect(Collectors.toSet()));
    }

    public TeamList(String name, Set<DataActor> members) {
        this.name = name;
        initialMembers = members != null;
        this.members = new HashSet<>();
        if (members != null) {
            this.members.addAll(members);
        }
    }

    public TeamList removeExcludedMembers(Predicate<DataActor> predicate) {
        if (!members.isEmpty()) {
            members.removeIf(predicate::test);
        }
        return this;
    }

    public boolean hasLogin(String login) {
        return members.stream().anyMatch(actor -> actor.login.equals(login));
    }

    public int size() {
        return members.size();
    }

    public String toString() {
        return "TeamList[%s: %s]".formatted(name, members.stream()
                .map(actor -> actor.login)
                .collect(Collectors.joining(",")));
    }

    public boolean isEmpty() {
        return members == null || members.isEmpty();
    }
}
