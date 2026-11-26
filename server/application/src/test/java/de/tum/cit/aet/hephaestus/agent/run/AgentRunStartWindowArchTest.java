package de.tum.cit.aet.hephaestus.agent.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaMethod;
import de.tum.cit.aet.hephaestus.architecture.HephaestusArchitectureTest;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

/** Each native query that takes a run lease belongs in the PostgreSQL eligibility test. */
class AgentRunStartWindowArchTest extends HephaestusArchitectureTest {

    private static final Pattern LEASE_WRITE =
            Pattern.compile("(?is)\\bUPDATE\\s+(?:public\\.)?agent_run\\s+(?:\\w+\\s+)?SET\\s+"
                    + "(?:(?:(?!\\b(?:WHERE|RETURNING)\\b)[^;])*?,\\s*)?"
                    + "(?:state\\s*=\\s*'LEASED'|lease_epoch\\s*=)"
                    + "|\\bINSERT\\s+INTO\\s+(?:public\\.)?agent_run\\b[^;]*'LEASED'");

    @Test
    void shouldCoverEveryNewRunLeaseAndCheckItsStartLimitsAtTheWrite() {
        var writers = classes.stream()
                .flatMap(owner -> owner.getMethods().stream())
                .filter(method -> method.isAnnotatedWith(Query.class))
                .filter(method -> LEASE_WRITE
                        .matcher(method.getAnnotationOfType(Query.class).value())
                        .find())
                .toList();

        String repository = AgentRunRepository.class.getName();
        assertThat(writers)
                .as("enroll every lease writer in AgentRunStartWindowIntegrationTest")
                .extracting(method -> method.getOwner().getName() + "." + method.getName())
                .containsExactlyInAnyOrder(repository + ".claim", repository + ".claimSubject");
        for (JavaMethod writer : writers) {
            String sql = writer.getAnnotationOfType(Query.class).value();
            assertThat(checksStartLimitsAtWrite(sql)).as(writer.getFullName()).isTrue();
        }
    }

    @Test
    void shouldRejectAClaimThatChecksStartLimitsOnlyWhenItPicksACandidate() {
        String claim = AgentRunRepository.CLAIMABLE + AgentRunRepository.CLAIM_ORDER + AgentRunRepository.LEASE;
        assertThat(checksStartLimitsAtWrite(claim)).isTrue();

        int atWrite = claim.lastIndexOf(AgentRunRepository.WITHIN_START_LIMITS);
        String mutation = claim.substring(0, atWrite)
                + claim.substring(atWrite + AgentRunRepository.WITHIN_START_LIMITS.length());

        assertThat(mutation).contains(AgentRunRepository.WITHIN_START_LIMITS);
        assertThat(checksStartLimitsAtWrite(mutation)).isFalse();
    }

    private static boolean checksStartLimitsAtWrite(String sql) {
        var write = LEASE_WRITE.matcher(sql);
        return write.find() && sql.substring(write.start()).contains(AgentRunRepository.WITHIN_START_LIMITS);
    }
}
