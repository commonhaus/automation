package org.commonhaus.automation.hk.config;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.core.type.TypeReference;

/** Source defining known projects, like PROJECTS.yaml */
public record ProjectSourceConfig(
        @JsonAlias("mail-domain") String mailDomain) {
    public static final TypeReference<Map<String, ProjectSourceConfig>> TYPE_REF = new TypeReference<>() {
    };
}
