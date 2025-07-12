package org.commonhaus.automation.opencollective;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import jakarta.inject.Inject;

import org.commonhaus.automation.ContextService;
import org.commonhaus.automation.PackagedException;
import org.commonhaus.automation.github.context.TestBotConfig;
import org.commonhaus.automation.opencollective.OpenCollectiveData.Account;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;

@QuarkusTest
public class OpenCollectiveQueryTest {

    @Inject
    TestBotConfig testConfig;

    @BeforeEach
    public void reset() {
        testConfig.ocConfigEnabled(true);
    }

    @Test
    @Disabled("Run manually if something changes. not worth mocking")
    public void testGraphQLQuery() throws Exception {
        ContextService contextService = Mockito.mock(ContextService.class);
        try {
            OpenCollectiveQueryContext qc = new OpenCollectiveQueryContext(contextService, testConfig);
            DynamicGraphQLClient client = qc.getGraphQLClient();
            assertThat(client).isNotNull();

            List<Account> contributors = qc.getContributors();
            assertThat(qc.hasErrors()).isFalse();
            assertThat(contributors).isNotNull();
            assertThat(contributors).isNotEmpty();
            contributors.stream().forEach(System.out::println);

        } catch (PackagedException e) {
            // Handle the exception
            System.out.println(e.details());
            throw e;
        }
    }
}
