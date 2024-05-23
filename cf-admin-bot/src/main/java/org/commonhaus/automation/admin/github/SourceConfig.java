package org.commonhaus.automation.admin.github;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SourceConfig(
        String path,
        String repo,
        Map<String, SyncToTeams> sync,
        @JsonProperty("dry_run") Boolean dryRun) {

    public boolean performSync() {
        return sync() != null
                && path() != null
                && repo() != null;
    }

    public Boolean dryRun() {
        return dryRun != null && dryRun;
    }

    @Override
    public String toString() {
        return "SourceConfig{path='%s', repo='%s', sync=%s, dryRun=%s}"
                .formatted(path, repo, sync, dryRun);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof SourceConfig that))
            return false;
        return path.equals(that.path) && repo.equals(that.repo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, repo);
    }

    public record SyncToTeams(
            String field,
            List<String> teams,
            @JsonProperty("preserve_users") List<String> preserveUsers) {

        @Override
        public List<String> teams() {
            return teams == null ? List.of() : teams;
        }

        @Override
        public List<String> preserveUsers() {
            return preserveUsers == null ? List.of() : preserveUsers;
        }

        @Override
        public String field() {
            return field == null ? "login" : field;
        }

        @Override
        public String toString() {
            return "SyncToTeams{field='%s', teams=%s}"
                    .formatted(field, teams);
        }
    }
}
