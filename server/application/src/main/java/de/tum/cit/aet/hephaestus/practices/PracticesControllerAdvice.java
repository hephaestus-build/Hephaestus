package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.core.LoggingUtils;
import de.tum.cit.aet.hephaestus.practices.curated.CuratedCatalogConflictException;
import de.tum.cit.aet.hephaestus.practices.curated.CuratedPreconditionRequiredException;
import de.tum.cit.aet.hephaestus.practices.curated.StaleCuratedEntryException;
import de.tum.cit.aet.hephaestus.practices.curated.adoption.CatalogAdoptionPreconditionRequiredException;
import de.tum.cit.aet.hephaestus.practices.curated.adoption.StaleCatalogAdoptionPlanException;
import de.tum.cit.aet.hephaestus.practices.review.InvalidReviewCoverageException;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewPreconditionRequiredException;
import de.tum.cit.aet.hephaestus.practices.review.StalePracticeReviewSettingsException;
import java.util.Objects;
import java.util.Optional;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Error mapper for practices-specific exceptions.
 * Kept in the practices module to avoid a cyclic dependency between practices and core.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PracticesControllerAdvice {

    @ExceptionHandler(PracticeSlugConflictException.class)
    ProblemDetail handleSlugConflict(PracticeSlugConflictException exception) {
        return problem(
                HttpStatus.CONFLICT,
                "Practice slug conflict",
                Objects.toString(exception.getMessage(), "Request failed"));
    }

    @ExceptionHandler(PracticeGroupSlugConflictException.class)
    ProblemDetail handleGroupSlugConflict(PracticeGroupSlugConflictException exception) {
        return problem(
                HttpStatus.CONFLICT,
                "Practice group slug conflict",
                Objects.toString(exception.getMessage(), "Request failed"));
    }

    @ExceptionHandler(CuratedCatalogConflictException.class)
    ProblemDetail handleCuratedConflict(CuratedCatalogConflictException exception) {
        return problem(
                HttpStatus.CONFLICT,
                "Practice catalog conflict",
                Objects.toString(exception.getMessage(), "Request failed"));
    }

    @ExceptionHandler(StaleCuratedEntryException.class)
    ProblemDetail handleStaleCuratedEntry(StaleCuratedEntryException exception) {
        return problem(
                HttpStatus.PRECONDITION_FAILED,
                "Practice catalog changed",
                Objects.toString(exception.getMessage(), "Request failed"));
    }

    @ExceptionHandler(CuratedPreconditionRequiredException.class)
    ProblemDetail handleCuratedPreconditionRequired(CuratedPreconditionRequiredException exception) {
        return problem(
                HttpStatus.PRECONDITION_REQUIRED,
                "Practice catalog version required",
                Objects.toString(exception.getMessage(), "Request failed"));
    }

    @ExceptionHandler(StaleCatalogAdoptionPlanException.class)
    ProblemDetail handleStaleAdoptionPlan(StaleCatalogAdoptionPlanException exception) {
        return problem(
                HttpStatus.PRECONDITION_FAILED,
                "Practice adoption preview changed",
                Objects.toString(exception.getMessage(), "Request failed"));
    }

    @ExceptionHandler(CatalogAdoptionPreconditionRequiredException.class)
    ProblemDetail handleAdoptionPreconditionRequired(CatalogAdoptionPreconditionRequiredException exception) {
        return problem(
                HttpStatus.PRECONDITION_REQUIRED,
                "Practice adoption preview required",
                Objects.toString(exception.getMessage(), "Request failed"));
    }

    @ExceptionHandler(StalePracticeReviewSettingsException.class)
    ProblemDetail handleStaleReviewSettings(StalePracticeReviewSettingsException exception) {
        return problem(
                HttpStatus.PRECONDITION_FAILED,
                "Practice review settings changed",
                Objects.toString(exception.getMessage(), "Request failed"));
    }

    @ExceptionHandler(PracticeReviewPreconditionRequiredException.class)
    ProblemDetail handleReviewPreconditionRequired(PracticeReviewPreconditionRequiredException exception) {
        return problem(
                HttpStatus.PRECONDITION_REQUIRED,
                "Practice review settings version required",
                Objects.toString(exception.getMessage(), "Request failed"));
    }

    @ExceptionHandler(PracticeReleasePreconditionRequiredException.class)
    ProblemDetail handleReleasePreconditionRequired(PracticeReleasePreconditionRequiredException exception) {
        return problem(
                HttpStatus.PRECONDITION_REQUIRED,
                "Practice release version required",
                Objects.toString(exception.getMessage(), "Request failed"));
    }

    @ExceptionHandler(StalePracticeReleaseException.class)
    ProblemDetail handleStaleRelease(StalePracticeReleaseException exception) {
        return problem(
                HttpStatus.PRECONDITION_FAILED,
                "Practice release changed",
                Objects.toString(exception.getMessage(), "Request failed"));
    }

    @ExceptionHandler(InvalidReviewCoverageException.class)
    ProblemDetail handleInvalidReviewCoverage(InvalidReviewCoverageException exception) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "Invalid practice review coverage",
                Objects.toString(exception.getMessage(), "Request failed"));
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setTitle(title);
        problem.setDetail(Optional.ofNullable(detail)
                .map(LoggingUtils::sanitizeForLog)
                .filter(s -> !s.isBlank())
                .orElse("The practice request could not be processed."));
        return problem;
    }
}
