package org.commonhaus.automation.hk.config;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.core.type.TypeReference;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** Source defining known projects, like PROJECTS.yaml */
@RegisterForReflection
public record ProjectSourceConfig(
        @JsonAlias("mail-domain") String mailDomain) {
    public static final TypeReference<Map<String, ProjectSourceConfig>> TYPE_REF = new TypeReference<>() {
    };
}
