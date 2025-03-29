package org.commonhaus.automation.opencollective;

import java.util.List;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;

import org.commonhaus.automation.JsonAttributeAccessor;

import io.quarkus.runtime.annotations.RegisterForReflection;

public interface OpenCollectiveData {

    public static final String BASIC_QUERY = """
            query account($slug: String) {
                account(slug: $slug) {
                    id
                    name
                    slug
                }
            }
            """;

    public static String USER_ACCOUNT = """
            id
            name
            slug
            imageUrl
            isIncognito
            socialLinks {
                type
                url
            }
            """;

    public static String BACKERS_QUERY = """
            query account($slug: String, $offset: Int!) {
              account(slug: $slug) {
                members(role: BACKER, limit: 100, offset: $offset) {
                  totalCount
                  offset
                  limit
                  nodes {
                    account {
                      """ + USER_ACCOUNT + """
                    }
                  }
                }
              }
            }
            """;

    public static String CONTRIBUTIONS_QUERY = """
            query account($slug: String) {
              account(slug: $slug) {
                name
                slug
                transactions(limit: 10, type: CREDIT) {
                  totalCount
                  nodes {
                    type
                    fromAccount {
                      name
                      slug
                    }
                    amount {
                      value
                      currency
                    }
                    createdAt
                  }
                }
              }
            }
            """;

    @RegisterForReflection
    public class Account {
        final String id;
        final String name;
        final String slug;
        final String imageUrl;
        final boolean isIncognito;
        final List<SocialLink> socialLinks;

        Account(JsonObject object) {
            this.id = OpenCollectiveFields.id.stringFrom(object);
            this.name = OpenCollectiveFields.name.stringFrom(object);
            this.slug = OpenCollectiveFields.slug.stringFrom(object);
            this.imageUrl = OpenCollectiveFields.imageUrl.stringFrom(object);
            this.isIncognito = OpenCollectiveFields.isIncognito.booleanFromOrDefault(object, false);
            this.socialLinks = OpenCollectiveFields.socialLinks.socialLinksFrom(object);
        }

        @Override
        public String toString() {
            return "Account [id=" + id + ", name=" + name + ", slug=" + slug + ", imageUrl=" + imageUrl + ", socialLinks="
                    + socialLinks + "]";
        }
    }

    @RegisterForReflection
    public class SocialLink {
        final String type;
        final String url;

        SocialLink(JsonObject object) {
            this.type = OpenCollectiveFields.type.stringFrom(object);
            this.url = OpenCollectiveFields.url.stringFrom(object);
        }

        @Override
        public String toString() {
            return "SocialLink [type=" + type + ", url=" + url + "]";
        }
    }

    @RegisterForReflection
    public class Tier {

    }

    enum OpenCollectiveFields implements JsonAttributeAccessor {
        account,
        id,
        imageUrl,
        isIncognito,
        members,
        name,
        slug,
        socialLinks,
        type,
        url,

        // GraphQL Query attributes
        node,
        nodes,
        limit,
        offset,
        totalCount;

        private final String nodeName;
        private final boolean alternateName;

        OpenCollectiveFields() {
            this.nodeName = this.name();
            this.alternateName = false;
        }

        OpenCollectiveFields(String nodeName) {
            this.nodeName = nodeName;
            this.alternateName = true;
        }

        @Override
        public String alternateName() {
            return nodeName;
        }

        @Override
        public boolean hasAlternateName() {
            return alternateName;
        }

        public Account accountFrom(JsonObject object) {
            JsonObject field = jsonObjectFrom(object);
            return field == null ? null : new Account(field);
        }

        public List<SocialLink> socialLinksFrom(JsonObject object) {
            if (object == null) {
                return List.of();
            }
            JsonArray array = jsonArrayFrom(object);
            return array == null
                    ? List.of()
                    : array.stream()
                            .map(JsonObject.class::cast)
                            .map(SocialLink::new)
                            .toList();
        }
    }
}
