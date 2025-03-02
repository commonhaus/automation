package org.commonhaus.automation.hm;

import java.io.IOException;

public class TestIOException extends IOException {
    private static final long serialVersionUID = 1L;

    public TestIOException(String message) {
        super(message);
    }

    public TestIOException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        // Do nothing. This is a test exception. Hush
        return this;
    }

}
