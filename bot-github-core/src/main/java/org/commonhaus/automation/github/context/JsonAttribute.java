package org.commonhaus.automation.github.context;

import java.time.format.DateTimeFormatter;
import java.util.List;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.json.JsonValue.ValueType;

import org.kohsuke.github.GHAppInstallation;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import com.fasterxml.jackson.databind.ObjectReader;

/**
 * This enumeration defines and manages a set of known field names utilized in GitHub API responses.
 * It provides a structured approach to handle JSON data without relying on reflection, using
 * enum values to avoid errors associated with hard-coded strings and to provide common
 * POJO (Plain Old Java Object) construction mechanisms.
 * <p>
 * Usage Examples:
 * </p>
 * <ul>
 * <li>
 * Extracting a field using the enum:
 * <br>
 * {@code JsonAttribute.author.actorFrom(object)}
 * <br>
 * This extracts the {@code author} field from the given JsonObject to construct an Actor.
 * </li>
 * <li>
 * Using an alternate enum name:
 * <br>
 * {@code JsonAttribute.url.stringFrom(object)}
 * <br>
 * This tries to use the {@code html_url} attribute from the JsonObject. If {@code html_url} is not present,
 * it defaults to using the {@code url} attribute.
 * </li>
 * </ul>
 */
public enum JsonAttribute implements JsonAttributeAccessor {

    // Event payload + repo discovery
    installation,
    organization,

    // Repo discovery
    fullName("full_name"),
    repositories,
    repositoriesAdded("repositories_added"),
    repositoriesRemoved("repositories_removed"),
    repository,

    // Base GitHub GraphQL object type
    id,
    node_id,
    url("html_url"),

    // Actor, extends base type
    avatarUrl("avatar_url"),
    login,

    // Common object, extends base type
    author,
    body,
    createdAt("created_at"),
    lastEditedAt,
    updatedAt("updated_at"),
    user,

    // Common Item (issue, discussion, pull request) extends Common object
    state,
    closed,
    closedAt("closed_at"),
    labels,
    number,
    title,

    // Discussion extends Common Item
    discussion,
    answer,
    category,

    // Issue or Pull Request extends Common Item
    createIssue,
    issue,
    pullRequest("pull_request"),
    reviewDecision,
    updateIssue,
    updatePullRequest,

    // Pull Request Review extends Common object
    latestReviews,
    submittedAt("submitted_at"),

    // Discussion Category or Label
    discussionCategories,
    name,

    // Label extends Common object
    addLabelsToLabelable,
    createLabel,
    label,
    labelable,
    removeLabelsFromLabelable,

    // Discussion and Issue comments
    addDiscussionComment,
    comment,
    commentEdge,
    comments,
    databaseId,
    discussion_id,
    issueComment,
    updateDiscussionComment,
    updateIssueComment,

    // Change/modification events
    changes,
    from,

    // Sponsorship amd Sponsorship Tier, extends common type
    // Does not have installation id!
    isActive,
    isCustomAmount("is_custom_amount"),
    isOneTime("is_one_time"),
    monthlyPriceInCents("monthly_price_in_cents"),
    monthlyPriceInDollars("monthly_price_in_dollars"),
    sponsorEntity("sponsor"),
    sponsorable,
    sponsorshipsAsMaintainer,
    tier,

    // Data Reaction -- standalone & weird; similar to common object
    content,
    reactableId,
    reactions,

    // Team, Collaborator, and membership events
    member,
    privacy,
    slug,
    team,

    // GraphQL Query attributes
    endCursor,
    hasNextPage,
    node,
    nodes,
    pageInfo,
    search,
    viewer,
    ;

    /** Bridge between JSON-B parsed types and Jackson-created GH* types */
    static final ObjectReader ghApiReader = GitHub.getMappingObjectReader();
    static final DateTimeFormatter DATE_TIME_PARSER_SLASHES = DateTimeFormatter
            .ofPattern("yyyy/MM/dd HH:mm:ss Z");

    private final String nodeName;
    private final boolean alternateName;

    JsonAttribute() {
        this.nodeName = this.name();
        this.alternateName = false;
    }

    JsonAttribute(String nodeName) {
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

    /**
     * @return Actor constructed from nodeName (or name()) attribute of object
     */
    public DataActor actorFrom(JsonObject object) {
        JsonObject field = jsonObjectFrom(object);
        return field == null ? null : new DataActor(field);
    }

    /**
     * @return DataCommonItem constructed from nodeName (or name()) attribute of object
     */
    public DataCommonItem commonItemFrom(JsonObject object) {
        JsonObject field = jsonObjectFrom(object);
        return field == null ? null : new DataCommonItem(field);
    }

    /**
     * @return DataCommonComment constructed from nodeName (or name()) attribute of object
     */
    public DataCommonComment commonCommentFrom(JsonObject object) {
        JsonObject field = jsonObjectFrom(object);
        return field == null ? null : new DataCommonComment(field);
    }

    /**
     * @return Discussion constructed from nodeName (or name()) attribute of object
     */
    public DataDiscussion discussionFrom(JsonObject object) {
        JsonObject field = jsonObjectFrom(object);
        return field == null ? null : new DataDiscussion(field);
    }

    /**
     * @return DiscussionCategory constructed from nodeName (or name()) attribute of object
     */
    public DataDiscussionCategory discussionCategoryFrom(JsonObject object) {
        JsonObject field = jsonObjectFrom(object);
        return field == null ? null : new DataDiscussionCategory(field);
    }

    /**
     * @return DataDiscussionComment constructed from nodeName (or name()) attribute of object
     */
    public DataDiscussionComment discussionCommentFrom(JsonObject object) {
        JsonObject field = jsonObjectFrom(object);
        return field == null ? null : new DataDiscussionComment(field);
    }

    /**
     * @return DataIssueComment constructed from nodeName (or name()) attribute of object
     */
    public DataIssueComment issueCommentFrom(JsonObject object) {
        JsonObject field = jsonObjectFrom(object);
        return field == null ? null : new DataIssueComment(field);
    }

    /**
     * @return Label constructed from nodeName (or name()) attribute of object
     */
    public DataLabel labelFrom(JsonObject object) {
        JsonObject field = jsonObjectFrom(object);
        return field == null ? null : new DataLabel(field);
    }

    /**
     * This list is constructed based on the field's type, as the input value
     * could be an object or an array.
     *
     * @return List of Labels constructed from nodeName (or name()) attribute of object
     */
    public List<DataLabel> labelsFrom(JsonObject object) {
        JsonValue field = valueFrom(object);
        if (field == null) {
            return null;
        }

        JsonArray list;
        if (field.getValueType() == ValueType.OBJECT) {
            // "labels": {
            //     "nodes": [
            //         {
            //         "id": "LA_kwDOL8tG0s8AAAABpSSN4Q",
            //         "name": "application/accepted"
            //         }
            //     ],
            //     "pageInfo": {
            //         "hasNextPage": false,
            //         "endCursor": "MQ"
            //     }
            // }
            JsonObject o = field.asJsonObject();
            if (JsonAttribute.id.existsIn(o)) {
                return List.of(new DataLabel(o));
            }
            list = JsonAttribute.nodes.jsonArrayFrom(o);
        } else {
            // "labels": [
            //     {
            //       "id": 6605129827,
            //       "node_id": "LA_kwDOLDuJqs8AAAABibJIYw",
            //       "url": "https://api.github.com/repos/commonhaus-test/automation-test/labels/vote/open",
            //       "name": "vote/open",
            //       "color": "5319e7",
            //       "default": false,
            //       "description": ""
            //     }
            //   ],
            list = field.asJsonArray();
        }
        return list.stream()
                .map(JsonObject.class::cast)
                .map(DataLabel::new)
                .toList();
    }

    /**
     * @return DataPageInfo constructed from the pageInfo attribute of a paginated query result
     */
    public DataPageInfo pageInfoFrom(JsonObject object) {
        JsonObject field = jsonObjectFrom(object);
        return field == null
                ? new DataPageInfo(null, false)
                : new DataPageInfo(object);
    }

    /**
     * @return DataTier constructed from nodeName (or name()) attribute of object
     */
    public DataTeam teamFrom(JsonObject object) {
        JsonObject field = jsonObjectFrom(object);
        return field == null ? null : new DataTeam(field);
    }

    /**
     * @return DataTier constructed from nodeName (or name()) attribute of object
     */
    public DataTier tierFrom(JsonObject object) {
        JsonObject field = jsonObjectFrom(object);
        return field == null ? null : new DataTier(field);
    }

    /**
     * Bridge to GH* type usually parsed using Jackson with the REST API;
     * the content returned by GraphQL may be incomplete.
     * Value is read as a string, and then passed to Jackson for parsing into GH object
     *
     * @return GHRepository constructed from nodeName (or name()) attribute of object
     */
    public GHRepository repositoryFrom(JsonObject object) {
        JsonObject field = jsonObjectFrom(object);
        String value = field.toString();
        return value == null
                ? null
                : tryOrNull(value, GHRepository.class);
    }

    /**
     * Bridge to GH* type usually parsed using Jackson with the REST API;
     * the content returned by GraphQL may be incomplete.
     * Value is read as a string, and then passed to Jackson for parsing into GH object
     *
     * @return List of GHRepository constructed from nodeName (or name()) attribute of object.
     */
    public List<GHRepository> repositoriesFrom(JsonObject object) {
        JsonArray list = jsonArrayFrom(object);
        return list == null
                ? null
                : list.stream()
                        .map(JsonObject.class::cast)
                        .map(x -> {
                            String fullName = JsonAttribute.fullName.stringFrom(x);
                            String owner = fullName.substring(0, fullName.indexOf('/'));
                            return Json.createObjectBuilder(x)
                                    .add("owner", Json.createObjectBuilder()
                                            .add("name", owner)
                                            .build())
                                    .build();
                        })
                        .map(x -> tryOrNull(x.toString(), GHRepository.class))
                        .toList();
    }

    /**
     * Bridge to GH* type usually parsed using Jackson with the REST API;
     * the content returned by GraphQL may be incomplete.
     * Value is read as a string, and then passed to Jackson for parsing into GH object
     *
     * @return GHOrganization constructed from nodeName (or name()) attribute of object
     */
    public GHOrganization organizationFrom(JsonObject object) {
        String value = stringFrom(object);
        return value == null
                ? null
                : tryOrNull(value, GHOrganization.class);
    }

    /**
     * Bridge to GH* type usually parsed using Jackson with the REST API;
     * the content returned by GraphQL may be incomplete.
     * Value is read as a string, and then passed to Jackson for parsing into GH object
     *
     * @return GHAppInstallation constructed from nodeName (or name()) attribute of object
     */
    public GHAppInstallation appInstallationFrom(JsonObject object) {
        String value = stringFrom(object);
        return value == null
                ? null
                : tryOrNull(value, GHAppInstallation.class);
    }
}
