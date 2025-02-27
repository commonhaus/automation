package org.commonhaus.automation.config;

public record RepoSource(String repository, String filePath) {
    public boolean isEmpty() {
        return filePath == null || filePath.isBlank();
    }

    @Override
    public String toString() {
        return "%s#=%s".formatted(repository(), filePath());
    }
}
