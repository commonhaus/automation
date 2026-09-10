package org.commonhaus.automation.hk.forwardemail;

public record AliasKey(
        String name,
        String domain,
        String email) {

    public String toString() {
        return email;
    }

    public Alias toAlias() {
        Alias alias = new Alias();
        alias.name = name;
        alias.domain = new Alias.AliasDomain();
        alias.domain.name = domain;
        return alias;
    }

    public static AliasKey createKey(String name, String domain) {
        return new AliasKey(name, domain, name + "@" + domain);
    }

    public static AliasKey fromCache(String email) {
        String[] parts = email.split("@");
        return new AliasKey(parts[0], parts[1], email);
    }

    /**
     * Validate that a caller-supplied address has exactly one '@' with a
     * non-empty local part and domain, no whitespace, and no control
     * characters, i.e. is safe to pass to {@link #fromCache(String)}.
     * Callers are responsible for trimming leading/trailing whitespace
     * before calling this method; see {@link #normalize(String)}.
     */
    public static boolean isValidFormat(String email) {
        if (!isValidCharacters(email)) {
            return false;
        }
        int at = email.indexOf('@');
        return at > 0 && at == email.lastIndexOf('@') && at < email.length() - 1;
    }

    static boolean isValidCharacters(String value) {
        return value != null && value.chars()
                .noneMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c));
    }

    /**
     * Trim leading/trailing whitespace and validate the result with
     * {@link #isValidFormat(String)}.
     *
     * @return the trimmed value if it is a validly-formatted address, otherwise null
     */
    public static String normalize(String email) {
        if (email == null) {
            return null;
        }
        String trimmed = email.strip();
        return isValidFormat(trimmed) ? trimmed : null;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((email == null) ? 0 : email.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        AliasKey other = (AliasKey) obj;
        if (email == null) {
            if (other.email != null) {
                return false;
            }
        } else if (!email.equals(other.email)) {
            return false;
        }
        return true;
    }
}
