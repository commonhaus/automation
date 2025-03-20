package org.commonhaus.automation.github.context;

import org.kohsuke.github.HttpException;

public class TestHttpException extends HttpException {
    private static final long serialVersionUID = 1L;

    public TestHttpException(int status, String message) {
        super(status, message, message, null);
    }

    // @Override
    // public synchronized Throwable fillInStackTrace() {
    //     // Do nothing. This is a test exception. Hush
    //     return this;
    // }

}
