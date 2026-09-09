package org.commonhaus.automation.config;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record RepoSource(String repository, String filePath) {
    public boolean isEmpty() {
        return filePath == null || filePath.isBlank();
    }

    /**
     * Resolve this source against the repository that owns it, defaulting
     * {@code repository} to {@code defaultRepoFullName} when unset (a
     * GroupMapping source with no repository means "this repository").
     */
    public RepoSource resolve(String defaultRepoFullName) {
        return repository == null || repository.isBlank() ? new RepoSource(defaultRepoFullName, filePath) : this;
    }

    /**
     * Compare this (already-resolved) source against a possibly-unresolved
     * {@code other} (e.g. from config), resolving it against
     * {@code defaultRepoFullName} before comparing.
     */
    public boolean equalsResolved(RepoSource other, String defaultRepoFullName) {
        return other != null && this.equals(other.resolve(defaultRepoFullName));
    }

    @Override
    public String toString() {
        return "%s#%s".formatted(repository(), filePath());
    }
}
