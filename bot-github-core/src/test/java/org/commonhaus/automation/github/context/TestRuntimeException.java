package org.commonhaus.automation.github.context;

import java.io.IOException;

public class TestRuntimeException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public TestRuntimeException(String message) {
        super(message);
    }

    public TestRuntimeException(String message, IOException e) {
        System.out.println("%s: %s".formatted(message, e.toString()));
        e.printStackTrace();
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        // Do nothing. This is a test exception. Hush
        return this;
    }

}
