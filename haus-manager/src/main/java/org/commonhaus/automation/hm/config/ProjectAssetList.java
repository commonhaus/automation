package org.commonhaus.automation.hm.config;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.fasterxml.jackson.annotation.JsonValue;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class ProjectAssetList {
    @JsonValue
    Map<String, ProjectAssets> allAssets;

    Map<String, ProjectAssets> allAssets() {
        return allAssets == null ? Map.of() : allAssets;
    }

    public ProjectAssets assetsForProject(String projectName) {
        return allAssets.get(projectName);
    }

    /**
     * Create a map with domains as a key.
     * Ideally, sets are all of length 1.
     */
    public Map<String, Set<String>> expectedDomains() {
        return buildAssetIndex(ProjectAssets::domainAssociation);
    }

    /**
     * Create a map with GH Organizations as a key.
     * Ideally, sets are all of length 1.
     */
    public Map<String, Set<String>> expectedOrganizations() {
        return buildAssetIndex(ProjectAssets::githubOrganizations);
    }

    private Map<String, Set<String>> buildAssetIndex(
            Function<ProjectAssets, Collection<String>> assetExtractor) {
        Map<String, Set<String>> assetOwners = new HashMap<>();
        for (var entry : allAssets.entrySet()) {
            var project = entry.getKey();
            var assets = assetExtractor.apply(entry.getValue());
            for (var asset : assets) {
                assetOwners.computeIfAbsent(asset, k -> new HashSet<>())
                        .add(project);
            }
        }
        return assetOwners;
    }

    @Override
    public String toString() {
        return "ProjectAssetList{%d projects}".formatted(allAssets.size());
    }

    /**
     * Configuration for an individual project's assets.
     *
     * @param domainAssociation   List of domains associated with this project
     * @param githubOrganizations List of GitHub organizations for this project
     */
    @RegisterForReflection
    public record ProjectAssets(
            List<String> domainAssociation,
            List<String> githubOrganizations) {

        @Override
        public List<String> domainAssociation() {
            return domainAssociation == null ? List.of() : domainAssociation;
        }

        @Override
        public List<String> githubOrganizations() {
            return githubOrganizations == null ? List.of() : githubOrganizations;
        }
    }
}
