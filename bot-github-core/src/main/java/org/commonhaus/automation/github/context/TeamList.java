package org.commonhaus.automation.github.context;

import java.util.Collection;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.kohsuke.github.GHUser;

public class TeamList {
    public final String name;
    public final Set<DataActor> members;

    public TeamList(String name, Collection<GHUser> members) {
        this.name = name;
        this.members = members.stream()
                .map(DataActor::new)
                .collect(Collectors.toSet());
    }

    /** testing */
    public TeamList(String name, Set<DataActor> members) {
        this.name = name;
        this.members = members;
    }

    public TeamList removeExcludedMembers(Predicate<DataActor> predicate) {
        members.removeIf(predicate::test);
        return this;
    }

    public int size() {
        return members.size();
    }

    public String toString() {
        return "TeamList[%s: %s]".formatted(name, members.stream()
                .map(actor -> actor.login)
                .collect(Collectors.joining(",")));
    }

    public String toPlainList() {
        return members.stream()
                .map(actor -> "- %s (%s)\n".formatted(actor.login, actor.url))
                .collect(Collectors.joining(", "));
    }

    public String toHtmlList() {
        return "<ul>" + members.stream()
                .map(actor -> "<li><a href=\"%s\">%s</a></li>".formatted(actor.url, actor.login))
                .collect(Collectors.joining(", "))
                + "</ul>";
    }
}
