package org.commonhaus.automation.hk.data;

import org.commonhaus.automation.github.context.DataCommonItem;

public record ApplicationIssue(
        String nodeId,
        String htmlUrl) {

    public static ApplicationIssue fromDataCommonType(DataCommonItem data) {
        return new ApplicationIssue(data.id, data.url);
    }

    public String toString() {
        return nodeId;
    }
}
