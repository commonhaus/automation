package org.commonhaus.automation.github.voting;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

import org.commonhaus.automation.github.model.DataActor;
import org.commonhaus.automation.github.model.QueryHelper.QueryContext;
import org.kohsuke.github.GHUser;

public class TeamList {
    String name;
    Set<DataActor> members;

    public TeamList(String name, Collection<GHUser> members) {
        this.name = name;
        this.members = members.stream()
                .map(u -> new DataActor(u))
                .collect(Collectors.toSet());
    }

    /** testing */
    public TeamList(Set<DataActor> members, String name) {
        this.name = name;
        this.members = members;
    }

    public TeamList removeBot(QueryContext qc) {
        members.removeIf(a -> qc.isBot(a.login));
        return this;
    }
}
