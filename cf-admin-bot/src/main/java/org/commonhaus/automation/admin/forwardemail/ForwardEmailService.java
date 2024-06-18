package org.commonhaus.automation.admin.forwardemail;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.annotation.Nonnull;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response.Status;

import org.commonhaus.automation.admin.AdminDataCache;
import org.commonhaus.automation.admin.api.CommonhausUser.ForwardEmail;
import org.commonhaus.automation.admin.api.MemberSession;
import org.commonhaus.automation.admin.config.UserManagementConfig;
import org.commonhaus.automation.admin.github.AppContextService;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import io.quarkus.logging.Log;

@Singleton
public class ForwardEmailService {

    @RestClient
    ForwardEmailClient forwardEmailClient;

    @Inject
    AppContextService ctx;

    /**
     * Fetch aliases using the ForwardEmail Rest Client
     *
     * @param emails Set of AliasKeys defining aliases to fetch
     * @param resetCache boolean to force a cache reset
     * @return Map of String address to Alias (for return to client)
     * @throws WebApplicationException on Rest Client error (including Not Found)
     * @see #getAlias(AliasKey, boolean)
     */
    public Map<AliasKey, Alias> fetchAliases(Set<AliasKey> emails, boolean resetCache) {
        if (emailDisabled()) {
            return Map.of();
        }
        Map<AliasKey, Alias> aliases = new HashMap<>();
        for (AliasKey key : emails) {
            try {
                // API CALL: will throw WebApplicationException if not found or error
                Alias alias = getAlias(key, resetCache);
                aliases.put(key, alias);
            } catch (WebApplicationException e) {
                if (e.getResponse().getStatus() == 404) {
                    Log.debugf("getAliases: Alias not found: %s", key);
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
    public Map<AliasKey, Alias> postAliases(Map<AliasKey, Set<String>> aliases, String description) {
        if (emailDisabled()) {
            return Map.of();
        }
        Map<AliasKey, Alias> result = new HashMap<>();
        Map<AliasKey, Alias> existingAliases = fetchAliases(aliases.keySet(), false);
        for (Map.Entry<AliasKey, Set<String>> entry : aliases.entrySet()) {
            try {
                AliasKey key = entry.getKey();
                Alias existing = existingAliases.get(key);
                Set<String> recipients = entry.getValue();

                // API CALL: Create or Update alias
                // will throw WebApplicationException if not found or error
                Alias updated = putAlias(key, description, recipients, existing);
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
     * Generate a password for the specified email alias using the ForwardEmail Rest Client
     *
     * @param email
     * @return
     */
    public boolean generatePassword(String email) {
        if (emailDisabled()) {
            return false;
        }
        // API CALL: will throw WebApplicationException if not found or error
        // Alias alias = getAlias(email, false);
        // TODO: NOT YET.. SOOOON
        // if (alias != null && alias.verified_recipients != null &&
        // alias.verified_recipients.size() > 0) {
        // forwardEmailClient.generatePassword(alias.domain.name, alias.id,
        // alias.verified_recipients.iterator().next());
        // return true;
        // }
        return false;
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
    protected Alias getAlias(@Nonnull AliasKey aliasKey, boolean resetCache) {
        if (emailDisabled()) {
            return null;
        }
        String lookup = aliasKey.toString();
        Alias alias = resetCache ? null : AdminDataCache.ALIASES.get(lookup);
        if (alias == null) {
            // API CALL: will throw WebApplicationException if not found or error
            // Will throw 404 on not found
            Set<Alias> aliases = forwardEmailClient.findAliasByName(aliasKey.domain(), aliasKey.name());
            if (aliases.isEmpty()) {
                throw new WebApplicationException("Alias not found: " + aliasKey, Status.NOT_FOUND);
            } else if (aliases.size() > 1) {
                // should not happen, but...
                throw new WebApplicationException("Multiple aliases found for " + aliasKey, Status.BAD_REQUEST);
            } else {
                alias = aliases.iterator().next();
                AdminDataCache.ALIASES.put(lookup, alias);
            }
        }
        return alias;
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
    protected Alias putAlias(@Nonnull AliasKey aliasKey, @Nonnull String description,
            @Nonnull Set<String> recipients, Alias existing) {
        if (emailDisabled() || recipients == null || recipients.isEmpty()) {
            return null;
        }

        Alias alias = existing;
        if (alias == null) {
            alias = aliasKey.toAlias();
            alias.description = description;
            alias.recipients = recipients;
            alias.is_enabled = true;
            alias.has_recipient_verification = true;
            // API CALL: will throw WebApplicationException or error
            alias = forwardEmailClient.createAlias(aliasKey.domain(), alias);
        } else if (alias.isDirty(description, recipients)) {
            alias.has_recipient_verification = true;
            alias.description = description;
            alias.recipients = recipients;
            if (alias.verified_recipients != null) {
                alias.verified_recipients.retainAll(recipients);
            }
            // API CALL: will throw WebApplicationException on error
            alias = forwardEmailClient.updateAlias(aliasKey.domain(), description, alias);
        }
        AdminDataCache.ALIASES.put(aliasKey.toString(), alias);
        return alias;
    }

    protected boolean emailDisabled() {
        UserManagementConfig userConfig = ctx.userManagementConfig();
        return userConfig.emailDisabled();
    }

    protected String defaultAliasDomain() {
        UserManagementConfig userConfig = ctx.userManagementConfig();
        return userConfig.emailDisabled() ? null : userConfig.defaultAliasDomain();
    }

    protected AliasKey normalizeAlias(String email) {
        int at = email.indexOf('@');
        String name = at < 0 ? email : email.substring(0, at);
        String domain = at < 0 ? defaultAliasDomain() : email.substring(at + 1);
        return AliasKey.createKey(name, domain);
    }

    public Set<AliasKey> normalizeEmailAddresses(MemberSession session, ForwardEmail forwardEmail) {
        List<String> addresses = new ArrayList<>();
        addresses.add(session.login());
        addresses.addAll(forwardEmail.altAlias());
        // Normalize email addresses using the default domain (server config)
        return addresses.stream().map(this::normalizeAlias).collect(Collectors.toSet());
    }

    public Map<AliasKey, Set<String>> sanitizeInputAddresses(Map<String, Set<String>> input, Set<AliasKey> permitted) {
        // Filter/Remove any unknown/extraneous email addresses
        Map<AliasKey, Set<String>> sanitized = new HashMap<>();
        input.entrySet().forEach(x -> {
            AliasKey address = normalizeAlias(x.getKey());
            if (permitted.contains(address)) {
                sanitized.put(address, x.getValue());
            }
        });
        return sanitized;
    }
}
