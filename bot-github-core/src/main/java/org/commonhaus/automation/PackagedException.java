package org.commonhaus.automation;

import java.io.PrintWriter;
import java.io.StringWriter;
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
        try (StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw)) {
            pw.printf("%s Exception(s)\n\n", exceptions.size());
            for (Throwable e : exceptions) {
                e.printStackTrace(pw);
            }
            pw.printf("%s Errors(s)\n\n", errors.size());
            for (GraphQLError e : errors) {
                pw.println(e.getMessage());
            }
            return sw.toString();
        } catch (Exception e) {
            return "Failed to generate details: %s".formatted(e.toString());
        }
    }

    public List<Throwable> getExceptions() {
        return exceptions;
    }

    public List<GraphQLError> getErrors() {
        return errors;
    }

    public String list() {
        List<String> messages = new ArrayList<>();
        for (Throwable e : exceptions) {
            messages.add(e.toString());
        }
        for (GraphQLError e : errors) {
            messages.add(e.toString());
        }
        return String.join("; ", messages);
    }

    @Override
    public String toString() {
        return super.toString() + ": " + list();
    }
}
