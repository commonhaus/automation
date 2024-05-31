package org.commonhaus.automation.admin.forwardemail;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
public class Domain {
    public String name;
    public String id;
    public String link;
    public String plan;
    public Integer max_recipients_per_alias;
    public Integer smtp_port;
    public boolean has_adult_content_protection;
    public boolean has_catchall;
    public boolean has_executable_protection;
    public boolean has_mx_record;
    public boolean has_phishing_protection;
    public boolean has_recipient_verification;
    public boolean has_txt_record;
    public boolean has_virus_protection;
    public String created_at;
    public String updated_at;

    @Override
    public String toString() {
        return "Domain [name=" + name + ", plan=" + plan + ", has_catchall=" + has_catchall + ", created_at="
                + created_at + ", updated_at=" + updated_at + "]";
    }
}
