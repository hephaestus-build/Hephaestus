package de.tum.cit.aet.hephaestus.notification.preferences;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@ConditionalOnServerRole
@RestControllerAdvice(assignableTypes = NotificationPreferencesController.class)
class NotificationPreferencesControllerAdvice {
    // Two first writes can both observe no rows; the account/kind uniqueness constraint chooses one.
    @ExceptionHandler({DataIntegrityViolationException.class, ObjectOptimisticLockingFailureException.class})
    ProblemDetail concurrentUpdate() {
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.PRECONDITION_FAILED, "Notification preferences changed; reload before saving");
    }
}
