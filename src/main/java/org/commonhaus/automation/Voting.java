package org.commonhaus.automation;

import org.commonhaus.automation.github.CFGHEventHandler;

/**
 * Highlevel workflow to manage voting.
 *
 * This acts as a mixin: stored with CFGH RepoInfo if voting is enabled.
 */
@CFGHEventHandler(name = "Voting", description = "High-level workflow to manage voting.", enabledByDefault = false, requiredLabels = {
        Voting.VOTE_OPEN, Voting.VOTE_QUORUM, Voting.VOTE_DONE,
        Voting.VOTE_PROCEED, Voting.VOTE_REVISE })
public class Voting {
    public static final String VOTE_OPEN = "vote/open";
    public static final String VOTE_QUORUM = "vote/quorum";
    public static final String VOTE_DONE = "vote/done";
    public static final String VOTE_PROCEED = "vote/proceed";
    public static final String VOTE_REVISE = "vote/revise";

}
