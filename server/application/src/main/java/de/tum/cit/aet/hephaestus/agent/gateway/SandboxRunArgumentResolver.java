package de.tum.cit.aet.hephaestus.agent.gateway;

import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunRef;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Resolves a {@link SandboxRun} from the route's {@code {runId}}, the headers {@code X-Agent-Run} and {@code
 * X-Agent-Epoch}, and the authenticated sandbox. A run of another workspace or another subject, a header that disagrees
 * with the route, and a missing one all answer {@code 404}, as every hidden route of the gateway does.
 */
public final class SandboxRunArgumentResolver implements HandlerMethodArgumentResolver {

    public static final String RUN_HEADER = "X-Agent-Run";
    public static final String EPOCH_HEADER = "X-Agent-Epoch";

    private final AgentRuns runs;

    public SandboxRunArgumentResolver(AgentRuns runs) {
        this.runs = runs;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == SandboxRun.class;
    }

    @Override
    public SandboxRun resolveArgument(
            MethodParameter parameter,
            @Nullable ModelAndViewContainer container,
            NativeWebRequest request,
            @Nullable WebDataBinderFactory binders) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AgentSandboxes.LiveSandbox sandbox))
            throw hidden();
        String runId = pathVariable(request);
        if (runId == null || !runId.equals(request.getHeader(RUN_HEADER))) throw hidden();
        return resolve(runs, sandbox, runId, request.getHeader(EPOCH_HEADER))
                .orElseThrow(SandboxRunArgumentResolver::hidden);
    }

    /**
     * The run that a sandbox's headers name, while it is a run of the sandbox's own subject; empty for a header that is
     * missing or malformed, and for a run of another workspace or subject. The model gateway reads a sandbox's run the
     * same way. Whether the claim still holds the run is the route's to check.
     */
    public static Optional<SandboxRun> resolve(
            AgentRuns runs,
            AgentSandboxes.LiveSandbox sandbox,
            @Nullable String runHeader,
            @Nullable String epochHeader) {
        if (runHeader == null
                || !UUID_TEXT.matcher(runHeader).matches()
                || epochHeader == null
                || !EPOCH_TEXT.matcher(epochHeader).matches()) return Optional.empty();
        UUID runId = UUID.fromString(runHeader);
        int epoch = Integer.parseInt(epochHeader);
        return runs.find(new AgentRunRef(runId, sandbox.workspaceId()))
                .filter(found -> found.getConcurrencyKey().equals(sandbox.subjectKey()))
                .map(run -> new SandboxRun(
                        sandbox,
                        new AgentRunFence(runId, sandbox.workspaceId(), epoch),
                        run.getKind(),
                        run.getAttempt()));
    }

    private static @Nullable String pathVariable(NativeWebRequest request) {
        var servlet = Objects.requireNonNull(request.getNativeRequest(HttpServletRequest.class));
        if (!(servlet.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) instanceof Map<?, ?> variables))
            return null;
        return variables.get("runId") instanceof String value ? value : null;
    }

    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern EPOCH_TEXT = Pattern.compile("[1-9][0-9]{0,8}");

    private static ResponseStatusException hidden() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
