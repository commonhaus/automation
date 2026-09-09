package org.commonhaus.automation.config;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record RepoSource(String repository, String filePath) {
    public boolean isEmpty() {
        return repository == null || repository.isBlank() || filePath == null || filePath.isBlank();
    }

    /**
     * Resolve this source against the repository that owns it, defaulting
     * {@code repository} to {@code defaultRepoFullName} when unset (a
     * GroupMapping source with no repository means "this repository").
     */
    public RepoSource resolve(String defaultRepoFullName) {
        return repository == null ? new RepoSource(defaultRepoFullName, filePath) : this;
    }

    @Override
    public String toString() {
        return "%s#%s".formatted(repository(), filePath());
    }
}
