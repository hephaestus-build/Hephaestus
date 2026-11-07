package de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import java.util.Map;
import java.util.Set;

/** A provider's own answer for the emails of one repository. An adapter never infers a person from an email. */
public interface CommitIdentityProvider {
    IntegrationKind kind();

    /**
     * Asks the provider. It runs with no open transaction, and it returns an answer for each question it can answer.
     *
     * @return the answer per question; a question with no entry has no answer
     */
    Map<Question, Answer> answer(String nameWithOwner, long providerId, long scopeId, Set<Question> questions);

    record Question(String sha, String email) {}

    sealed interface Answer {
        /** The provider names this account, and {@code userId} is its local user. */
        record Account(long userId, long nativeId) implements Answer {}

        /** The provider found the commit and says that no account owns the email. */
        record NoAccount() implements Answer {}

        /** The provider names an account that was erased here. Nothing may link to it. */
        record Erased() implements Answer {}

        /** The provider's budget is spent. The question stays open, and it costs no attempt. */
        record Deferred() implements Answer {}
    }
}
