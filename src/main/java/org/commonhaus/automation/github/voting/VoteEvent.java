package org.commonhaus.automation.github.voting;

import org.commonhaus.automation.github.Voting;
import org.commonhaus.automation.github.Voting.Config;
import org.commonhaus.automation.github.model.QueryHelper.QueryContext;

public class VoteEvent {
    final QueryContext qc;
    final Voting.Config votingConfig;

    public VoteEvent(QueryContext qc, Config votingConfig) {
        this.qc = qc;
        this.votingConfig = votingConfig;
    }
}
