package org.commonhaus.automation.github.context;

import java.util.List;

import io.smallrye.graphql.client.GraphQLError;

public class PackagedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    final List<Throwable> exceptions;
    final List<GraphQLError> errors;

    public PackagedException(List<Throwable> exceptions, List<GraphQLError> errors) {
        super("%s exception(s) / %s error(s) occurred while working with GitHub".formatted(
                exceptions.size(), errors.size()));
        this.exceptions = exceptions;
        this.errors = errors;
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        // Do nothing. This is a packaged exception.
        return this;
    }

    public String details() {
        StringBuilder sb = new StringBuilder();
        sb.append(getMessage());
        if (!exceptions.isEmpty()) {
            sb.append("Exceptions:\n");
            for (Throwable e : exceptions) {
                sb.append(e.getMessage()).append("\n");
            }
        }
        if (!errors.isEmpty()) {
            sb.append("Errors:\n");
            for (GraphQLError e : errors) {
                sb.append(e.getMessage()).append("\n");
            }
        }
        return sb.toString();
    }
}
