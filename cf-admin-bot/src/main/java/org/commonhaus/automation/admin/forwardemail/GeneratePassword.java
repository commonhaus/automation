package org.commonhaus.automation.admin.forwardemail;

public record GeneratePassword(
        boolean is_override,
        String emailed_instructions) {
}
