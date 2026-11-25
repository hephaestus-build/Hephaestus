package de.tum.cit.aet.hephaestus.testconfig;

import de.tum.cit.aet.hephaestus.core.authority.spi.AuthorityGenerations;
import java.util.OptionalLong;

/** Authorization generations that never move: every workspace and person stays at generation 0, so all authority holds. */
public final class UnchangedAuthority implements AuthorityGenerations {

    @Override
    public OptionalLong workspace(long workspaceId) {
        return OptionalLong.of(0);
    }

    @Override
    public OptionalLong workspaceHeld(long workspaceId) {
        return OptionalLong.of(0);
    }

    @Override
    public OptionalLong account(long accountId) {
        return OptionalLong.of(0);
    }

    @Override
    public OptionalLong accountHeld(long accountId) {
        return OptionalLong.of(0);
    }
}
