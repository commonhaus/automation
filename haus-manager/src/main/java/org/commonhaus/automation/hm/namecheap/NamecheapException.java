package org.commonhaus.automation.hm.namecheap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Exception thrown when Namecheap API returns an error response.
 * Wraps one or more error codes and messages from the API.
 */
public class NamecheapException extends RuntimeException {

    private final List<NamecheapError> errors;

    public NamecheapException(String message) {
        super(message);
        this.errors = Collections.emptyList();
    }

    public NamecheapException(String message, Throwable cause) {
        super(message, cause);
        this.errors = Collections.emptyList();
    }

    public NamecheapException(List<NamecheapError> errors) {
        super(formatErrorMessage(errors));
        this.errors = new ArrayList<>(errors);
    }

    public List<NamecheapError> getErrors() {
        return Collections.unmodifiableList(errors);
    }

    private static String formatErrorMessage(List<NamecheapError> errors) {
        if (errors.isEmpty()) {
            return "Namecheap API error (no error details available)";
        }

        String errorList = errors.stream()
                .map(e -> "Error %s: %s".formatted(e.number(), e.message()))
                .collect(java.util.stream.Collectors.joining("\n  - ", "  - ", ""));
        return "Namecheap API error(s):\n" + errorList;
    }

    /**
     * Represents a single error returned by the Namecheap API.
     *
     * @param number Error number from the API
     * @param message Error message from the API
     */
    public record NamecheapError(String number, String message) {
    }
}
