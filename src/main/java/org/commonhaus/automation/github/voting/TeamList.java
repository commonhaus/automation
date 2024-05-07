package org.commonhaus.automation.github.voting;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

import org.commonhaus.automation.github.Voting.Config;
import org.commonhaus.automation.github.model.DataActor;
import org.commonhaus.automation.github.model.QueryHelper.QueryContext;
import org.kohsuke.github.GHUser;

public class TeamList {
    final String name;
    final Set<DataActor> members;

    public TeamList(String name, Collection<GHUser> members) {
        this.name = name;
        this.members = members.stream()
                .map(DataActor::new)
                .collect(Collectors.toSet());
    }

    /** testing */
    public TeamList(Set<DataActor> members, String name) {
        this.name = name;
        this.members = members;
    }

    public TeamList removeExcludedLogins(QueryContext qc, Config voteConfig) {
        members.removeIf(a -> qc.isBot(a.login) || voteConfig.isLoginExcluded(a.login));
        return this;
    }
}
