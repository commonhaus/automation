package org.commonhaus.automation.admin.forwardemail;

import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
public class Alias {
    public boolean has_imap;
    public AliasOwner user;
    public AliasDomain domain;
    public String name;
    public boolean is_enabled;
    public boolean has_recipient_verification;
    public Set<String> recipients;
    public String id;
    public String object;
    public String created_at;
    public String updated_at;
    public boolean has_pgp;
    public String public_key;
    public String last_vacuum_at;
    public String imap_backup_at;

    @Override
    public String toString() {
        return "Alias [name=" + name + ", is_enabled=" + is_enabled
                + ", has_recipient_verification=" + has_recipient_verification + ", recipients=" + recipients
                + ", created_at=" + created_at + ", updated_at=" + updated_at + "]";
    }

    public static class AliasOwner {
        String email;
        String display_name;
        String id;

        @Override
        public String toString() {
            return "AliasOwner [email=" + email + ", display_name=" + display_name + ", id=" + id + "]";
        }
    }

    public static class AliasDomain {
        String name;
        String id;

        @Override
        public String toString() {
            return "AliasDomain [name=" + name + ", id=" + id + "]";
        }
    }
}
