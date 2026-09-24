package de.tum.cit.aet.hephaestus.integration.scm.domain.organization;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@NoArgsConstructor
public class OrganizationMembershipId implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private @Nullable Long organizationId;
    private @Nullable Long userId;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof OrganizationMembershipId that)) {
            return false;
        }
        return Objects.equals(organizationId, that.organizationId) && Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(organizationId, userId);
    }
}
