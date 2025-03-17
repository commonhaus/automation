package org.commonhaus.automation.github.hr.rules;

import java.util.ArrayList;
import java.util.List;

import org.commonhaus.automation.github.context.DataActor;
import org.commonhaus.automation.github.context.EventData;
import org.commonhaus.automation.github.hr.EventQueryContext;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHUser;

public class MatchMember {
    public final List<String> include = new ArrayList<>();
    public final List<String> exclude = new ArrayList<>();

    public MatchMember(List<String> groups) {
        groups.forEach(x -> {
            if (x.startsWith("!")) {
                exclude.add(x.substring(1));
            } else {
                include.add(x);
            }
        });
    }

    public boolean matches(EventQueryContext qc) {
        EventData eventData = qc.getEventData();
        if (eventData == null) {
            return false; // unlikely/bad event; fail match
        }
        DataActor author = eventData.getAuthor();
        if (author == null) {
            return false; // unlikely/bad event; fail match
        }
        GHUser user = qc.getUser(author.login); // cached lookup

        if (!exclude.isEmpty() && exclude.stream().anyMatch(group -> userIsMember(qc, user, group))) {
            return false;
        }

        return include.isEmpty() || include.stream().anyMatch(group -> userIsMember(qc, user, group));
    }

    private boolean userIsMember(EventQueryContext qc, GHUser user, String group) {
        if (group.contains("/")) {
            // Check for team membership
            return qc.isTeamMember(user, group);
        }
        // Check for org membership
        GHOrganization org = qc.getOrganization(group);
        return org.hasMember(user);
    }

}
