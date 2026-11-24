package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.web.OffsetPageDTO;
import de.tum.cit.aet.hephaestus.workspace.dto.SpaceDTO;
import java.security.SecureRandom;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A person's own space (ADR 0126): a workspace that only its owner enters and that belongs to no team. It is created
 * the first time the person needs it, once, and it ends with the account ({@code PersonalSpaceErasureAdapter}).
 */
@Service
@RequiredArgsConstructor
public class PersonalSpaces {

    /** The space's name in every list. A person never chooses it. */
    static final String DISPLAY_NAME = "Personal space";

    /** Candidate slugs to try when one is already reserved. With 80 random bits, the second is never needed. */
    private static final int SLUG_ATTEMPTS = 5;

    private static final char[] BASE32 = "abcdefghijklmnopqrstuvwxyz234567".toCharArray();

    private static final SecureRandom RANDOM = new SecureRandom();

    private final WorkspaceRepository workspaces;
    private final MembershipRepository memberships;
    private final WorkspaceSlugService slugs;
    private final AccountDirectory accounts;

    /**
     * The account's personal space with the owner's active membership, created if the account has none. The lock on
     * the account row makes concurrent calls for one account wait for each other, and keeps a deleted account from
     * getting a space.
     */
    @Transactional
    public Workspace ensure(long accountId) {
        if (!accounts.lockWhileActive(accountId)) {
            throw new EntityNotFoundException("Account", accountId);
        }
        var space = workspaces.findByPersonalAccountId(accountId).orElseGet(() -> {
            workspaces.insertPersonalSpace(accountId, unreservedSlug(), DISPLAY_NAME);
            return workspaces.findByPersonalAccountId(accountId).orElseThrow();
        });
        memberships.keepPersonalOwner(space.getId(), accountId);
        return space;
    }

    /**
     * One page of the account's spaces: its personal space first, then the team workspaces that the workspace registry
     * lists for it, by name and slug. The personal space exists once {@link #ensure} returned.
     */
    @Transactional(readOnly = true)
    public OffsetPageDTO<SpaceDTO> spaces(long accountId, int page, int size) {
        // An erasure that ended the space after ensure() leaves the account with no list to show.
        if (workspaces.findByPersonalAccountId(accountId).isEmpty()) {
            throw new EntityNotFoundException("Account", accountId);
        }
        return OffsetPageDTO.from(
                workspaces.findSpaces(accountId, PageRequest.of(page, size)).map(SpaceDTO::of));
    }

    private String unreservedSlug() {
        for (int attempt = 0; attempt < SLUG_ATTEMPTS; attempt++) {
            String slug = newSlug();
            if (!slugs.isReserved(slug)) {
                return slug;
            }
        }
        throw new IllegalStateException("No unreserved personal space slug after " + SLUG_ATTEMPTS + " attempts");
    }

    /** {@code me--} and 16 base32 characters: 80 random bits, so the address does not reveal or guess a person. */
    static String newSlug() {
        var slug = new StringBuilder("me--");
        for (int i = 0; i < 16; i++) {
            slug.append(BASE32[RANDOM.nextInt(BASE32.length)]);
        }
        return slug.toString();
    }
}
