package de.tum.cit.aet.hephaestus.integration.scm.domain.item;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AiChoice;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentityResolver;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonProcessingSuppression;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSourceAccess;
import de.tum.cit.aet.hephaestus.integration.core.spi.SourceReadAudience;
import de.tum.cit.aet.hephaestus.integration.scm.ScmPersonSourceIdentityContributor;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReviewRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Reads current access and consent only; historical content remains in retained custody. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ScmItemSourceAccess implements ItemSourceAccess {
    private final ScmItemAccess items;
    private final RepositoryRepository repositories;
    private final PullRequestReviewRepository reviews;
    private final ScmRepositoryAccess access;
    private final ScmPersonSourceIdentityContributor contributors;
    private final PersonProcessingSuppression suppression;
    private final PersonIdentityResolver identities;
    private final AccountDirectory accounts;

    @Override
    public Set<ItemKind> kinds() {
        return Set.of(
                ScmItemEvents.REPOSITORY,
                ScmItemEvents.FILE,
                ScmItemEvents.ISSUE,
                ScmItemEvents.PULL_REQUEST,
                ScmItemEvents.PULL_REQUEST_REVIEW);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean permits(ItemRef item, SourceUsePurpose purpose) {
        Objects.requireNonNull(purpose);
        return permitsItem(item, SourceReadAudience.PROCESSING);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean permits(ItemRef item, SourceUsePurpose purpose, SourceReadAudience audience) {
        Objects.requireNonNull(purpose);
        return permitsItem(item, Objects.requireNonNull(audience));
    }

    // The audience reaches every parent, and changes only whether a contributor's AI choice applies.
    private boolean permitsItem(ItemRef item, SourceReadAudience audience) {
        if (!kinds().contains(item.kind())) return false;
        try {
            items.require(item);
            Long source = item.sourceId();
            if (source != null
                    && suppression.isSourceSuppressed(
                            item.workspaceId(), item.kind().value(), source)) return false;
            if (item.kind().equals(ScmItemEvents.REPOSITORY)) {
                if (source == null || item.parentId() != null || item.path() != null) return false;
                var repository = repositories.findById(source);
                if (repository.isEmpty()) return false;
                access.authorize(item.workspaceId(), repository.orElseThrow());
                return true;
            }
            if (item.kind().equals(ScmItemEvents.FILE)) {
                if (source != null || item.path() == null) return false;
                return permitsItem(items.parent(item, ScmItemEvents.REPOSITORY), audience);
            }
            if (source == null || item.path() != null) return false;
            if (item.kind().equals(ScmItemEvents.PULL_REQUEST_REVIEW)) {
                var parent = items.parent(item, ScmItemEvents.PULL_REQUEST);
                if (!permitsItem(parent, audience)) return false;
                var review = reviews.findById(source);
                if (!review.map(row -> row.getPullRequest() != null
                                && Objects.equals(row.getPullRequest().getId(), parent.sourceId()))
                        .orElse(false)) return false;
            } else {
                var parent = items.parent(item, ScmItemEvents.REPOSITORY);
                if (!permitsItem(parent, audience)) return false;
                var work = items.work(item.kind(), source);
                if (!work.map(row -> !row.getDeleted()
                                && row.getRepositoryId() != null
                                && Objects.equals(row.getRepositoryId(), parent.sourceId()))
                        .orElse(false)) return false;
            }
            for (var identity : contributors.identitiesForSource(
                    item.workspaceId(), item.kind().value(), source)) {
                if (suppression.isSuppressed(identity.providerId(), identity.subject(), identity.teamId()))
                    return false;
                var accountId = identities.accountFor(identity);
                if (accountId.isPresent()) {
                    var account = accounts.find(accountId.orElseThrow());
                    if (account.isEmpty()) return false;
                    var person = account.orElseThrow();
                    if (suppression.isAccountSuppressed(person.id())
                            || (audience == SourceReadAudience.PROCESSING && person.aiChoice() == AiChoice.NO_AI)
                            || (person.status() != Account.Status.ACTIVE
                                    && person.status() != Account.Status.UNCLAIMED)) return false;
                }
            }
            return true;
        } catch (ItemCaptureException unavailable) {
            return false;
        } catch (ResponseStatusException conflict) {
            if (conflict.getStatusCode().value() == HttpStatus.CONFLICT.value()) return false;
            throw conflict;
        }
    }
}
