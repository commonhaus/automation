package org.commonhaus.automation.admin.forwardemail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@Disabled
public class ForwardEmailTest {

    @RestClient
    ForwardEmailClient forwardEmailClient;

    @Test
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
    public void testQueryAliases() {
        Set<Alias> aliases = forwardEmailClient.getAliases("hibernate.org");
        for (Alias alias : aliases) {
            System.out.println("Alias: " + alias);
        }
    }

    @Test
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

                forwardEmailClient.createAliases("hibernate.org", newAlias);
            }
        }
    }
}
