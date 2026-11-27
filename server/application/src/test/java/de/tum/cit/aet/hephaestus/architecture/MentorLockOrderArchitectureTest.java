package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import de.tum.cit.aet.hephaestus.mentor.runtime.MentorAttempt.WorkClass;
import de.tum.cit.aet.hephaestus.mentor.runtime.MentorAttemptRepository;
import de.tum.cit.aet.hephaestus.mentor.runtime.MentorAttempts;
import de.tum.cit.aet.hephaestus.mentor.runtime.MentorSourceRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

/**
 * The dispatch grant and the commit lock a mentor request before its attempt (MENTOR-RUNTIME-CONTRACT.md § Attempt
 * proofs). A statement that row-locks both leaves their order to the query plan, so none may. A method that locks
 * both takes the request first.
 */
class MentorLockOrderArchitectureTest extends HephaestusArchitectureTest {
    private static final String REQUEST = "mentor_request";
    private static final String ATTEMPT = "mentor_attempt";
    private static final List<Class<?>> REPOSITORIES =
            List.of(MentorSourceRepository.class, MentorAttemptRepository.class);
    private static final Pattern ROW_LOCK = Pattern.compile(
            "\\bFOR\\s+(?:NO\\s+KEY\\s+UPDATE|KEY\\s+SHARE|UPDATE|SHARE)\\b(?:\\s+OF\\s+(\\w+))?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern WRITE =
            Pattern.compile("\\b(?:UPDATE|DELETE\\s+FROM)\\s+(mentor_\\w+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TABLE = Pattern.compile(
            "\\b(FROM|JOIN)\\s+(mentor_\\w+)(?:\\s+(?:AS\\s+)?(?!(?:WHERE|ON|JOIN|FOR|ORDER|LIMIT|GROUP)\\b)(\\w+))?",
            Pattern.CASE_INSENSITIVE);

    @Test
    void shouldLockNoMentorRequestAndAttemptInOneStatement() {
        for (Class<?> repository : REPOSITORIES) {
            for (var method : repository.getDeclaredMethods()) {
                Query query = method.getAnnotation(Query.class);
                if (query == null) continue;
                assertThat(locked(query.value()).containsAll(Set.of(REQUEST, ATTEMPT)))
                        .as(
                                "%s.%s row-locks the request and the attempt together",
                                repository.getSimpleName(), method.getName())
                        .isFalse();
            }
        }
    }

    @Test
    void shouldLockTheRequestBeforeTheAttemptInEveryMethodThatLocksBoth() {
        var both = new ArrayList<String>();
        classes.stream().flatMap(type -> type.getMethods().stream()).forEach(method -> {
            OptionalInt request = firstLock(method, REQUEST);
            OptionalInt attempt = firstLock(method, ATTEMPT);
            if (request.isEmpty() || attempt.isEmpty()) return;
            both.add(method.getFullName());
            assertThat(request.getAsInt())
                    .as("%s locks the attempt before the request", method.getFullName())
                    .isLessThan(attempt.getAsInt());
        });
        // The rule is not vacuous: the claim locks both.
        assertThat(both)
                .contains(classes.get(MentorAttempts.class)
                        .getMethod("claim", long.class, WorkClass.class, int.class, Duration.class)
                        .getFullName());
    }

    @Test
    void shouldReadTheLockedTablesOfAStatement() {
        assertThat(locked("SELECT 1 FROM mentor_attempt a JOIN mentor_request r ON r.id = a.request_id FOR UPDATE"))
                .containsExactlyInAnyOrder(ATTEMPT, REQUEST);
        assertThat(locked("SELECT s.id, (SELECT r.id FROM mentor_request r) FROM mentor_source s FOR UPDATE OF s"))
                .containsExactly("mentor_source");
        assertThat(locked("SELECT a.id FROM mentor_attempt a JOIN mentor_request r ON r.id = a.request_id"))
                .isEmpty();
        assertThat(locked("WITH d AS (SELECT id FROM mentor_attempt FOR UPDATE SKIP LOCKED) UPDATE mentor_attempt t"))
                .containsExactly(ATTEMPT);
        assertThat(locked("INSERT INTO mentor_attempt(id) SELECT r.id FROM mentor_request r"))
                .isEmpty();
    }

    /** The line of the method's first call that row-locks the table, if any. */
    private static OptionalInt firstLock(JavaMethod method, String table) {
        return method.getMethodCallsFromSelf().stream()
                .filter(call -> REPOSITORIES.stream()
                        .anyMatch(type -> call.getTargetOwner().isEquivalentTo(type)))
                .filter(call ->
                        sql(call).map(sql -> locked(sql).contains(table)).orElse(false))
                .mapToInt(JavaMethodCall::getLineNumber)
                .min();
    }

    private static Optional<String> sql(JavaMethodCall call) {
        return call.getTarget()
                .resolveMember()
                .flatMap(member -> member.tryGetAnnotationOfType(Query.class))
                .map(Query::value);
    }

    /**
     * The mentor tables that one statement row-locks: the targets of its {@code UPDATE} and {@code DELETE}, and for each
     * locking clause the tables it names, or else the tables of the {@code FROM} that precedes it. An insert locks no
     * existing row of its own.
     */
    static Set<String> locked(String sql) {
        var tables = new TreeSet<String>();
        Matcher write = WRITE.matcher(sql);
        while (write.find()) tables.add(write.group(1).toLowerCase(Locale.ROOT));
        Matcher lock = ROW_LOCK.matcher(sql);
        while (lock.find()) {
            var named = new ArrayList<String[]>();
            Matcher table = TABLE.matcher(sql.substring(0, lock.start()));
            while (table.find()) {
                if (table.group(1).equalsIgnoreCase("FROM")) named.clear();
                named.add(new String[] {table.group(2).toLowerCase(Locale.ROOT), table.group(3)});
            }
            String of = lock.group(1);
            Matcher every = TABLE.matcher(sql);
            if (of != null) {
                while (every.find()) {
                    if (of.equalsIgnoreCase(every.group(3)) || of.equalsIgnoreCase(every.group(2))) {
                        tables.add(every.group(2).toLowerCase(Locale.ROOT));
                    }
                }
            } else {
                named.forEach(entry -> tables.add(entry[0]));
            }
        }
        return tables;
    }
}
