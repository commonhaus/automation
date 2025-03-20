package org.commonhaus.automation.github.context;

import java.util.List;
import java.util.Map;

import org.kohsuke.github.GHFileNotFoundException;

public class TestFileNotFoundException extends GHFileNotFoundException {
    private static final long serialVersionUID = 1L;

    public TestFileNotFoundException(String message) {
        super(message);
    }

    @Override
    public Map<String, List<String>> getResponseHeaderFields() {
        return Map.of();
    }

    // @Override
    // public synchronized Throwable fillInStackTrace() {
    //     // Do nothing. This is a test exception. Hush
    //     return this;
    // }

}
