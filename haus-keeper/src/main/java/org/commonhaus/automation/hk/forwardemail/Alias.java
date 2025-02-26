package org.commonhaus.automation.hk.forwardemail;

import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
public class Alias {
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    public String id;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    public AliasDomain domain;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    public String created_at;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    public String updated_at;

    public String name;
    public String description;
    public boolean is_enabled;

    public Set<String> recipients;

    public boolean has_recipient_verification;
    public Set<String> verified_recipients;

    @Override
    public String toString() {
        return "Alias [name=" + name + ", is_enabled=" + is_enabled + ", id=" + id
                + ", has_recipient_verification=" + has_recipient_verification + ", recipients=" + recipients
                + ", created_at=" + created_at + ", updated_at=" + updated_at + "]";
    }

    public boolean isDirty(String description, Set<String> recipients) {
        return !Objects.equals(this.recipients, recipients)
                || !Objects.equals(this.description, description);
    }

    public static class AliasDomain {
        public String name;
        public String id;

        public AliasDomain() {
        }

        // present in API responses
        // com.fasterxml.jackson.databind.exc.MismatchedInputException:
        // Cannot construct instance of `org.commonhaus.automation.hk.forwardemail.Alias$AliasDomain` (although at least one Creator exists):
        // no String-argument constructor/factory method to deserialize from String value ('6579da3c730fe30d10910be6')
        AliasDomain(String id) {
            this.id = id;
        }

        @Override
        public String toString() {
            return "AliasDomain [name=" + name + ", id=" + id + "]";
        }
    }
}
