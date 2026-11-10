package de.tum.cit.aet.hephaestus.workspace.exception;

import java.io.Serial;

/** An owner role is a person's decision only, so it cannot follow the provider. */
public class OwnerRoleFollowsNoProviderException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public OwnerRoleFollowsNoProviderException(String workspaceSlug) {
        super(String.format(
                "An owner's role in workspace '%s' is always set by a person. Change the role first.", workspaceSlug));
    }
}
