package de.tum.cit.aet.hephaestus.core.web;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class CsvTest extends BaseUnitTest {

    @Test
    void shouldQuoteEveryCellAndEscapeWhatWouldBreakTheLayout() {
        StringBuilder out = new StringBuilder();

        Csv.appendRow(
                out, Arrays.<@Nullable String>asList("plain", "a,b", "say \"hi\"", "one\r\ntwo\rthree", "", null));

        assertThat(out.toString()).isEqualTo("\"plain\",\"a,b\",\"say \"\"hi\"\"\",\"one\ntwo\nthree\",\"\",\"\"\n");
    }

    @Test
    void shouldNeutraliseCellsASpreadsheetWouldRunAsAFormula() {
        StringBuilder out = new StringBuilder();

        Csv.appendRow(out, List.of("=1+1", "+1", "-1", "@SUM", "\tx", "\rx", "x=1"));

        assertThat(out.toString()).isEqualTo("\"'=1+1\",\"'+1\",\"'-1\",\"'@SUM\",\"'\tx\",\"'\nx\",\"x=1\"\n");
    }
}
