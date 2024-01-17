package org.commonhaus.automation.github;

import jakarta.json.JsonObject;

public class GHComment extends GHObject {

    /** {@literal parent_id} for webhook events (may be null) */
    public final Integer parent_id;

    public final boolean includesCreatedEdit;

    public GHComment(JsonObject object) {
        super(object);

        this.parent_id = JsonAttribute.parent_id.integerFrom(object);
        this.includesCreatedEdit = JsonAttribute.includesCreatedEdit.booleanFromOrFalse(object);
    }
}
