package org.commonhaus.automation.hm.config;

import java.util.Optional;

/**
 * Interface for SmallRye Config (@ConfigMapping).
 * Used in ManagerBotConfig for bot default contacts from application.properties.
 */
public interface ContactConfig {
    String firstName();

    String lastName();

    String address1();

    String city();

    String stateProvince();

    String postalCode();

    String country();

    String phone();

    String emailAddress();

    Optional<String> organization();

    Optional<String> jobTitle();

    Optional<String> address2();

    Optional<String> phoneExt();

    Optional<String> fax();
}
