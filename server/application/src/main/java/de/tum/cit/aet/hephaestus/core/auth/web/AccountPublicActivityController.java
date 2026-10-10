package de.tum.cit.aet.hephaestus.core.auth.web;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountPublicActivity;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.swagger.v3.oas.annotations.Operation;
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

@RestController
@ConditionalOnServerRole
@RequestMapping("/user/public-activity")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class AccountPublicActivityController {
    private final AccountPublicActivity choice;

    public record PublicActivityChoiceDTO(@NonNull @NotNull Boolean visible) {}

    @GetMapping
    @Operation(operationId = "getPublicActivityChoice", summary = "Get your account-wide public activity choice")
    public PublicActivityChoiceDTO get() {
        return new PublicActivityChoiceDTO(choice.visible(CurrentAccount.requireId()));
    }

    @PutMapping
    @Operation(operationId = "updatePublicActivityChoice", summary = "Show or hide your activity on all public pages")
    public PublicActivityChoiceDTO update(@Valid @RequestBody PublicActivityChoiceDTO body) {
        return new PublicActivityChoiceDTO(choice.setVisible(CurrentAccount.requireId(), body.visible()));
    }
}
