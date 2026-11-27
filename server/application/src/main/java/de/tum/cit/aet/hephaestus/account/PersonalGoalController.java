package de.tum.cit.aet.hephaestus.account;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.RequiresRecentSignIn;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonAct;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.UserViewContextHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@ConditionalOnServerRole
@RequiredArgsConstructor
@Validated
@RequestMapping("/user/personal-goal")
@Tag(name = "Account")
@PreAuthorize("isAuthenticated()")
@WorkspaceAgnostic("A personal goal belongs to the authenticated account")
public class PersonalGoalController {
    private final PersonalGoals goals;

    @GetMapping
    @Operation(summary = "Read your personal goal", operationId = "getPersonalGoal")
    public PersonalGoalDTO read() {
        return goals.read(owner());
    }

    @PutMapping
    @Operation(summary = "Save or edit your personal goal", operationId = "savePersonalGoal")
    public PersonalGoalDTO save(@Valid @RequestBody SavePersonalGoalDTO request) {
        return goals.save(owner(), request.revision(), request.words());
    }

    @DeleteMapping
    @Operation(summary = "Forget your personal goal", operationId = "forgetPersonalGoal")
    @ApiResponse(
            responseCode = "200",
            description = "Goal forgotten",
            content = @Content(schema = @Schema(implementation = PersonalGoalDTO.class)))
    @ApiResponse(
            responseCode = "403",
            description = "The caller has not signed in recently (problem type step-up-required)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Audited(ledger = AuditLedger.AUTH_EVENT, type = "PERSON_DATA_ERASURE_REQUESTED")
    @RequiresRecentSignIn
    public PersonalGoalDTO forget(@RequestParam @PositiveOrZero long revision) {
        return goals.forget(owner(), revision);
    }

    private static PersonAct owner() {
        if (UserViewContextHolder.get() != null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only you can open your personal goal.");
        }
        return PersonAct.fromPrincipal();
    }
}
