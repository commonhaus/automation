package org.commonhaus.automation.hm.namecheap.requests;

import jakarta.ws.rs.QueryParam;

/**
 * Base class for all Namecheap API request beans.
 * Ensures all requests provide the required Command parameter.
 *
 * Global parameters (ApiUser, ApiKey, UserName, ClientIp) are handled
 * by the REST client configuration via ClientRequestFilter, not in request beans.
 *
 * See: https://www.namecheap.com/support/api/global-parameters/
 */
public abstract class BaseRequest {

    /**
     * Get the command name for this API call.
     * Each subclass must implement this to return the specific command
     * (e.g., "namecheap.domains.getList", "namecheap.domains.create", etc.)
     */
    @QueryParam("Command")
    public abstract String getCommand();
}
