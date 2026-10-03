package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataSelection;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataSelection.RowKey;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PersonScopeTest {

    @Test
    void shouldKeepTheResolvedIdentityScopeImmutable() {
        var identity = new PersonIdentity(1, "42", null);
        var identities = new ArrayList<>(List.of(identity));
        var users = new ArrayList<>(List.of(7L));
        var scope = new PersonScope(null, identities, users);
        identities.clear();
        users.clear();

        assertThat(scope.identities()).containsExactly(identity);
        assertThat(scope.userIds()).containsExactly(7L);
        assertThatThrownBy(() -> scope.identities().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldFreezeCompositeKeysWithoutKeepingRowContent() {
        var columns = new HashMap<>(Map.of("workspace_id", "3", "user_id", "7"));
        var key = new RowKey(columns);
        var rows = new ArrayList<>(List.of(key));
        var selection = new PersonDataSelection(rows);
        columns.clear();
        rows.clear();

        assertThat(selection.rows()).containsExactly(new RowKey(Map.of("workspace_id", "3", "user_id", "7")));
        assertThatThrownBy(() -> selection.rows().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> key.columns().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldRejectDuplicateAndEmptyPrimaryKeys() {
        var key = new RowKey(Map.of("id", "7"));
        assertThatThrownBy(() -> new PersonDataSelection(List.of(key, key)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RowKey(Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RowKey(Map.of("", "7"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(new PersonDataSelection(List.of()).rows()).isEmpty();
    }

    @Test
    void shouldRejectMissingProviderOrNativeSubject() {
        assertThatThrownBy(() -> new PersonIdentity(0, "42", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PersonIdentity(1, " ", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PersonIdentity(1, "42", " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PersonIdentity(1, "a".repeat(256), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
