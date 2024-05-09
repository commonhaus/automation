package org.commonhaus.automation.github.voting;

import java.util.Date;

import org.commonhaus.automation.github.context.DataActor;
import org.commonhaus.automation.github.context.DataReaction;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VoteRecord {
    public final String login;
    public final String url;
    public final Date createdAt;
    public final String reaction;

    public VoteRecord(DataReaction reaction) {
        this.login = reaction.user.login;
        this.url = reaction.user.url;
        this.createdAt = reaction.createdAt;
        this.reaction = DataReaction.toEmoji(reaction);
    }

    public VoteRecord(DataActor actor, Date createdAt) {
        this.login = actor.login;
        this.url = actor.url;
        this.createdAt = createdAt;
        this.reaction = null;
    }

    public String login() {
        return login;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((login == null) ? 0 : login.hashCode());
        result = prime * result + ((reaction == null) ? 0 : reaction.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        VoteRecord other = (VoteRecord) obj;
        if (login == null) {
            if (other.login != null)
                return false;
        } else if (!login.equals(other.login))
            return false;
        if (reaction == null) {
            return other.reaction == null;
        } else
            return reaction.equals(other.reaction);
    }
}
