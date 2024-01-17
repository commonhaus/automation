package org.commonhaus.automation.github;

import jakarta.json.JsonObject;

public class GHWebHook {
    public final String action;
    public final GHRepository repository;
    public final GHOrganization organization;
    public final GHInstallation installation;
    public final Actor sender;

    public GHWebHook(JsonObject object) {
        this.action = JsonAttribute.action.stringFrom(object);
        this.repository = new GHRepository(JsonAttribute.repository.jsonObjectFrom(object));
        this.organization = new GHOrganization(JsonAttribute.organization.jsonObjectFrom(object));
        this.installation = new GHInstallation(JsonAttribute.installation.jsonObjectFrom(object));
        this.sender = JsonAttribute.sender.actorFrom(object);
    }

    static class GHInstallation {
        /** For consistency with other objects, this is the string {@literal id} used by GraphQL queries; it is the {@literal node_id} for webhook events */ 
        public final String id;
        /** {@literal id} for webhook events (number) */
        public final Integer webhook_id;

        GHInstallation(JsonObject object) {
            this.webhook_id = JsonAttribute.id.integerFrom(object);
            this.id = JsonAttribute.node_id.stringFrom(object);
        }
    }

    static class GHRepository {
        /** For consistency with other objects, this is the string {@literal id} used by GraphQL queries; it is the {@literal node_id} for webhook events */ 
        public final String id;
        /** {@literal id} for webhook events (number) */
        public final Integer webhook_id;

        public final String name;
        public final String full_name;
        public final Actor owner;

        public final String url;

        GHRepository(JsonObject object) {
            this.webhook_id = JsonAttribute.id.integerFrom(object);
            this.id = JsonAttribute.node_id.stringFrom(object);
            this.name = JsonAttribute.name.stringFrom(object);
            this.full_name = JsonAttribute.full_name.stringFrom(object);
            this.owner = JsonAttribute.owner.actorFrom(object);
            // will look for html_url first.. 
            this.url = JsonAttribute.url.stringFrom(object);
        }
    }

    static class GHOrganization {
        /** For consistency with other objects, this is the string {@literal id} used by GraphQL queries; it is the {@literal node_id} for webhook events */ 
        public final String id;
        /** {@literal id} for webhook events (number) */
        public final Integer webhook_id;
        
        public final String login;
        
        // All API URLs
        public final String url;

        GHOrganization(JsonObject object) {
            this.webhook_id = JsonAttribute.id.integerFrom(object);
            this.login = JsonAttribute.login.stringFrom(object);
            this.id = JsonAttribute.node_id.stringFrom(object);
            this.url = JsonAttribute.url.stringFrom(object);
        }
    }
}
