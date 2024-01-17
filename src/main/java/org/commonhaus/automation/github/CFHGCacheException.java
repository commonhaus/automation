package org.commonhaus.automation.github;

import java.io.IOException;
import java.util.List;

import io.smallrye.graphql.client.GraphQLError;

public class CFHGCacheException extends IOException {
    List<GraphQLError> errors;
    
    public CFHGCacheException(CFGHQueryContext queryContext) {
        super("Unable to cache information for repository " + queryContext.getGhRepository().getFullName() + " (" + queryContext.getGhiId() + ")");
        if (queryContext.hasErrors()) {
            this.errors = queryContext.errors;
            for (Throwable t : queryContext.exceptions) {
                addSuppressed(t);
            }
        }   
    }

    public List<GraphQLError> getErrors() {
        return errors;
    }
}
