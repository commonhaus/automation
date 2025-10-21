package org.commonhaus.automation.hk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import jakarta.inject.Inject;

import org.commonhaus.automation.hk.config.ProjectAliasMapping;
import org.commonhaus.automation.hk.config.ProjectAliasMapping.UserAliasList;
import org.commonhaus.automation.hk.github.AppContextService;
import org.commonhaus.automation.hk.github.HausKeeperTestBase;
import org.junit.jupiter.api.Test;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@GitHubAppTest
public class ProjectAliasManagerTest extends HausKeeperTestBase {

    @Inject
    AppContextService ctx;

    @Test
    void testDomainConfiguration() {
        // Single domain (backward compatibility)
        ProjectAliasMapping singleDomain = new ProjectAliasMapping(
                "example.com",
                null,
                Set.of(new UserAliasList("user1", Set.of("alias@example.com"))),
                null);
        assertThat(singleDomain.domains()).containsExactly("example.com");
        assertThat(singleDomain.isEnabled()).isTrue();

        // Multiple domains
        Set<String> domains = Set.of("example.com", "example.org");
        ProjectAliasMapping multiDomain = new ProjectAliasMapping(
                null,
                domains,
                Set.of(new UserAliasList("user1", Set.of("alias@example.com"))),
                null);
        assertThat(multiDomain.domains()).containsExactlyInAnyOrder("example.com", "example.org");
        assertThat(multiDomain.isEnabled()).isTrue();

        // Disabled: empty/null domains or mappings
        Set<String> emptyDomains = Set.of();
        assertThat(new ProjectAliasMapping(null, emptyDomains, Set.of(new UserAliasList("u", Set.of("a@b.com"))), null)
                .isEnabled()).isFalse();
        assertThat(new ProjectAliasMapping(null, domains, Set.of(), null).isEnabled()).isFalse();
    }

    @Test
    void testAliasValidation() {
        Set<String> singleDomain = Set.of("example.com");
        Set<String> multiDomain = Set.of("example.com", "example.org");

        // Valid cases
        assertThat(new UserAliasList("user1", Set.of("a@example.com", "b@example.com"))
                .isValid(singleDomain)).isTrue();
        assertThat(new UserAliasList("user2", Set.of("a@example.com", "b@example.org"))
                .isValid(multiDomain)).isTrue();

        // Invalid: wrong domain
        assertThat(new UserAliasList("user3", Set.of("a@example.com", "b@wrong.com"))
                .isValid(singleDomain)).isFalse();
        assertThat(new UserAliasList("user4", Set.of("a@wrong.com"))
                .isValid(multiDomain)).isFalse();

        // Invalid: bad login or aliases
        assertThat(new UserAliasList(null, Set.of("a@example.com")).isValid(singleDomain)).isFalse();
        assertThat(new UserAliasList("", Set.of("a@example.com")).isValid(singleDomain)).isFalse();
        assertThat(new UserAliasList("user", Set.of()).isValid(singleDomain)).isFalse();
        assertThat(new UserAliasList("user", null).isValid(singleDomain)).isFalse();
    }

    @Test
    void testYamlDeserializationBackwardCompatibility() throws Exception {
        // Test old format: singular "domain"
        String oldFormatYaml = """
                domain: example.com
                userMapping:
                  - login: user1
                    aliases:
                      - alias1@example.com
                      - alias2@example.com
                """;

        ProjectAliasMapping oldFormat = ctx.yamlMapper().readValue(oldFormatYaml, ProjectAliasMapping.class);

        assertThat(oldFormat.domains()).containsExactly("example.com");
        assertThat(oldFormat.userMapping()).hasSize(1);
        assertThat(oldFormat.isEnabled()).isTrue();

        // Test new format: plural "domains"
        String newFormatYaml = """
                domains:
                  - example.com
                  - example.org
                userMapping:
                  - login: user1
                    aliases:
                      - alias1@example.com
                      - alias2@example.org
                """;

        ProjectAliasMapping newFormat = ctx.yamlMapper().readValue(newFormatYaml, ProjectAliasMapping.class);
        assertThat(newFormat.domains()).containsExactlyInAnyOrder("example.com", "example.org");
        assertThat(newFormat.userMapping()).hasSize(1);
        assertThat(newFormat.isEnabled()).isTrue();

        // Verify validation works correctly for both
        UserAliasList userFromOld = oldFormat.userMapping().iterator().next();
        assertThat(userFromOld.isValid(oldFormat.domains())).isTrue();

        UserAliasList userFromNew = newFormat.userMapping().iterator().next();
        assertThat(userFromNew.isValid(newFormat.domains())).isTrue();
    }
}
