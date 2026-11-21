package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.practices.dto.PracticeFileProblemDTO;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** A practice folder that cannot become a practice; the problem detail lists each problem with its file and line. */
public final class PracticeFolderInvalidException extends ErrorResponseException {
    private static final long serialVersionUID = 1L;

    public PracticeFolderInvalidException(List<PracticeFileProblemDTO> problems) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, problem(problems), null);
    }

    private static ProblemDetail problem(List<PracticeFileProblemDTO> problems) {
        var detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_CONTENT, "The practice folder is not valid. Nothing was saved.");
        detail.setProperty("problems", List.copyOf(problems));
        return detail;
    }
}
