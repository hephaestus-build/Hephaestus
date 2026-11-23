package de.tum.cit.aet.hephaestus.core.exception;

import de.tum.cit.aet.hephaestus.core.web.LabelDTO;
import de.tum.cit.aet.hephaestus.core.web.LabelDTO.LabelParamDTO;
import de.tum.cit.aet.hephaestus.core.web.MentoringCopy;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

public final class MentoringProblems {

    private MentoringProblems() {}

    public static ProblemDetail changedSinceShown(String what) {
        return problem(ProblemType.CHANGED_SINCE_SHOWN, HttpStatus.CONFLICT, Map.of("what", what));
    }

    public static ProblemDetail turnedOff(String what, String by) {
        return problem(ProblemType.TURNED_OFF, HttpStatus.CONFLICT, Map.of("what", what, "by", by));
    }

    public static ProblemDetail noModel() {
        return problem(ProblemType.NO_MODEL, HttpStatus.CONFLICT, Map.of());
    }

    public static ProblemDetail checkInInvalid(String field) {
        return problem(ProblemType.CHECK_IN_INVALID, HttpStatus.UNPROCESSABLE_CONTENT, Map.of("field", field));
    }

    private static ProblemDetail problem(ProblemType type, HttpStatus status, Map<String, String> values) {
        String code = "problem." + type.slug();
        var params = values.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, entry -> new LabelParamDTO("value", entry.getValue(), null, null, null)));
        var label = new LabelDTO(code, params.isEmpty() ? null : params, MentoringCopy.text(code));
        var problem = type.labelledProblem(status, status.getReasonPhrase(), label);
        values.forEach(problem::setProperty);
        return problem;
    }
}
