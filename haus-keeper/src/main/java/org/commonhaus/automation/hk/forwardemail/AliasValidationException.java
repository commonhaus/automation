package org.commonhaus.automation.hk.forwardemail;

/**
 * Thrown when a caller-supplied alias update fails local pre-flight
 * validation, before any ForwardEmail API call is made.
 */
public class AliasValidationException extends RuntimeException {
    public AliasValidationException(String reason) {
        super(reason);
    }
}
