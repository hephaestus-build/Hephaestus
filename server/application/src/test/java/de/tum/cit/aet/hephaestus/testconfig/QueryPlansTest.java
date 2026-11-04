package de.tum.cit.aet.hephaestus.testconfig;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class QueryPlansTest extends BaseUnitTest {

    @Test
    void shouldNumberEachPlaceholderWhenTheStatementHasSeveral() {
        assertThat(QueryPlans.numberedParameters("select a from t where b=? and c in (?,?)"))
                .isEqualTo("select a from t where b=$1 and c in ($2,$3)");
    }

    @Test
    void shouldKeepQuestionMarksWhenTheyAreNotPlaceholders() {
        assertThat(QueryPlans.numberedParameters(
                        "select '?', \"a?\" from t -- why?\nwhere x=? /* ? */ and $q$?$q$ <> ? and j ?? 'k'"))
                .isEqualTo("select '?', \"a?\" from t -- why?\nwhere x=$1 /* ? */ and $q$?$q$ <> $2 and j ? 'k'");
    }
}
