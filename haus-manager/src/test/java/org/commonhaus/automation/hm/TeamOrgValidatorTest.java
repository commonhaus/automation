package org.commonhaus.automation.hm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.commonhaus.automation.ContextService;
import org.commonhaus.automation.hm.TeamOrgValidator.Kind;
import org.commonhaus.automation.hm.TeamOrgValidator.Result;
import org.commonhaus.automation.hm.TeamOrgValidator.Violation;
import org.commonhaus.automation.hm.config.ProjectConfig;
import org.junit.jupiter.api.Test;

public class TeamOrgValidatorTest {

    static final List<String> ORGS = List.of("test-org", "https://github.com/other-org");

    static ProjectConfig loadProjectConfig(String path) throws IOException {
        return ContextService.yamlMapper.readValue(Files.readString(Path.of(path)), ProjectConfig.class);
    }

    @Test
    void validOrgMatchesBareDeclaration() {
        assertThat(TeamOrgValidator.validate("test-org/cf-council", ORGS)).isNull();
    }

    @Test
    void validOrgMatchesUrlFormDeclaration() {
        assertThat(TeamOrgValidator.validate("other-org/teamB", ORGS)).isNull();
    }

    @Test
    void orgMismatchIsAViolation() {
        Violation v = TeamOrgValidator.validate("commonhaus-test/bad team!", ORGS);
        assertThat(v).isNotNull();
        assertThat(v.kind()).isEqualTo(Kind.ORG_MISMATCH);
        assertThat(v.qualifiedTeamName()).isEqualTo("commonhaus-test/bad team!");
    }

    @Test
    void blankValueIsMalformed() {
        Violation v = TeamOrgValidator.validate("", ORGS);
        assertThat(v).isNotNull();
        assertThat(v.kind()).isEqualTo(Kind.MALFORMED);
    }

    @Test
    void trailingSlashWithEmptyTeamSideIsMalformed() {
        Violation v = TeamOrgValidator.validate("commonhaus-test/", ORGS);
        assertThat(v).isNotNull();
        assertThat(v.kind()).isEqualTo(Kind.MALFORMED);
    }

    @Test
    void leadingSlashWithEmptyOrgSideIsMalformed() {
        Violation v = TeamOrgValidator.validate("/some-team", ORGS);
        assertThat(v).isNotNull();
        assertThat(v.kind()).isEqualTo(Kind.MALFORMED);
    }

    @Test
    void noDeclaredOrganizationsTreatsEveryReferenceAsMismatch() {
        Violation v = TeamOrgValidator.validate("test-org/cf-council", List.of());
        assertThat(v).isNotNull();
        assertThat(v.kind()).isEqualTo(Kind.ORG_MISMATCH);
    }

    @Test
    void orgMismatchFixtureProducesExpectedPushTargetAndSourceTeamViolations() throws IOException {
        ProjectConfig projectConfig = loadProjectConfig("src/test/resources/cf-haus-manager-team-org-mismatch.yml");

        Result result = TeamOrgValidator.validate(projectConfig, "test-org");

        assertThat(result.isEmpty()).isFalse();
        assertThat(result.pushTargetViolations()).extracting(Violation::qualifiedTeamName)
                .containsExactly("other-org/teamB");
        assertThat(result.pushTargetViolations()).extracting(Violation::kind)
                .containsExactly(Kind.ORG_MISMATCH);
        assertThat(result.sourceTeamViolations()).extracting(Violation::qualifiedTeamName)
                .containsExactly("other-org/teamA");
        assertThat(result.sourceTeamViolations()).extracting(Violation::kind)
                .containsExactly(Kind.ORG_MISMATCH);
    }

    @Test
    void malformedFixtureProducesBlankAndMismatchPushTargetViolations() throws IOException {
        ProjectConfig projectConfig = loadProjectConfig("src/test/resources/cf-haus-manager-team-malformed.yml");

        Result result = TeamOrgValidator.validate(projectConfig, "test-org");

        assertThat(result.pushTargetViolations()).extracting(Violation::qualifiedTeamName)
                .containsExactlyInAnyOrder("test-org/", "test-org/", "other-org/bad team!");
        assertThat(result.pushTargetViolations()).extracting(Violation::kind)
                .containsOnly(Kind.MALFORMED, Kind.ORG_MISMATCH);
        assertThat(result.sourceTeamViolations()).extracting(Violation::qualifiedTeamName)
                .containsExactly("other-org/bad team!");
    }

    @Test
    void noOrgsFixtureTreatsEveryReferencedTeamAsMismatch() throws IOException {
        ProjectConfig projectConfig = loadProjectConfig("src/test/resources/cf-haus-manager-team-no-orgs.yml");

        Result result = TeamOrgValidator.validate(projectConfig, "test-org");

        assertThat(result.pushTargetViolations()).extracting(Violation::qualifiedTeamName)
                .containsExactlyInAnyOrder("test-org/cf-council", "other-org/teamB");
        assertThat(result.sourceTeamViolations()).extracting(Violation::qualifiedTeamName)
                .containsExactly("other-org/teamA");
    }
}
