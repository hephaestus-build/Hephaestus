package de.tum.cit.aet.hephaestus.activity.overview;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.activity.ActivityEventType;
import de.tum.cit.aet.hephaestus.activity.overview.ActivityQueryRepository.WorkGroup;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The work page query spells out one count column per kind, since a query annotation must be a constant. This keeps
 * those columns, {@link ActivityKind} and {@link WorkGroup#count} saying the same thing.
 */
@Tag("unit")
class ActivityWorkPageQueryTest {

    private static final Pattern COLUMN = Pattern.compile("COUNT\\(\\*\\) FILTER \\(WHERE grouped\\.eventType = "
            + Pattern.quote(ActivityQueryRepository.EVENT)
            + "(\\w+)\\) AS (\\w+)");

    @Test
    void shouldCountEveryKindInItsOwnColumnWhenTheWorkPageIsRead() {
        Map<ActivityEventType, String> columns = new HashMap<>();
        Matcher matcher = COLUMN.matcher(ActivityQueryRepository.WORK_PAGE);
        while (matcher.find()) {
            columns.put(ActivityEventType.valueOf(matcher.group(1)), matcher.group(2));
        }

        assertThat(columns.keySet())
                .as("one column for each kind's event type, and no other")
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(ActivityKind.values())
                        .map(ActivityKind::eventType)
                        .toList());
        assertThat(columns.values()).doesNotHaveDuplicates();

        WorkGroup group = columnNamed();
        for (ActivityKind kind : ActivityKind.values()) {
            assertThat(group.count(kind))
                    .as("%s reads the column of %s", kind, kind.eventType())
                    .isEqualTo(Objects.requireNonNull(columns.get(kind.eventType()))
                            .hashCode());
        }
    }

    /** A work group whose every count is the hash of the column the getter reads. */
    private static WorkGroup columnNamed() {
        InvocationHandler handler = (proxy, method, arguments) -> {
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, arguments);
            }
            String column = Character.toLowerCase(method.getName().charAt(3))
                    + method.getName().substring(4);
            return (long) column.hashCode();
        };
        return (WorkGroup)
                Proxy.newProxyInstance(WorkGroup.class.getClassLoader(), new Class<?>[] {WorkGroup.class}, handler);
    }
}
