package org.commonhaus.automation.hk.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.commonhaus.automation.hk.github.HausKeeperTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@GitHubAppTest
public class CommonhausUserTest extends HausKeeperTestBase {

    @AfterEach
    void noErrorMail() {
        assertNoErrorEmails();
    }

    @Override
    @BeforeEach
    protected void init() throws Exception {
        super.init();
        setupInstallationRepositories();
    }

    @Test
    void testGetCommonhausUserStatus() throws Exception {

        setUserManagementConfig();
        ctx.getStatusForRole("sponsor");

        CommonhausUser user = new CommonhausUser.Builder()
                .withId(12345)
                .withData(new CommonhausUserData())
                .build();

        assertThat(user.status()).isEqualTo(MemberStatus.UNKNOWN);

        Set<String> roles = Set.of("sponsor");
        boolean update = user.statusUpdateRequired(ctx, roles);
        assertThat(update).isTrue();
        user.updateMemberStatus(ctx, roles);
        assertThat(user.status()).isEqualTo(MemberStatus.SPONSOR);

        roles = Set.of("sponsor", "member", "egc");
        update = user.statusUpdateRequired(ctx, roles);
        assertThat(update).isTrue();
        user.updateMemberStatus(ctx, roles);
        assertThat(user.status()).isEqualTo(MemberStatus.COMMITTEE);
    }

    @Test
    void testMergeCommonhausUser() {
        // Create the first user with some history
        CommonhausUser user1 = new CommonhausUser.Builder()
                .withId(12345)
                .withData(new CommonhausUserData())
                .build();
        user1.data.projects().add("user1-project");
        user1.data.projects().add("other-project");
        user1.history.addAll(List.of(
                "2025-03-12T14:35:00Z Membership application accepted",
                "2025-03-18T09:51:00Z Sign attestation (email|fe-2024-05-31)"));

        // Create the second user with overlapping and new history
        CommonhausUser user2 = new CommonhausUser.Builder()
                .withId(12345)
                .withData(new CommonhausUserData())
                .build();
        user2.data.projects().add("user2-project");
        user2.data.projects().add("other-project");
        user2.history.addAll(List.of(
                "2025-03-18T09:51:00Z Sign attestation (email|fe-2024-05-31)", // Duplicate
                "2025-03-18T09:54:00Z Sign attestation (coc|cf-2024-06-07)",
                "2025-04-15T02:15:00Z Update user aliases for hibernate.org"));

        // Merge user2 into user1
        user1.merge(user2);

        // Validate the merged history
        assertThat(user1.history).containsExactly(
                "2025-03-12T14:35:00Z Membership application accepted",
                "2025-03-18T09:51:00Z Sign attestation (email|fe-2024-05-31)",
                "2025-03-18T09:54:00Z Sign attestation (coc|cf-2024-06-07)",
                "2025-04-15T02:15:00Z Update user aliases for hibernate.org");
        assertThat(user1.projects()).containsExactly(
                "other-project",
                "user1-project",
                "user2-project");
    }

    @Test
    void testYamlSerializationDeserialization() throws Exception {
        // Create a user with history
        CommonhausUser user = new CommonhausUser.Builder()
                .withId(12345)
                .withData(new CommonhausUserData())
                .build();
        user.history.addAll(List.of(
                "2025-03-12T14:35:00Z Membership application accepted",
                "2025-03-18T09:51:00Z Sign attestation (email|fe-2024-05-31)"));
        user.data.projects().add("test-project");
        user.data.projects().add("other-project");

        // Serialize to YAML
        String yaml = ctx.yamlMapper().writeValueAsString(user);

        // Deserialize back to object
        CommonhausUser deserializedUser = ctx.yamlMapper().readValue(yaml, CommonhausUser.class);

        // Validate the deserialized object
        assertThat(deserializedUser.id).isEqualTo(user.id);
        assertThat(deserializedUser.history).isEqualTo(user.history);
        assertThat(deserializedUser.projects()).isEqualTo(user.projects());
    }

    @Test
    void testGetCommonhausUserData() throws Exception {
        Path userFile = Path.of(UserPath.WITH_ATTESTATION.filename());
        CommonhausUser user = ctx.yamlMapper().readValue(userFile.toFile(), CommonhausUser.class);
        assertThat(user).isNotNull();
        assertThat(user.id).isEqualTo(156364140);
        assertThat(user.login).isEqualTo("commonhaus-bot");
        assertThat(user.data).isNotNull();
        assertThat(user.data.goodUntil).isNotNull();
        assertThat(user.data.goodUntil.dues).isEqualTo("2020-01-01");
    }

    @Test
    void testAliasesMatch() {
        CommonhausUser user = new CommonhausUser.Builder()
                .withId(12345)
                .withData(new CommonhausUserData())
                .build();

        user.services().forwardEmail().altAlias().addAll(List.of(
                "alias@otherdomain.com",
                "alias1@domain.com",
                "alias2@domain.com",
                "alias3@domain.com"));

        var expected = Set.of(
                "alias1@domain.com",
                "alias3@domain.com",
                "alias2@domain.com");

        // Case 1: Project mismatch (project not set); should return false
        boolean result = user.aliasesMatch("project", "domain.com", expected);
        assertThat(result).isFalse();

        // Case 2: Project and aliases match
        user.addProject("project");
        result = user.aliasesMatch("project", "domain.com", expected);
        assertThat(result).isTrue();

        // Case 3: Aliases mismatch - alias1 is missing from the user
        user.services().forwardEmail().altAlias().remove("alias1@domain.com");
        result = user.aliasesMatch("project", "domain.com", expected);
        assertThat(result).isFalse();

        // Update expected to match the current state
        expected = Set.of(
                "alias3@domain.com",
                "alias2@domain.com");

        // Case 4: Aliases mismatch - alias4 is extra in the user
        user.services().forwardEmail().altAlias().add("alias4@domain.com");
        result = user.aliasesMatch("project", "domain.com", expected);
        assertThat(result).isFalse();
    }
}
