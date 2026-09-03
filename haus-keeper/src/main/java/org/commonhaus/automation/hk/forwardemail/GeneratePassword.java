package org.commonhaus.automation.hk.forwardemail;

public record GeneratePassword(
        boolean is_override,
        String emailed_instructions,
        String new_password,
        String password) {
}
