package org.commonhaus.automation.admin.forwardemail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import jakarta.inject.Inject;

import org.commonhaus.automation.admin.github.AppContextService;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class ForwardEmailTest {

    @RestClient
    ForwardEmailClient forwardEmailClient;

    @Inject
    AppContextService ctx;

    @Test
    @Disabled
    public void testQueryDomains() {
        Set<Domain> domains = forwardEmailClient.getDomains();
        for (Domain domain : domains) {
            System.out.println("Domain: " + domain.name);
            // Set<Alias> aliases = forwardEmailClient.getAliases(domain.name);
            // for (Alias alias : aliases) {
            //     System.out.println("  Alias: " + alias.name);
            // }
        }
    }

    @Test
    @Disabled
    public void testQueryAliases() {
        // Set<Alias> aliases = forwardEmailClient.findAliasByName("commonhaus.dev", "ebullient");
        // for (Alias alias : aliases) {
        //     System.out.println("Alias: " + alias);
        // }

        // Map<String, Alias> aliases = ctx.getAliases(List.of("ebullient@commonhaus.dev"), false);
        // System.out.println("Alias: " + aliases);

        // Map<String, Set<String>> updates = aliases.entrySet().stream()
        //         .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue().recipients));
        // aliases = ctx.setRecipients("Erin Schnabel", updates);

        Alias alias = forwardEmailClient.getAlias("commonhaus.dev", "automation");
        System.out.println("Alias: " + alias);

        // ctx.generatePassword("automation@commonhaus.dev");

        // forwardEmailClient.updateAlias("commonhaus.dev", alias.id, alias);
    }

    @Test
    @Disabled
    public void testAddAliases() throws IOException {
        String data = Files.readString(Path.of("aliases.csv"));
        String[] lines = data.split("\n");
        for (String line : lines) {
            if (line.contains("Ready")) {
                String[] parts = line.split(",");
                Alias newAlias = new Alias();
                newAlias.name = parts[0].replace("@hibernate.org", "");
                newAlias.recipients = Set.of(parts[1]);
                newAlias.is_enabled = true;

                forwardEmailClient.createAlias("hibernate.org", newAlias);
            }
        }
    }
}
