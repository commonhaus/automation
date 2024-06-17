package org.commonhaus.automation.admin.forwardemail;

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
