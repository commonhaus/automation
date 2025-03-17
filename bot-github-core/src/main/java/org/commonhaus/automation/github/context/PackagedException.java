package org.commonhaus.automation.github.context;

import java.util.ArrayList;
import java.util.List;

import io.smallrye.graphql.client.GraphQLError;

public class PackagedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final List<Throwable> exceptions;
    private final List<GraphQLError> errors;

    public PackagedException(List<Throwable> exceptions, List<GraphQLError> errors) {
        super();
        this.exceptions = exceptions != null ? exceptions : new ArrayList<>();
        this.errors = errors != null ? errors : new ArrayList<>();
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        // Do nothing. This is a packaged exception.
        // All of the interesting stack traces belong to
        // the exceptions and errors that are packaged within this exception.
        return this;
    }

    @Override
    public String getMessage() {
        return "%s exception(s) / %s error(s) occurred while working with GitHub".formatted(
                exceptions.size(), errors.size());
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
