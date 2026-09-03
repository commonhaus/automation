package org.commonhaus.automation.hk.forwardemail;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response.Status;

import org.commonhaus.automation.hk.AdminDataCache;
import org.commonhaus.automation.hk.api.MemberSession;
import org.commonhaus.automation.hk.config.UserManagementConfig;
import org.commonhaus.automation.hk.data.CommonhausUser;
import org.commonhaus.automation.hk.data.CommonhausUserData.ForwardEmail;
import org.commonhaus.automation.hk.data.CommonhausUserData.Services;
import org.commonhaus.automation.hk.github.AppContextService;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import io.quarkus.logging.Log;

@Singleton
public class ForwardEmailService {
    private static final int MAX_PASSWORD_LENGTH = 128;

    @RestClient
    ForwardEmailClient forwardEmailClient;

    @Inject
    AppContextService ctx;

    public Map<AliasKey, Alias> fetchAliases(MemberSession session, CommonhausUser user) {
        Set<AliasKey> emailAddresses = getConfiguredAliases(session, user);
        if (emailAddresses.isEmpty()) {
            return Map.of();
        }
        return fetchAliases(emailAddresses);
    }

    /**
     * Fetch aliases using the ForwardEmail Rest Client
     *
     * @param emails Set of AliasKeys defining aliases to fetch
     * @param resetCache boolean to force a cache reset
     * @return Map of String address to Alias (for return to client)
     * @throws WebApplicationException on Rest Client error (including Not Found)
     * @see #getAlias(AliasKey, boolean)
     */
    Map<AliasKey, Alias> fetchAliases(Set<AliasKey> emails) {
        if (emailDisabled()) {
            return Map.of();
        }
        Map<AliasKey, Alias> aliases = new HashMap<>();
        for (AliasKey key : emails) {
            try {
                // API CALL: will throw WebApplicationException if not found or error
                Alias alias = getAlias(key);
                aliases.put(key, alias);
            } catch (WebApplicationException e) {
                if (e.getResponse().getStatus() == 404) {
                    Log.debugf("getAliases: Alias not found: %s, creating placeholder", key);
                    // Create a placeholder alias so the frontend can display the input form
                    Alias placeholder = key.toAlias();
                    aliases.put(key, placeholder);
                    continue;
                }
                throw e;
            }
        }
        return aliases;
    }

    /**
     * Create or update aliases using the ForwardEmail Rest Client
     *
     * @param emails Set of AliasKeys defining aliases to fetch
     * @param resetCache boolean to force a cache reset
     * @return Map of String address to Alias (for return to client)
     * @throws WebApplicationException on Rest Client error.
     * @see #fetchAliases(Set, boolean)
     * @see #putAlias(AliasKey, String, Set, Alias)
     */
    public Map<AliasKey, Alias> postAliases(Map<AliasKey, AliasUpdate> aliases, String description) {
        if (emailDisabled()) {
            return Map.of();
        }
        validateUpdates(aliases);

        Map<AliasKey, Alias> result = new HashMap<>();
        Map<AliasKey, Alias> existingAliases = fetchAliases(aliases.keySet());
        for (Map.Entry<AliasKey, AliasUpdate> entry : aliases.entrySet()) {
            try {
                AliasKey key = entry.getKey();
                Alias existing = existingAliases.get(key);
                AliasUpdate update = entry.getValue();
                Set<String> recipients = update.recipients();

                // API CALL: Create or Update alias
                // will throw WebApplicationException if not found or error
                Alias updated = putAlias(key, description, recipients, update.has_imap(), existing);
                result.put(key, updated);
            } catch (WebApplicationException e) {
                if (e.getResponse().getStatus() == 404) {
                    // If we get a 404 here, that's an error. We should have created unknown aliases
                    throw new WebApplicationException("Unable to create or update alias", Status.INTERNAL_SERVER_ERROR);
                }
                throw e;
            }
        }
        return result;
    }

    /**
     * Validate all updates in the batch before any ForwardEmail API call is made.
     * All-or-nothing: if any alias fails validation, the whole batch is rejected
     * and no alias in it is applied.
     *
     * @throws AliasValidationException on the first validation failure found
     */
    protected void validateUpdates(Map<AliasKey, AliasUpdate> aliases) {
        for (Map.Entry<AliasKey, AliasUpdate> entry : aliases.entrySet()) {
            AliasKey key = entry.getKey();
            AliasUpdate update = entry.getValue();
            Set<String> recipients = update.recipients();
            boolean hasRecipients = recipients != null && !recipients.isEmpty();

            if (!hasRecipients && !update.has_imap()) {
                throw new AliasValidationException(
                        "alias " + key + " must have at least one recipient or have_imap enabled");
            }

            if (recipients != null && !recipients.isEmpty()) {
                Domain domain = getDomain(key.domain());
                if (domain != null && domain.max_recipients_per_alias != null
                        && recipients.size() > domain.max_recipients_per_alias) {
                    throw new AliasValidationException(
                            "alias " + key + " recipients exceeds max_recipients_per_alias ("
                                    + domain.max_recipients_per_alias + ")");
                }
            }
        }
    }

    /**
     * Set/change the password for the specified email alias using the ForwardEmail Rest Client
     *
     * @param alias Alias to set the password for
     * @param newPassword Member-supplied new password (required)
     * @param currentPassword Current password, to preserve mailbox contents (optional)
     * @param reset Whether the member has confirmed a destructive reset (maps to ForwardEmail's is_override)
     * @param email Verified recipient to notify, if any (maps to ForwardEmail's emailed_instructions)
     * @return the ForwardEmail confirmation response, or null if the alias is null or ineligible
     */
    public GeneratePasswordResponse generatePassword(Alias alias, String newPassword, String currentPassword,
            boolean reset, String email) {
        if (newPassword == null || newPassword.isEmpty()) {
            throw new WebApplicationException("new_password is required", Status.BAD_REQUEST);
        }
        if (newPassword.length() > MAX_PASSWORD_LENGTH
                || !newPassword.equals(newPassword.strip())
                || newPassword.indexOf('"') >= 0
                || newPassword.indexOf('\'') >= 0) {
            throw new WebApplicationException(
                    "new_password must be 128 characters or fewer, have no leading or trailing whitespace, "
                            + "and contain no quotes or apostrophes",
                    Status.BAD_REQUEST);
        }

        boolean hasVerifiedRecipient = alias != null && alias.verified_recipients != null
                && !alias.verified_recipients.isEmpty();
        if (emailDisabled() || alias == null || (!hasVerifiedRecipient && !alias.has_imap)) {
            return null;
        }

        // Check for null domain or id which would cause NullPointerException in REST client
        if (alias.domain == null || alias.domain.name == null || alias.id == null) {
            Log.errorf("generatePassword: Invalid alias data: %s", alias);
            return null;
        }

        // API CALL: will throw WebApplicationException if not found or error
        return forwardEmailClient.generatePassword(
                alias.domain.name,
                alias.id,
                new GeneratePassword(reset, email, newPassword, currentPassword));
    }

    /**
     * Clear the cache of all aliases configured for the specified user
     *
     * @param session MemberSession
     * @param user CommonhausUser
     */
    public void forgetUser(MemberSession session, CommonhausUser user) {
        Set<AliasKey> emailAddresses = getConfiguredAliases(session, user);
        emailAddresses.forEach(x -> AdminDataCache.ALIASES.invalidate(x.toString()));
    }

    /**
     * Wrap the call the ForwardEmailClient.getAlias to cache the result
     *
     * @param aliasKey Single aliasKey to fetch
     * @param resetCache boolean to force a cache reset
     * @return retrieved Alias object
     * @throws WebApplicationException on Rest Client error (including Not Found)
     *         or if the key resolves to multiple aliases
     */
    protected Alias getAlias(AliasKey aliasKey) {
        if (emailDisabled()) {
            return null;
        }
        String lookup = aliasKey.toString();
        Alias alias = AdminDataCache.ALIASES.get(lookup);
        if (alias == null) {
            // API CALL: will throw WebApplicationException if not found (404) or error
            Set<Alias> aliases = forwardEmailClient.findAliasByName(aliasKey.domain(), aliasKey.name());
            // The name search is a fuzzy match, so we may get multiple results.
            aliases.removeIf(x -> x.name == null || !x.name.equals(aliasKey.name()));
            if (aliases.isEmpty()) {
                throw new WebApplicationException("Alias not found: " + aliasKey, Status.NOT_FOUND);
            } else if (aliases.size() > 1) {
                // should not happen, but...
                Log.errorf("Multiple aliases found for %s: %s", aliasKey, aliases);
                throw new WebApplicationException("Multiple aliases found for " + aliasKey, Status.BAD_REQUEST);
            } else {
                alias = aliases.iterator().next();
                Log.debugf("Cache retrieved alias: %s", alias);
                AdminDataCache.ALIASES.put(lookup, alias);
            }
        }
        return alias;
    }

    /**
     * Wrap the call to ForwardEmailClient.getDomains to cache the result
     *
     * @param fqdn Domain name to fetch (e.g. "commonhaus.dev")
     * @return the matching Domain, or null if not found
     * @throws WebApplicationException on Rest Client error
     */
    protected Domain getDomain(String fqdn) {
        if (emailDisabled()) {
            return null;
        }
        Domain domain = AdminDataCache.DOMAINS.get(fqdn);
        if (domain == null) {
            // API CALL: will throw WebApplicationException on error
            Set<Domain> domains = forwardEmailClient.getDomains();
            domain = domains.stream()
                    .filter(x -> fqdn.equals(x.name))
                    .findFirst()
                    .orElse(null);
            if (domain != null) {
                Log.debugf("Cache retrieved domain: %s", domain);
                AdminDataCache.DOMAINS.put(fqdn, domain);
            }
        }
        return domain;
    }

    /**
     * Wrap the call the ForwardEmailClient.putAlias to choose between post/put
     * and cache the result
     *
     * @param aliasKey Single AliasKey representing the Alias to create or update
     * @param description Description of the Alias (usually the user's name)
     * @param recipients Set of email addresses to forward to
     * @param existing Existing Alias object (if present)
     * @throws WebApplicationException on Rest Client error
     */
    protected Alias putAlias(AliasKey aliasKey, String description,
            Set<String> recipients, boolean hasImap, Alias existing) {
        if (emailDisabled() || recipients == null || recipients.isEmpty()) {
            return null;
        }

        Alias alias = existing;
        if (alias == null || alias.id == null) {
            // Create a new alias if no existing alias or if it's a placeholder without an id
            alias = aliasKey.toAlias();
            alias.description = description;
            alias.recipients = recipients;
            alias.is_enabled = true;
            alias.has_recipient_verification = true;
            alias.has_imap = hasImap;
            // API CALL: will throw WebApplicationException or error
            alias = forwardEmailClient.createAlias(aliasKey.domain(), alias);
        } else if (alias.isDirty(description, recipients, hasImap)) {
            alias.has_recipient_verification = true;
            alias.description = description;
            alias.has_imap = hasImap;
            alias.recipients = recipients;
            if (alias.verified_recipients != null) {
                alias.verified_recipients.retainAll(recipients);
            }
            // API CALL: will throw WebApplicationException on error
            alias = forwardEmailClient.updateAlias(aliasKey.domain(), alias.id, alias);
        }
        Log.debugf("Update alias %s", alias);
        AdminDataCache.ALIASES.put(aliasKey.toString(), alias);
        return alias;
    }

    protected boolean emailDisabled() {
        UserManagementConfig userConfig = ctx.getConfig();
        return userConfig.emailDisabled();
    }

    protected String defaultAliasDomain() {
        UserManagementConfig userConfig = ctx.getConfig();
        return userConfig.emailDisabled() ? null : userConfig.defaultAliasDomain();
    }

    protected AliasKey normalizeAlias(String email) {
        int at = email.indexOf('@');
        String name = at < 0 ? email : email.substring(0, at);
        String domain = at < 0 ? defaultAliasDomain() : email.substring(at + 1);
        return AliasKey.createKey(name.toLowerCase(), domain.toLowerCase());
    }

    public boolean isDefaultAlias(String login, AliasKey email) {
        return email.name().equals(login) && email.domain().equals(defaultAliasDomain());
    }

    public Set<AliasKey> normalizeEmailAddresses(MemberSession session, ForwardEmail forwardEmail) {
        List<String> addresses = new ArrayList<>();
        if (forwardEmail.hasDefaultAlias()) {
            addresses.add(session.login());
        }
        addresses.addAll(forwardEmail.altAlias());
        // Normalize email addresses using the default domain (server config)
        return addresses.stream().map(this::normalizeAlias).collect(Collectors.toSet());
    }

    public Map<AliasKey, AliasUpdate> sanitizeInputUpdates(MemberSession session, CommonhausUser user,
            Map<String, AliasUpdate> input) {
        Set<AliasKey> permitted = getConfiguredAliases(session, user);
        Map<AliasKey, AliasUpdate> sanitized = new HashMap<>();
        input.entrySet().forEach(x -> {
            AliasKey address = normalizeAlias(x.getKey());
            if (permitted.contains(address)) {
                sanitized.put(address, x.getValue());
            }
        });
        Log.debugf("sanitizeInputUpdates: permitted=%s, sanitized=%s", permitted, sanitized);
        return sanitized;
    }

    public Set<AliasKey> getConfiguredAliases(MemberSession session, CommonhausUser user) {
        if (emailDisabled() || !(user.status().mayHaveEmail() || user.status().mayHaveAltEmail())) {
            return Set.of();
        }
        Services services = user.services();
        ForwardEmail emailConfig = services.forwardEmail();
        return normalizeEmailAddresses(session, emailConfig);
    }
}
