package de.tum.cit.aet.hephaestus.practices.dto;

import java.util.List;

/** Practice route names are reserved before 1.0; later static routes need a separate namespace. */
public final class DefinitionSlugs {
    public static final int MAX_LENGTH = 64;
    private static final String FORMAT = "[a-z0-9]+(?:-[a-z0-9]+)*";
    private static final String RESERVED_PRACTICE_NAMES =
            "definition-options|feedback|feedback-modes|items|practice-script-needs|releases|results|review-batches|review-context|review-requests|review-schedules|review-settings|reviewed|reviews|scans|standings|tiles|workspace-overview";
    // Unlike $, this end guard rejects a final newline in both Java and ECMAScript.
    private static final String END = "(?![\\s\\S])";
    public static final String DEFINITION_PATTERN = "^" + FORMAT + END;
    public static final String PRACTICE_PATTERN = "^(?!(?:" + RESERVED_PRACTICE_NAMES + ")$)" + FORMAT + END;

    private DefinitionSlugs() {}

    public static List<String> reservedPracticeNames() {
        return List.of(RESERVED_PRACTICE_NAMES.split("\\|"));
    }
}
