package org.commonhaus.automation.hm.namecheap;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.UriBuilder;

import org.commonhaus.automation.hm.config.ManagerBotConfig;

/**
 * Client request filter that adds Namecheap API global parameters to every request.
 * These parameters are required by all Namecheap API calls:
 * - ApiUser: Username required to access the API
 * - ApiKey: API key (password)
 * - UserName: The username on which a command is executed (usually same as ApiUser)
 * - ClientIp: IP address from which API calls are made (must be whitelisted)
 *
 * See: https://www.namecheap.com/support/api/global-parameters/
 */
public class NamecheapClientFilter implements ClientRequestFilter {
    private final String apiUser;
    private final String apiKey;
    private final String userName;
    private final String clientIp;

    /**
     * Create filter from Namecheap configuration.
     *
     * @param config Namecheap configuration from ManagerBotConfig
     */
    public NamecheapClientFilter(ManagerBotConfig.NamecheapConfig config) {
        this.apiUser = config.username();
        this.apiKey = config.apiKey();
        this.userName = config.username(); // Usually same as apiUser
        this.clientIp = config.ipv4Addr();
    }

    @Override
    public void filter(ClientRequestContext requestContext) {
        // Add global query parameters to the request URI
        UriBuilder uriBuilder = UriBuilder.fromUri(requestContext.getUri());
        uriBuilder.queryParam("ApiUser", apiUser);
        uriBuilder.queryParam("ApiKey", apiKey);
        uriBuilder.queryParam("UserName", userName);
        uriBuilder.queryParam("ClientIp", clientIp);

        requestContext.setUri(uriBuilder.build());
    }
}
