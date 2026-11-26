package de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity;

import de.tum.cit.aet.hephaestus.integration.core.connection.ActiveScmConnections.ActiveScm;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** A provider's own answer for the emails of one repository. An adapter never infers a person from an email. */
public interface CommitIdentityProvider {
    IntegrationKind kind();

    /**
     * Asks the provider. It runs with no open transaction, and it returns an answer for each question it can answer.
     *
     * @return the answers and any pause for the credential that made the request
     */
    Batch answer(String nameWithOwner, long providerId, long scopeId, ActiveScm credential, Set<Question> questions);

    record Question(String sha, String email) {}

    record Batch(Map<Question, Answer> answers, @Nullable Pause pause) {}

    record Pause(Instant until, CredentialGeneration credential, boolean alreadyStored) {
        public Pause(Instant until, CredentialGeneration credential) {
            this(until, credential, false);
        }
    }

    sealed interface Answer {
        /** The provider names this account, and {@code userId} is its local user. */
        record Account(long userId, long nativeId) implements Answer {}

        /** The provider found the commit and says that no account owns the email. */
        record NoAccount() implements Answer {}

        /** The provider names an account that was erased here. Nothing may link to it. */
        record Erased() implements Answer {}

        /** The credential changed during the read. Try again on the next run. */
        record RetryAfterCredentialChange() implements Answer {}
    }

    record CredentialGeneration(long connectionId, long generation) {}
}
