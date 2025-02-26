package org.commonhaus.automation.hk.data;

import jakarta.annotation.Nonnull;

import org.commonhaus.automation.github.context.DataCommonItem;

public record MembershipApplication(
        @Nonnull String nodeId,
        @Nonnull String htmlUrl) {

    public static MembershipApplication fromDataCommonType(DataCommonItem data) {
        return new MembershipApplication(data.id, data.url);
    }
}
