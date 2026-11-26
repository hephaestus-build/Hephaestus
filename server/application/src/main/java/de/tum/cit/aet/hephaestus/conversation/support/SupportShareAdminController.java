package de.tum.cit.aet.hephaestus.conversation.support;

import de.tum.cit.aet.hephaestus.core.RequireInstanceAdmin;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonAct;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The shares addressed to the signed-in instance admin. It is the one staff route that returns a person's words, and
 * only those the person shared with this admin, while the share runs (ADR 0164).
 */
@RestController
@ConditionalOnServerRole
@RequestMapping("/admin/support-shares")
@RequireInstanceAdmin
@Tag(name = "Support share")
@RequiredArgsConstructor
public class SupportShareAdminController {

    private final SupportShareService shares;

    @GetMapping
    @Operation(operationId = "listReceivedSupportShares", summary = "List the support shares you can read now")
    public ResponseEntity<List<SupportShareReceivedDTO>> list() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(shares.received(PersonAct.fromPrincipal().requireSelf()));
    }

    @GetMapping("/{shareId}")
    @Operation(
            operationId = "readSupportShare",
            summary = "Read a support share addressed to you",
            description = "Each read counts, and the person sees the count and the time of the last read.")
    public ResponseEntity<SupportShareReceivedDTO> read(@PathVariable UUID shareId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(shares.read(PersonAct.fromPrincipal().requireSelf(), shareId));
    }
}
