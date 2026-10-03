package de.tum.cit.aet.hephaestus.agent.gateway;

import de.tum.cit.aet.hephaestus.agent.runtime.SandboxOutputArchive;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/llm/runtime/{id}")
@Hidden
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
@ConditionalOnWorkerRole
@WorkspaceAgnostic("Gateway authentication validates current ownership; session credentials bind its exact workspace")
public class SandboxWorkspaceController {
    private final SandboxGatewaySessions sessions;

    @GetMapping
    public Capabilities capabilities(@PathVariable UUID id, @RequestHeader("Authorization") String authorization) {
        var session = sessions.require(id, authorization);
        return new Capabilities(
                3, sessions.workspaceByteBudget(), SandboxOutputArchive.MAX_OUTPUT_BYTES, session.frameByteBudget());
    }

    public record Capabilities(
            int protocolVersion,
            long workspaceByteBudget,
            long resultByteBudget,
            @Nullable Integer frameByteBudget) {}

    @GetMapping("/workspace")
    public void workspace(
            @PathVariable UUID id,
            @RequestHeader("Authorization") String authorization,
            HttpServletRequest request,
            HttpServletResponse response)
            throws IOException {
        var session = sessions.require(id, authorization);
        if (request.getQueryString() != null) {
            var parameters = request.getParameterMap();
            if (parameters.size() != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            var selection = parameters.entrySet().iterator().next();
            if (selection.getValue().length != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            try (var download = session.download(selection.getKey(), selection.getValue()[0])) {
                response.setContentType("application/x-tar");
                response.setContentLengthLong(download.bytes());
                download.input().transferTo(response.getOutputStream());
            }
            return;
        }
        try (var input = session.download()) {
            response.setContentType("application/x-tar");
            response.setContentLengthLong(session.inputBytes());
            input.transferTo(response.getOutputStream());
        }
    }

    /** Its declared length is bounded on the gateway chain before this is reached. */
    @PostMapping(value = "/result", consumes = "application/x-tar")
    public void result(
            @PathVariable UUID id,
            @RequestHeader("Authorization") String authorization,
            @RequestHeader("Content-Digest") String contentDigest,
            HttpServletRequest request,
            HttpServletResponse response) {
        try {
            var upload = sessions.require(id, authorization).upload(request.getInputStream(), contentDigest);
            response.setHeader("ETag", upload.etag());
            response.setStatus(upload.admitted() ? HttpStatus.NO_CONTENT.value() : HttpStatus.CONFLICT.value());
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid result archive", exception);
        }
    }
}
