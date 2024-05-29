package org.commonhaus.automation.admin.forwardemail;

import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

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
        return "Alias [name=" + name + ", is_enabled=" + is_enabled
                + ", has_recipient_verification=" + has_recipient_verification + ", recipients=" + recipients
                + ", created_at=" + created_at + ", updated_at=" + updated_at + "]";
    }

    public static class AliasDomain {
        public String name;
        String id;

        @Override
        public String toString() {
            return "AliasDomain [name=" + name + ", id=" + id + "]";
        }
    }
}
