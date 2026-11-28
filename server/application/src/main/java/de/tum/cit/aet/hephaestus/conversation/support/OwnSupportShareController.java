package de.tum.cit.aet.hephaestus.conversation.support;

import de.tum.cit.aet.hephaestus.core.RecentSignInExempt;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonAct;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The person's own support shares: to whom, until when, how often read. The person alone revokes one. */
@RestController
@ConditionalOnServerRole
@RequestMapping("/user/support-shares")
@Tag(name = "Support share")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class OwnSupportShareController {

    private final SupportShareService shares;

    @GetMapping
    @Operation(operationId = "listOwnSupportShares", summary = "List your support shares, also the ended ones")
    public ResponseEntity<List<SupportShareDTO>> list() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(shares.ownShares(PersonAct.fromPrincipal().requireSelf()));
    }

    @DeleteMapping("/{shareId}")
    @RecentSignInExempt(reason = "ends the caller's own share, which only narrows who reads; a new share is a new act")
    @Operation(
            operationId = "revokeSupportShare",
            summary = "Revoke a support share now",
            description = "The recipient can no longer read it. It stays in your list as revoked.")
    public ResponseEntity<SupportShareDTO> revoke(@PathVariable UUID shareId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(shares.revoke(PersonAct.fromPrincipal().requireSelf(), shareId));
    }
}
