package org.commonhaus.automation.hm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.commonhaus.automation.hm.TeamOrgValidator.Kind;
import org.commonhaus.automation.hm.TeamOrgValidator.Violation;
import org.junit.jupiter.api.Test;

public class TeamOrgValidatorTest {

    static final List<String> ORGS = List.of("test-org", "https://github.com/other-org");

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
}
