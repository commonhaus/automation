package org.commonhaus.automation.hm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.commonhaus.automation.hm.OrganizationManager.OrganizationConfigState;
import org.commonhaus.automation.hm.ProjectManager.ProjectConfigState;
import org.commonhaus.automation.hm.config.OrganizationConfig;
import org.commonhaus.automation.hm.config.ProjectConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@GitHubAppTest
public class TeamConflictResolverTest extends HausManagerTestBase {
    static final AtomicInteger proj1Refresh = new AtomicInteger(0);
    static final AtomicInteger proj2Refresh = new AtomicInteger(0);

    TeamConflictResolver resolver;

    OrganizationConfigState orgState;
    ProjectConfigState project1State;
    ProjectConfigState project2State;

    @BeforeEach
    @Override
    void setup() throws IOException {
        super.setup();
        resolver = new TeamConflictResolver();
        resolver.ctx = appContextService;
        resolver.updateQueue = updateQueue;

        String orgString = """
                {
                    "teamMembership": [
                        {
                            "source": {
                                "repository": "public-org/source",
                                "filePath": "CONTACTS.yaml"
                            },
                            "defaults": {
                                "field": "login",
                                "preserveUsers": ["test-bot"]
                            },
                            "pushMembers": {
                                "cf-council":{
                                    "teams":[
                                        "test-org/cf-council",
                                        "test-org/admin"
                                    ]
                                },
                                "egc": {
                                    "teams": ["test-org/team-quorum"]
                                }
                            }
                        }
                    ],
                    "emailNotifications": {
                        "errors": ["org@test.org"]
                    }
                }
                """;

        String project1 = """
                {
                    "teamMembership": [
                        {
                            "source": {
                                "repository": "public-org/source",
                                "filePath": "signatories.yaml"
                            },
                            "mapPointer": "/signatories",
                            "defaults": {
                                "field": "login",
                                "preserveUsers": ["test-bot"]
                            },
                            "pushMembers": {
                                "active":{
                                    "teams":[
                                        "test-org/cf-council",
                                        "test-org/active-users"
                                    ]
                                }
                            }
                        }
                    ],
                    "emailNotifications": {
                        "errors": ["proj-1@test.org"]
                    }
                }
                """;

        String project2 = """
                {
                    "teamMembership": [
                        {
                            "source": {
                                "repository": "public-org/source",
                                "filePath": "other.yaml"
                            },
                            "defaults": {
                                "field": "login",
                                "preserveUsers": ["test-bot"]
                            },
                            "pushMembers": {
                                "active":{
                                    "teams":[
                                        "test-org/project-2",
                                        "test-org/active-users"
                                    ]
                                }
                            }
                        }
                    ],
                    "emailNotifications": {
                        "errors": ["proj-2@test.org"]
                    }
                }
                """;

        OrganizationConfig orgConfig = objectMapper.readValue(orgString, OrganizationConfig.class);
        ProjectConfig project1Config = objectMapper.readValue(project1, ProjectConfig.class);
        ProjectConfig project2Config = objectMapper.readValue(project2, ProjectConfig.class);

        orgState = new OrganizationConfigState(PRIMARY.installId(), PRIMARY.repoFullName(), orgConfig);

        project1State = new ProjectConfigState("project1",
                () -> {
                    proj1Refresh.incrementAndGet();
                },
                PROJECT_ORG.repoFullName(), PROJECT_ORG.installId(),
                project1Config);

        project2State = new ProjectConfigState("project2",
                () -> {
                    proj2Refresh.incrementAndGet();
                },
                PROJECT_TWO.repoFullName(), PROJECT_TWO.installId(),
                project2Config);

        proj1Refresh.set(0);
        proj2Refresh.set(0);
    }

    @Test
    void testOrgTakesPrecedenceOverProject() {
        // Setup: Project registers first for "test-org/cf-council"
        System.out.println("=== Project1 target teams: " + project1State.targetTeams());
        resolver.registerProjectTeams(project1State);

        waitForQueue();
        assertThat(proj1Refresh.get()).isEqualTo(0);
        assertThat(proj2Refresh.get()).isEqualTo(0);
        System.out.println("=== After project registration, refresh count: " + proj1Refresh.get());

        // Org takes over the team "test-org/cf-council"
        System.out.println("=== Org target teams: " + orgState.teams());
        resolver.registerOrgTeams(orgState);

        // Verify:
        // Project should be refreshed to stop tracking "test-org/cf-council"
        // Email should be sent to proj-1@test.org

        waitForQueue();
        System.out.println("=== After org registration, refresh count: " + proj1Refresh.get());

        assertThat(proj1Refresh.get()).isEqualTo(1);
        assertThat(proj2Refresh.get()).isEqualTo(0);
        assertThat(mailbox.getMailsSentTo("proj-1@test.org")).hasSize(1);
        mailbox.clear();

        // Org releases a team that project1 wants
        resolver.releaseOrgTeams(Set.of("test-org/cf-council"));

        waitForQueue();
        System.out.println("=== After org team release, refresh count: " + proj1Refresh.get());

        // Verify:
        // Project should be refreshed to resume tracking "test-org/cf-council"
        assertThat(proj1Refresh.get()).isEqualTo(2);
        assertThat(proj2Refresh.get()).isEqualTo(0);
        assertThat(mailbox.getMailsSentTo("proj-1@test.org")).isEmpty();
    }

    @Test
    void testProjectConflictDetection() {
        // 1. Project1 registers first for teams: test-org/cf-council, test-org/active-users
        // 2. Project2 registers second for teams: test-org/project-2, test-org/active-users

        // Expected behavior:
        // - Project1 should get both teams initially (no conflicts)
        // - When Project2 registers:
        // - test-org/project-2 - no conflict, Project2 gets it
        // - test-org/active-users - conflict! Both projects want it
        // - Project1 should get refreshed (to learn about the conflict)
        // - Project2 should NOT get refreshed (it's the registering project)
        // - Both projects should get conflict emails
        // - Project2 should get a filtered list back that doesn't include test-org/active-users

        // Project1 registers first for: test-org/cf-council, test-org/active-users
        System.out.println("=== Project1 target teams: " + project1State.targetTeams());
        resolver.registerProjectTeams(project1State);

        waitForQueue();
        System.out.println("=== After project1 registration, refresh counts: p1=" + proj1Refresh.get() + ", p2="
                + proj2Refresh.get());
        assertThat(proj1Refresh.get()).isEqualTo(0); // No refresh for initial registration
        assertThat(proj2Refresh.get()).isEqualTo(0);

        // Project2 registers second for: test-org/project-2, test-org/active-users
        // (CONFLICT!)
        System.out.println("=== Project2 target teams: " + project2State.targetTeams());
        resolver.registerProjectTeams(project2State);
        System.out.println("=== Project2 BLOCKED target teams: " + project2State.blockedTeams());

        waitForQueue();
        System.out.println("=== After project2 registration, refresh counts: p1=" + proj1Refresh.get() + ", p2="
                + proj2Refresh.get());

        // Expected:
        // - Project1 should be refreshed (to learn about conflict on
        // test-org/active-users)
        // - Project2 should NOT be refreshed (it's the registering project)
        assertThat(proj1Refresh.get()).isEqualTo(1); // Project1 learns about conflict
        assertThat(proj2Refresh.get()).isEqualTo(0); // Project2 doesn't refresh itself

        // Both projects should get conflict emails
        assertThat(mailbox.getMailsSentTo("proj-1@test.org")).hasSize(1);
        assertThat(mailbox.getMailsSentTo("proj-2@test.org")).hasSize(1);
        mailbox.clear();

        resolver.releaseProjectTeams(project1State, Set.of("test-org/active-users"));
        waitForQueue();
        System.out.println("=== After proj1 team release, refresh counts: p1=" + proj1Refresh.get() + ", p2="
                + proj2Refresh.get());
        assertThat(proj1Refresh.get()).isEqualTo(1); // Project1 does not refresh (it is driving release)
        assertThat(proj2Refresh.get()).isEqualTo(1); // Project2 is refreshed
    }

}
