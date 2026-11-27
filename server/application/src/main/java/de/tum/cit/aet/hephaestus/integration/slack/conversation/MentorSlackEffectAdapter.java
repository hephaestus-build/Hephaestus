package de.tum.cit.aet.hephaestus.integration.slack.conversation;

import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.SectionBlock;
import com.slack.api.model.block.composition.MarkdownTextObject;
import de.tum.cit.aet.hephaestus.core.action.ActionAdapter;
import de.tum.cit.aet.hephaestus.core.action.ActionAttempt;
import de.tum.cit.aet.hephaestus.core.action.ActionEnded;
import de.tum.cit.aet.hephaestus.core.action.ActionKind;
import de.tum.cit.aet.hephaestus.core.action.ActionResult;
import de.tum.cit.aet.hephaestus.core.action.ActionState;
import de.tum.cit.aet.hephaestus.core.action.ActionStopReason;
import de.tum.cit.aet.hephaestus.core.action.ActionVariants;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.slack.SlackHephaestusUiLinks;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackSendException;
import de.tum.cit.aet.hephaestus.mentor.spi.EffectKind;
import de.tum.cit.aet.hephaestus.mentor.spi.EffectOutcome;
import de.tum.cit.aet.hephaestus.mentor.spi.EffectRef;
import de.tum.cit.aet.hephaestus.mentor.spi.MentorEffectProof;
import de.tum.cit.aet.hephaestus.mentor.spi.ObservationTarget;
import de.tum.cit.aet.hephaestus.mentor.spi.Proof;
import de.tum.cit.aet.hephaestus.mentor.spi.ProofRefusal;
import de.tum.cit.aet.hephaestus.mentor.spi.ProviderObservation;
import de.tum.cit.aet.hephaestus.mentor.spi.RenderProjection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Writes the fixed Slack retention notice after the runtime's render and wire proofs. */
@Component
@ConditionalOnServerRole
class MentorSlackEffectAdapter implements ActionAdapter {
    private static final Duration RETRY_DELAY = Duration.ofSeconds(2);
    private static final int PROOF_TIMEOUT_SECONDS = 2;
    private static final int READBACK_PAGES = 3;
    private static final String NOTICE = "Slack may retain and export this DM, including Hephaestus replies.";

    private final ObjectProvider<SlackMessageService> messages;
    private final ObjectProvider<SlackHephaestusUiLinks> links;
    private final ObjectProvider<MentorEffectProof> runtime;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TransactionTemplate proofs;

    MentorSlackEffectAdapter(
            ObjectProvider<SlackMessageService> messages,
            ObjectProvider<SlackHephaestusUiLinks> links,
            ObjectProvider<MentorEffectProof> runtime,
            ObjectMapper mapper,
            Clock clock,
            PlatformTransactionManager transactions) {
        this.messages = messages;
        this.links = links;
        this.runtime = runtime;
        this.mapper = mapper;
        this.clock = clock;
        this.proofs = new TransactionTemplate(transactions);
        this.proofs.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.proofs.setTimeout(PROOF_TIMEOUT_SECONDS);
    }

    record Payload(UUID effectId) {}

    private record Rendered(List<LayoutBlock> blocks, String fallback) {}

    private enum WireDecision {
        ADMITTED,
        CONTENDED
    }

    @Override
    public ActionKind kind() {
        return ActionKind.MENTOR_EFFECT;
    }

    @Override
    public ActionVariants variants() {
        return runtime.getIfAvailable() == null ? ActionVariants.none() : ActionVariants.one(Payload.class);
    }

    @Override
    public ActionResult execute(ActionAttempt attempt) {
        var proof = runtime.getIfAvailable();
        if (proof == null) return new ActionResult.Abandoned();
        var payload = mapper.treeToValue(attempt.payload(), Payload.class);
        var ref = new EffectRef(payload.effectId());
        ObservationTarget target;
        try {
            target = proof.observationTarget(ref).orElse(null);
        } catch (PessimisticLockingFailureException contention) {
            return contention(attempt);
        }
        if (target == null || target.leaseEpoch() != attempt.epoch()) return new ActionResult.Abandoned();
        var route = target.route();
        if (!ref.equals(target.ref()) || !Objects.equals(attempt.workspaceId(), route.workspaceId())) {
            return refused(attempt, ProofRefusal.OBSERVATION_MISMATCH);
        }

        Instant observedAt = clock.instant();
        var slack = messages.getIfAvailable();
        var bound = slack == null ? null : slack.bind(route).orElse(null);
        if (bound == null || !bound.observesAskerInDm()) {
            return refused(attempt, ProofRefusal.READERS_UNPROVEN);
        }
        var observed = new ProviderObservation(
                ref,
                route.fingerprint(),
                route.nativeAskerId(),
                Set.of(route.nativeAskerId()),
                target.readerEpoch(),
                observedAt);
        Proof checked;
        try {
            checked = proofs.execute(status -> proof.check(ref, observed));
        } catch (PessimisticLockingFailureException contention) {
            return contention(attempt);
        }
        if (checked instanceof Proof.Refused refusal) return refused(attempt, refusal.reason());
        if (!(checked instanceof Proof.Allowed allowed)) return new ActionResult.Abandoned();
        var intent = allowed.intent();
        if (!intent.route().equals(route)
                || !intent.ref().equals(ref)
                || !intent.key().equals(attempt.idempotencyKey())) {
            return refused(attempt, ProofRefusal.OBSERVATION_MISMATCH);
        }
        if (intent.marker().isBlank()) return unconfirmed();

        try {
            var found = bound.readPost(intent.marker(), READBACK_PAGES);
            if (found.more()) return unconfirmed();
            if (found.ts().size() == 1) return new ActionResult.Done(found.ts().getFirst());
            if (!found.ts().isEmpty()) return unconfirmed();
        } catch (SlackSendException unavailable) {
            Duration delay = retryDelay(unavailable);
            if ((!unavailable.isRateLimited() && !unavailable.isOutcomeUnknown()) || !canWait(intent.deadline(), delay))
                return unconfirmed();
            return attempt.writeStarted() ? new ActionResult.Reread(delay) : new ActionResult.Retry(delay);
        }
        if (attempt.writeStarted()) {
            return canWait(intent.deadline(), RETRY_DELAY)
                    ? new ActionResult.Retry(RETRY_DELAY)
                    : new ActionResult.Cancelled(ActionStopReason.ABSENT);
        }
        if (!(allowed.render() instanceof RenderProjection.FixedText fixed)
                || fixed.kind() != EffectKind.RETENTION_NOTICE
                || intent.kind() != fixed.kind()) {
            return new ActionResult.Cancelled(ActionStopReason.NOT_CONFIGURED);
        }
        var ui = links.getIfAvailable();
        String settings = ui == null ? "" : ui.userSettingsUrl();
        if (settings.isBlank()) return new ActionResult.Cancelled(ActionStopReason.NOT_CONFIGURED);
        String url = settings + "#slack-dm-binding-" + route.connectionId();
        var rendered = withLink(NOTICE, NOTICE, url, "Use my personal context in Slack DMs");
        return write(
                attempt,
                proof,
                observed,
                allowed,
                admit -> bound.post(rendered.blocks(), rendered.fallback(), intent.marker(), admit)
                        .ts());
    }

    private static Rendered withLink(String body, String fallback, String url, String label) {
        var blocks = new ArrayList<>(SlackConversationPosts.markdown(body));
        blocks.add(SectionBlock.builder()
                .text(MarkdownTextObject.builder()
                        .text("<" + url + "|" + label + ">")
                        .build())
                .build());
        return new Rendered(List.copyOf(blocks), fallback);
    }

    private ActionResult write(
            ActionAttempt attempt,
            MentorEffectProof proof,
            ProviderObservation observation,
            Proof.Allowed allowed,
            Function<BooleanSupplier, String> send) {
        var status = new AtomicReference<@Nullable WireDecision>();
        var refusal = new AtomicReference<@Nullable ProofRefusal>();
        try {
            var receipt = send.apply(() -> {
                try {
                    boolean admitted = attempt.fence().beginWrite(() -> {
                        Proof current = proof.check(allowed.intent().ref(), observation);
                        if (allowed.equals(current)) return true;
                        refusal.set(
                                current instanceof Proof.Refused denied
                                        ? denied.reason()
                                        : ProofRefusal.AUTHORIZATION_CHANGED);
                        return false;
                    });
                    if (admitted) status.set(WireDecision.ADMITTED);
                    return admitted;
                } catch (PessimisticLockingFailureException contention) {
                    status.set(WireDecision.CONTENDED);
                    return false;
                }
            });
            return new ActionResult.Done(receipt);
        } catch (SlackSendException failure) {
            if (status.get() == WireDecision.CONTENDED) return contention(attempt);
            var denied = refusal.get();
            if (denied != null) return refused(attempt, denied);
            if ("not_admitted".equals(failure.slackError())) return new ActionResult.Abandoned();
            if ("silent_mode_engaged".equals(failure.slackError())) {
                return new ActionResult.Cancelled(ActionStopReason.SILENCED);
            }
            if (failure.isRateLimited()) {
                Duration after = retryDelay(failure);
                if (!canWait(allowed.intent().deadline(), after)) {
                    return new ActionResult.Cancelled(ActionStopReason.EXPIRED);
                }
                return status.get() == WireDecision.ADMITTED
                        ? new ActionResult.Retry(after)
                        : new ActionResult.Defer(after);
            }
            if (!failure.isOutcomeUnknown()) return new ActionResult.Failed(ActionStopReason.REJECTED);
            boolean canRetry = canWait(allowed.intent().deadline(), RETRY_DELAY);
            if (status.get() == WireDecision.ADMITTED)
                return canRetry ? new ActionResult.Reread(RETRY_DELAY) : unconfirmed();
            return canRetry ? new ActionResult.Retry(RETRY_DELAY) : new ActionResult.Cancelled(ActionStopReason.EXPIRED);
        }
    }

    private static Duration retryDelay(SlackSendException failure) {
        return failure.isRateLimited()
                ? Duration.ofMillis(failure.retryAfterMillis()).plusMillis(999).truncatedTo(ChronoUnit.SECONDS)
                : RETRY_DELAY;
    }

    private boolean canWait(Instant deadline, Duration delay) {
        return delay.compareTo(Duration.between(clock.instant(), deadline)) < 0;
    }

    private static ActionResult contention(ActionAttempt attempt) {
        return attempt.writeStarted() ? new ActionResult.Reread(RETRY_DELAY) : new ActionResult.Defer(RETRY_DELAY);
    }

    private static ActionResult refused(ActionAttempt attempt, ProofRefusal reason) {
        if (attempt.writeStarted()) return unconfirmed();
        return new ActionResult.Cancelled(
                reason == ProofRefusal.DEADLINE_PASSED ? ActionStopReason.EXPIRED : ActionStopReason.WITHDRAWN, reason);
    }

    private static ActionResult unconfirmed() {
        return new ActionResult.Uncertain(ActionStopReason.WRITE_UNCONFIRMED);
    }

    @Override
    public void ended(ActionEnded ended) {
        var proof = runtime.getIfAvailable();
        if (proof == null) throw new IllegalStateException("Mentor effect settlement is unavailable");
        EffectOutcome outcome;
        if (ended.state() == ActionState.UNCERTAIN) {
            outcome = new EffectOutcome.Unconfirmed();
        } else if (ended.state() == ActionState.DONE) {
            outcome = ended.readbackRef() == null
                    ? new EffectOutcome.Unconfirmed()
                    : new EffectOutcome.Delivered(ended.readbackRef());
        } else if (ended.reason() == ActionStopReason.EXPIRED) {
            outcome = new EffectOutcome.Expired();
        } else {
            outcome = new EffectOutcome.Refused(Objects.requireNonNullElse(
                    ended.decisionCode(), ended.reason().name()));
        }
        proof.settleAction(ended.id(), outcome);
    }
}
