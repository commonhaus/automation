package org.commonhaus.automation.github;

import java.util.Date;

import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;

/**
 * List of Json fields returned from GraphQL queries.
 * Reader wraps construction of base field types from the Json object. 
 * 
 * Why? This avoids finger checks from a lot of hard-coded strings
 * for fields. It also ensures we're using sane defaults, and converting
 * from Json in a consistent way for more complex types.
 * 
 * If an alternate name is provided, the reader will check for that name
 * first, and then fall back to the name() attribute of the enum.
 */
public enum JsonAttribute {
    action, 
    activeLockReason, 
    answer, 
    answerChosenAt, 
    author, 
    authorAssociation, 
    avatarUrl("avatar_url"), 
    body,
    category, 
    changes, 
    closed, 
    closedAt, 
    color, 
    comment, 
    confused, 
    content, 
    createdAt("created_at"),
    deletedAt, 
    description, 
    discussion, 
    discussionCategories, 
    discussion_id, 
    discussions,
    editedAt,
    editor, 
    emoji,
    endCursor, 
    eyes, 
    from, 
    full_name, 
    hasNextPage, 
    heart, 
    hooray, 
    id,
    includesCreatedEdit, 
    installation, 
    isAnswered, 
    isDefault("default"), 
    label, 
    lastEditedAt, 
    laugh, 
    locked,
    login, 
    minusOne, 
    name, 
    new_discussion, 
    new_repository, 
    node, 
    node_id, 
    nodes,
    number, 
    old_answer, 
    organization, 
    owner, 
    pageInfo, 
    parent_id, 
    plusOne, 
    publishedAt, 
    reactableId, 
    reactions, 
    repository,
    rocket, 
    sender, 
    state, 
    stateReason, 
    title,
    total_count, 
    updatedAt("updated_at"), 
    upvoteCount, 
    url("html_url"), 
    user, 
    ;

    private final String nodeName;
    private final boolean alternateName;
    private JsonAttribute() {
        this.nodeName = this.name();
        this.alternateName = false;
    }
    private JsonAttribute(String nodeName) {
        this.nodeName = nodeName;
        this.alternateName = true;
    }
    public String getNodeName() {
        return nodeName;
    }

    /** 
     * @return boolean with value from nodeName (or name()) attribute of object or false 
     */
    public boolean booleanFromOrFalse(JsonObject object) {
        if (object == null) {
            return false;
        }
        if (alternateName) {
            return object.getBoolean(nodeName, object.getBoolean(name(), false));
        }
        return object.getBoolean(nodeName, false);
    }
    /** 
     * @return boolean with value from nodeName (or name()) attribute of object or defaultValue 
     */
    public boolean booleanFrom(JsonObject object, boolean defaultValue) {
        if (object == null) {
            return defaultValue;
        }
        if (alternateName) {
            return object.getBoolean(nodeName, object.getBoolean(name(), defaultValue));
        }
        return object.getBoolean(nodeName, defaultValue);
    }

    /** 
     * @return String with value from nodeName (or name()) attribute of object or null 
     */
    public String stringFrom(JsonObject object) {
        if (object == null) {
            return null;
        }
        return object.getString(nodeName);
    }
    /** 
     * @return String with value from nodeName (or name()) attribute of object or defaultValue 
     */
    public String stringFrom(JsonObject object, String defaultValue) {
        if (object == null) {
            return defaultValue;
        }
        if (alternateName) {
            return object.getString(nodeName, object.getString(name(), defaultValue));
        }
        return object.getString(nodeName, defaultValue);
    }

    /** 
     * @return Integer with value from nodeName (or name()) attribute of object or null 
     */
    public Integer integerFrom(JsonObject object) {
        if (object == null) {
            return null;
        }
        JsonValue value = object.get(nodeName);
        if (alternateName && value == null) {
            value = object.get(name());
        }
        return value == null ? null : JsonNumber.class.cast(value).intValue();
    }
    /** 
     * @return int with value from nodeName (or name()) attribute of object or default value 
     */
    public int integerFrom(JsonObject object, int defaultValue) {
        if (object == null) {
            return defaultValue;
        }
        if (alternateName) {
            return object.getInt(nodeName, object.getInt(name(), defaultValue));
        }
        return object.getInt(nodeName, defaultValue);
    }
    /** 
     * @return Date constructed from nodeName (or name()) attribute of object 
     */
    public Date dateFrom(JsonObject object) {
        if (object == null) {
            return null;
        }
        String timestamp = alternateName
            ? object.getString(nodeName, object.getString(name(), null))
            : object.getString(nodeName, null);
        return CFGHApp.parseDate(timestamp);
    }

    /** 
     * @return Actor constructed from nodeName (or name()) attribute of object 
     */
    public Actor actorFrom(JsonObject object) {
        if (object == null) {
            return null;
        }
        JsonObject field = jsonObjectFrom(object);
        return field == null ? null : new Actor(field);
    }

    /** 
     * @return Discussion constructed from nodeName (or name()) attribute of object 
     */
    public Discussion discussionFrom(JsonObject object) {
        if (object == null) {
            return null;
        }
        JsonObject field = jsonObjectFrom(object);
        return field == null ? null : new Discussion(field);
    }
    /** 
     * @return DiscussionCategory constructed from nodeName (or name()) attribute of object 
     */
    public DiscussionCategory discussionCategoryFrom(JsonObject object) {
        if (object == null) {
            return null;
        }
        JsonObject field = jsonObjectFrom(object);
        return field == null ? null : new DiscussionCategory(field);
    }
    /** 
     * @return DiscussionComment constructed from nodeName (or name()) attribute of object 
     */
    public DiscussionComment discussionCommentFrom(JsonObject object) {
        if (object == null) {
            return null;
        }
        JsonObject field = jsonObjectFrom(object);
        return field == null ? null : new DiscussionComment(field);
    }

    /** 
     * @return Label constructed from nodeName (or name()) attribute of object 
     */
    public Label labelFrom(JsonObject object) {
        if (object == null) {
            return null;
        }
        JsonObject field = jsonObjectFrom(object);
        return field == null ? null : new Label(field);
    }

    /** @return JsonObject with nodeName (or name()) from object */
    public JsonObject jsonObjectFrom(JsonObject object) {
        if (object == null) {
            return null;
        }
        JsonObject result = (JsonObject) object.get(nodeName);
        if (alternateName && result == null) {
            result = (JsonObject) object.get(name());
        }
        return result;
    }
    /** 
     * @return JsonObject from nodeName (or name()) attribute
     * after extracting intermediate nodes (using attributes) from 
     * original object
     */
    public JsonObject extractObjectFrom(JsonObject object, JsonAttribute... readers) {
        if (object == null) {
            return null;
        }
        for (JsonAttribute reader : readers) {
            object = reader.jsonObjectFrom(object);
            if (object == null) {
                return null;
            }
        }
        return this.jsonObjectFrom(object);
    }

    /** @return JsonArray with nodeName (or name()) from object */
    public JsonArray jsonArrayFrom(JsonObject object) {
        if (object == null) {
            return null;
        }
        JsonArray array = (JsonArray) object.get(nodeName);
        if (alternateName && array == null) {
            array = (JsonArray) object.get(name());
        }
        return array;
    }
    /** 
     * @return JsonArray constructed from nodeName (or name()) attribute
     * after extracting intermediate nodes (using attributes) from 
     * original object
     */
    public JsonArray extractArrayFrom(JsonObject object, JsonAttribute... readers) {
        if (object == null) {
            return null;
        }
        for (JsonAttribute reader : readers) {
            object = reader.jsonObjectFrom(object);
            if (object == null) {
                return null;
            }
        }
        return this.jsonArrayFrom(object);
    }
}
