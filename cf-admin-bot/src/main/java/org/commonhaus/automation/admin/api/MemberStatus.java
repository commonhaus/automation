package org.commonhaus.automation.admin.api;

public enum MemberStatus {
    REVOKED,
    SUSPENDED,
    DECLINED,
    COMMITTEE,
    ACTIVE,
    INACTIVE,
    PENDING,
    SPONSOR,
    CONTRIBUTOR, // project contributor, not sponsor or member
    UNKNOWN;

    public static MemberStatus fromString(String role) {
        if (role == null) {
            return UNKNOWN;
        }
        try {
            return MemberStatus.valueOf(role.toUpperCase());
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }

    public boolean mayHaveEmail() {
        return this == COMMITTEE
                || this == ACTIVE
                || this == INACTIVE;
    }

    public boolean updateToPending() {
        return this == UNKNOWN
                || this == SPONSOR
                || this == INACTIVE;
    }

    public boolean updateFromPending() {
        return this == UNKNOWN
                || this == PENDING;
    }

    public boolean couldBeActiveMember() {
        return this != MemberStatus.REVOKED
                && this != MemberStatus.SUSPENDED
                && this != MemberStatus.DECLINED;
    }
}
