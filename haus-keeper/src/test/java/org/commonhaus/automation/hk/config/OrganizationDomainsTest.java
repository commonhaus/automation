package org.commonhaus.automation.hk.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;

import org.commonhaus.automation.ContextService;
import org.junit.jupiter.api.Test;

public class OrganizationDomainsTest {

    @Test
    void testYamlDeserialization() throws Exception {
        String yaml = """
                projects:
                  one:
                    projectRepository: commonhaus/project-one
                    domainAssociation:
                      - one.org
                  two:
                    projectRepository: commonhaus/project-two
                    domainAssociation:
                      - two.org
                      - two.com
                  three:
                    projectRepository: commonhaus/project-three
                """;

        OrganizationDomains parsed = ContextService.yamlMapper.readValue(yaml, OrganizationDomains.class);

        assertThat(parsed.projects()).hasSize(3);
        assertThat(parsed.projects().get("one").domainAssociation()).containsExactly("one.org");
        assertThat(parsed.projects().get("two").domainAssociation()).containsExactlyInAnyOrder("two.org", "two.com");
        // null/absent domainAssociation defaults to empty list, not null
        assertThat(parsed.projects().get("three").domainAssociation()).isEmpty();
    }

    @Test
    void testRegroupOneToOne() {
        Map<String, OrganizationDomains.ProjectDomains> raw = Map.of(
                "one", new OrganizationDomains.ProjectDomains("commonhaus/project-one", java.util.List.of("one.org")),
                "two", new OrganizationDomains.ProjectDomains("commonhaus/project-two", java.util.List.of("two.org")));
        OrganizationDomains orgDomains = new OrganizationDomains(raw);

        Map<String, Set<String>> regrouped = orgDomains.regroup(name -> name);

        assertThat(regrouped).hasSize(2);
        assertThat(regrouped.get("one")).containsExactly("one.org");
        assertThat(regrouped.get("two")).containsExactly("two.org");
    }

    @Test
    void testRegroupCollapsesMultipleEntriesOntoSameProjectName() {
        // two raw entries that both derive to the same coarser project name ("foo")
        Map<String, OrganizationDomains.ProjectDomains> raw = Map.of(
                "project-foo", new OrganizationDomains.ProjectDomains(
                        "commonhaus/project-foo", java.util.List.of("foo.org")),
                "foo-assets", new OrganizationDomains.ProjectDomains(
                        "commonhaus/foo-assets", java.util.List.of("foo.com")));
        OrganizationDomains orgDomains = new OrganizationDomains(raw);

        Map<String, Set<String>> regrouped = orgDomains.regroup(name -> "foo");

        assertThat(regrouped).hasSize(1);
        assertThat(regrouped.get("foo")).containsExactlyInAnyOrder("foo.org", "foo.com");
    }

    @Test
    void testRegroupHandlesNullAndAbsentDomainAssociation() {
        Map<String, OrganizationDomains.ProjectDomains> raw = Map.of(
                "one", new OrganizationDomains.ProjectDomains("commonhaus/project-one", null),
                "two", new OrganizationDomains.ProjectDomains("commonhaus/project-two", java.util.List.of("two.org")));
        OrganizationDomains orgDomains = new OrganizationDomains(raw);

        Map<String, Set<String>> regrouped = orgDomains.regroup(name -> name);

        assertThat(regrouped).hasSize(2);
        assertThat(regrouped.get("one")).isEmpty();
        assertThat(regrouped.get("two")).containsExactly("two.org");
    }

    @Test
    void testRegroupHandlesNullMapEntryValue() throws Exception {
        // A project entry with an empty/absent YAML body deserializes to a null
        // map value (not a ProjectDomains with null fields); regroup() must treat
        // that as "no domains for this project" rather than throwing an NPE.
        String yaml = """
                projects:
                  one:
                    domainAssociation:
                      - one.org
                  placeholder:
                """;

        OrganizationDomains parsed = ContextService.yamlMapper.readValue(yaml, OrganizationDomains.class);
        assertThat(parsed.projects().get("placeholder")).isNull();

        Map<String, Set<String>> regrouped = parsed.regroup(name -> name);

        assertThat(regrouped).hasSize(2);
        assertThat(regrouped.get("one")).containsExactly("one.org");
        assertThat(regrouped.get("placeholder")).isEmpty();
    }

    @Test
    void testEmptyOrganizationDomains() {
        OrganizationDomains empty = new OrganizationDomains(null);
        assertThat(empty.projects()).isEmpty();
        assertThat(empty.regroup(name -> name)).isEmpty();
    }
}
