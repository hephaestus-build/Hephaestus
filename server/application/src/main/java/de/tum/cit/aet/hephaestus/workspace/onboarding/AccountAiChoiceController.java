package de.tum.cit.aet.hephaestus.workspace.onboarding;

import de.tum.cit.aet.hephaestus.core.auth.web.CurrentAccount;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiChoice;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in account's AI choice, which holds in every workspace it is a member of. */
@RestController
@ConditionalOnServerRole
@RequestMapping("/user/ai-choice")
@Tag(name = "Account", description = "User preferences")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class AccountAiChoiceController {
    private final WorkspaceOnboardingService service;

    public record AccountAiChoiceRequestDTO(
            @NonNull @NotNull MemberAiChoice choice) {}

    @GetMapping
    @Operation(summary = "Get your AI choice", operationId = "getAccountAiChoice")
    public AccountAiChoiceDTO getAccountAiChoice() {
        return service.accountChoice(CurrentAccount.requireId());
    }

    @PutMapping
    @Operation(summary = "Change your AI choice for every workspace you are in", operationId = "updateAccountAiChoice")
    public AccountAiChoiceDTO updateAccountAiChoice(@Valid @RequestBody AccountAiChoiceRequestDTO request) {
        return service.chooseForAccount(CurrentAccount.requireId(), request.choice());
    }
}
