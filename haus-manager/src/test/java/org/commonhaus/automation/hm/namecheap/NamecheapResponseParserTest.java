package org.commonhaus.automation.hm.namecheap;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.commonhaus.automation.hm.namecheap.models.DomainRecord;
import org.junit.jupiter.api.Test;

class NamecheapResponseParserTest {

    @Test
    void parseDomainInfoResponseExtractsSafeOperationalFields() {
        DomainRecord info = NamecheapResponseParser.parseDomainInfoResponse(SAMPLE_DOMAIN_INFO_XML);

        assertThat(info.name()).isEqualTo("example.org");
        assertThat(info.expires()).isEqualTo(LocalDate.of(2030, 1, 2));
        assertThat(info.isExpired()).isFalse();
        assertThat(info.isLocked()).isTrue();
        assertThat(info.autoRenew()).isTrue();
        assertThat(info.isOurDNS()).isTrue();
    }

    @Test
    void parseDomainInfoResponseIgnoresContactData() {
        DomainRecord info = NamecheapResponseParser.parseDomainInfoResponse(SAMPLE_DOMAIN_INFO_XML);

        assertThat(info)
                .extracting(DomainRecord::name, DomainRecord::expires, DomainRecord::isExpired,
                        DomainRecord::isLocked, DomainRecord::autoRenew, DomainRecord::isOurDNS)
                .containsExactly("example.org", LocalDate.of(2030, 1, 2), false, true, true, true);
    }

    @Test
    void redactDomainInfoXmlMasksContactFieldsButKeepsOperationalFields() {
        String redacted = NamecheapResponseParser.redactDomainInfoXml(SAMPLE_DOMAIN_INFO_XML);

        assertThat(redacted)
                .contains("example.org")
                .contains("01/02/2030")
                .contains("true")
                .contains("Example Org")
                .contains("Platform Lead")
                .doesNotContain("Jane")
                .doesNotContain("Doe")
                .doesNotContain("jane@example.org")
                .doesNotContain("123 Main St")
                .doesNotContain("Springfield")
                .doesNotContain("+1.5551234567");
    }

    private static final String SAMPLE_DOMAIN_INFO_XML = """
            <?xml version="1.0" encoding="utf-8"?>
            <ApiResponse Status="OK">
              <CommandResponse>
                <DomainGetInfoResult DomainName="example.org" Status="ok" IsOurDNS="true">
                  <DomainDetails CreatedDate="01/02/2020" ExpiredDate="01/02/2030" NumYears="1"/>
                  <Whoisguard Enabled="true"/>
                  <Modificationrights All="true">
                    <Rights IsLocked="true" AutoRenew="true" IsExpired="false"/>
                  </Modificationrights>
                  <RegistrantContact>
                    <FirstName>Jane</FirstName>
                    <LastName>Doe</LastName>
                    <OrganizationName>Example Org</OrganizationName>
                    <JobTitle>Platform Lead</JobTitle>
                    <Address1>123 Main St</Address1>
                    <City>Springfield</City>
                    <StateProvince>IL</StateProvince>
                    <PostalCode>62701</PostalCode>
                    <Country>US</Country>
                    <Phone>+1.5551234567</Phone>
                    <EmailAddress>jane@example.org</EmailAddress>
                  </RegistrantContact>
                </DomainGetInfoResult>
              </CommandResponse>
            </ApiResponse>
            """;
}
