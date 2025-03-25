package org.commonhaus.automation.hk.data;

import static org.assertj.core.api.Assertions.assertThat;

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
}
