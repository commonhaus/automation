package org.commonhaus.automation.admin.config;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonAlias;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record TeamSourceConfig(
        String path,
        String repo,
        Defaults defaults,
        Map<String, SyncToTeams> sync,
        @JsonAlias("dry_run") Boolean dryRun) {

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
        if (!(o instanceof TeamSourceConfig that))
            return false;
        return path.equals(that.path) && repo.equals(that.repo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, repo);
    }

    public record Defaults(
            String field,
            @JsonAlias("preserve_users") List<String> preserveUsers) {
        @Override
        public String field() {
            return field == null ? "login" : field;
        }

        @Override
        public List<String> preserveUsers() {
            return preserveUsers == null ? List.of() : preserveUsers;
        }

        @Override
        public String toString() {
            return "SyncToTeams{field='%s', preserveUsers=%s}"
                    .formatted(field, preserveUsers);
        }
    }

    public record SyncToTeams(
            String field,
            List<String> teams,
            @JsonAlias("preserve_users") List<String> preserveUsers) {

        @Override
        public List<String> teams() {
            return teams == null ? List.of() : teams;
        }

        @Override
        public List<String> preserveUsers() {
            return preserveUsers == null ? List.of() : preserveUsers;
        }

        public List<String> preserveUsers(Defaults defaults) {
            return preserveUsers == null ? defaults.preserveUsers() : preserveUsers;
        }

        @Override
        public String field() {
            return field == null ? "login" : field;
        }

        public String field(Defaults defaults) {
            return field == null ? defaults.field() : field;
        }

        @Override
        public String toString() {
            return "SyncToTeams{field='%s', preserveUsers=%s, teams=%s}"
                    .formatted(field, preserveUsers, teams);
        }
    }
}
