package org.commonhaus.automation.hm.github;

import java.util.Optional;

import org.commonhaus.automation.hm.DomainMonitorTest;
import org.commonhaus.automation.hm.config.ContactConfig;
import org.commonhaus.automation.hm.config.ManagerBotConfig.NamecheapConfig;
import org.commonhaus.automation.hm.namecheap.models.ContactInfo;
import org.commonhaus.automation.hm.namecheap.models.DomainContacts;

public class TestNamecheapConfig implements NamecheapConfig {

    DomainContacts contacts;

    public TestNamecheapConfig(DomainContacts contacts) {
        this.contacts = contacts;

    }

    @Override
    public String url() {
        throw new UnsupportedOperationException("Unimplemented method 'url'");
    }

    @Override
    public String username() {
        throw new UnsupportedOperationException("Unimplemented method 'username'");
    }

    @Override
    public String apiKey() {
        throw new UnsupportedOperationException("Unimplemented method 'apiKey'");
    }

    @Override
    public String ipv4Addr() {
        throw new UnsupportedOperationException("Unimplemented method 'ipv4Addr'");
    }

    @Override
    public String workflowRepository() {
        return DomainMonitorTest.PRIMARY.repoFullName();
    }

    @Override
    public String workflowName() {
        return "domain-list-update.yml";
    }

    @Override
    public ContactConfig registrant() {
        return new TestContactConfig(contacts.registrant());
    }

    @Override
    public Optional<ContactConfig> tech() {
        if (contacts.tech() == null) {
            return Optional.empty();
        }
        return Optional.of(new TestContactConfig(contacts.tech()));
    }

    @Override
    public Optional<ContactConfig> billing() {
        if (contacts.auxBilling() == null) {
            return Optional.empty();
        }
        return Optional.of(new TestContactConfig(contacts.auxBilling()));
    }

    public static class TestContactConfig implements ContactConfig {
        private final ContactInfo contactInfo;

        public TestContactConfig(ContactInfo contactInfo) {
            this.contactInfo = contactInfo;
        }

        @Override
        public String firstName() {
            return contactInfo.firstName();
        }

        @Override
        public String lastName() {
            return contactInfo.lastName();
        }

        @Override
        public String address1() {
            return contactInfo.address1();
        }

        @Override
        public String city() {
            return contactInfo.city();
        }

        @Override
        public String stateProvince() {
            return contactInfo.stateProvince();
        }

        @Override
        public String postalCode() {
            return contactInfo.postalCode();
        }

        @Override
        public String country() {
            return contactInfo.country();
        }

        @Override
        public String phone() {
            return contactInfo.phone();
        }

        @Override
        public String emailAddress() {
            return contactInfo.emailAddress();
        }

        @Override
        public Optional<String> organization() {
            return contactInfo.organization();
        }

        @Override
        public Optional<String> jobTitle() {
            return contactInfo.jobTitle();
        }

        @Override
        public Optional<String> address2() {
            return contactInfo.address2();
        }

        @Override
        public Optional<String> phoneExt() {
            return contactInfo.phoneExt();
        }

        @Override
        public Optional<String> fax() {
            return contactInfo.fax();
        }
    }
}
