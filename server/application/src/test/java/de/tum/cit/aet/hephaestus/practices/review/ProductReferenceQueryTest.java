package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.AccountReferenceService;
import de.tum.cit.aet.hephaestus.integration.core.framework.ItemDescriptorRegistry;
import de.tum.cit.aet.hephaestus.integration.core.framework.RegisteredItemLabels;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemLabel;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;

class ProductReferenceQueryTest extends BaseUnitTest {
    @Mock
    ProductItemRepository rows;

    @Mock
    RegisteredItemLabels labels;

    @Mock
    ItemDescriptorRegistry catalog;

    @ParameterizedTest
    @CsvSource({"GITHUB,#42,42", "GITLAB,!5,5"})
    void shouldKeepTheNativeItemLabelSeparateFromItsTitle(IntegrationKind provider, String nativeLabel, int number) {
        var item = new ItemRef(1L, 2L, new ItemKind("scm.pull_request"), 3L, null, null);
        var stored = mock(ProductItemRepository.ItemRow.class);
        when(stored.ref(1L)).thenReturn(item);
        when(rows.items(1L, List.of(2L))).thenReturn(List.of(stored));
        var label = new ItemLabel(2L, nativeLabel, "Explain the change", "pull request", number, null, null, provider);
        when(labels.labels(1L, List.of(item))).thenReturn(Map.of(2L, label));
        var references = new ProductReferenceQuery(rows, mock(AccountReferenceService.class), labels, catalog);
        var result = Objects.requireNonNull(references.items(1L, List.of(2L)).get(2L));
        assertThat(result.label()).isEqualTo(nativeLabel);
        assertThat(result.title()).isEqualTo("Explain the change");
        assertThat(result.provider()).isEqualTo(provider);
    }
}
