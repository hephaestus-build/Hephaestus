package de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity.CommitIdentityProvider.Answer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity.CommitIdentityResolution.State;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmDomainEvent;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Writes one provider answer in one short transaction, with a record of each link it changes. */
@Service
@RequiredArgsConstructor
public class CommitIdentityResolutionService {

    /** Stands for "no user" in the change statements. A user id is never 0. */
    private static final long NO_USER = 0L;

    private final CommitIdentityResolutionRepository resolutions;
    private final CommitIdentityChangeRepository changes;
    private final PersonDataWriteFence fence;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public record Result(String outcome, int changedLinks) {}

    /** Applies an answer for the email of one queue row. {@link Answer.Deferred} never reaches this method. */
    @Transactional
    public Result apply(long resolutionId, long repositoryId, long providerId, String email, Answer answer) {
        if (resolutions.lockById(resolutionId).isEmpty()) {
            return new Result("skipped", 0);
        }
        return switch (answer) {
            case Answer.Account account -> {
                var person = new PersonIdentity(providerId, Long.toString(account.nativeId()), null);
                if (!fence.holdForWrite(List.of(person))) {
                    yield finish(resolutionId, State.RESOLVED, "erased", 0);
                }
                int changed = write(repositoryId, email, account.userId(), "resolved");
                yield finish(resolutionId, State.RESOLVED, "resolved", changed);
            }
            case Answer.NoAccount ignored -> {
                int changed = write(repositoryId, email, NO_USER, "no_account");
                yield finish(resolutionId, State.NO_ACCOUNT, "no_account", changed);
            }
            case Answer.Erased ignored -> finish(resolutionId, State.RESOLVED, "erased", 0);
            case Answer.Deferred ignored -> throw new IllegalArgumentException("A deferred answer writes nothing");
        };
    }

    /** Ends a question that can never be answered, or counts one attempt. It changes no link. */
    @Transactional
    public Result miss(long resolutionId, int attempts, int maxAttempts) {
        if (resolutions.lockById(resolutionId).isEmpty()) {
            return new Result("skipped", 0);
        }
        if (attempts + 1 >= maxAttempts) {
            return finish(resolutionId, State.UNANSWERED, "unanswered", 0);
        }
        resolutions.retryLater(resolutionId);
        return new Result("retry", 0);
    }

    private int write(long repositoryId, String email, long userId, String outcome) {
        var now = clock.instant();
        return changes.changeAuthors(repositoryId, email, userId, outcome, now)
                + changes.changeCommitters(repositoryId, email, userId, outcome, now)
                + changes.changeContributors(repositoryId, email, userId, outcome, now);
    }

    private Result finish(long resolutionId, State state, String outcome, int changedLinks) {
        resolutions.finish(resolutionId, state.name(), clock.instant());
        if (state == State.RESOLVED || state == State.NO_ACCOUNT) {
            events.publishEvent(new ScmDomainEvent.CommitIdentityCorrected(resolutionId));
        }
        return new Result(outcome, changedLinks);
    }
}
