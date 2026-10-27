package de.tum.cit.aet.hephaestus.integration.core.spi;

import com.fasterxml.jackson.annotation.JsonValue;

public enum CandidateRefusal {
    MALFORMED_CANDIDATE("malformed_candidate"),
    OUTCOME_PROBLEM_MISMATCH("outcome_problem_mismatch"),
    WARRANT_OUTCOME_MISMATCH("warrant_outcome_mismatch"),
    RESULT_CITATIONS_REQUIRED("result_citations_required"),
    MET_COVERAGE_INCOMPLETE("met_coverage_incomplete"),
    COVERAGE_INCONSISTENT("coverage_inconsistent"),
    EXAMPLE_OUTCOME_MISMATCH("example_outcome_mismatch"),
    EXAMPLE_OVERLAPS_PROBLEM("example_overlaps_problem"),
    BASIS_CITATION_OUT_OF_RANGE("basis_citation_out_of_range"),
    DUPLICATE_PROBLEM_KEY("duplicate_problem_key"),
    BASIS_STATE_UNSUPPORTED("basis_state_unsupported"),
    BASIS_REVISION_UNSUPPORTED("basis_revision_unsupported"),
    QUOTE_UNVERIFIED("quote_unverified"),
    SOURCE_UNAVAILABLE("source_unavailable"),
    BASIS_WITNESS_MISSING("basis_witness_missing"),
    DUTY_QUOTE_UNVERIFIED("duty_quote_unverified"),
    CONSULTED_SOURCE_UNREAD("consulted_source_unread");

    private final String wireValue;

    CandidateRefusal(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }
}
