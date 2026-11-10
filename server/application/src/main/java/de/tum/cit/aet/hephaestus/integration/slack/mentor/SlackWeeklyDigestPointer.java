package de.tum.cit.aet.hephaestus.integration.slack.mentor;

import static com.slack.api.model.block.Blocks.actions;
import static com.slack.api.model.block.Blocks.context;
import static com.slack.api.model.block.Blocks.section;
import static com.slack.api.model.block.composition.BlockCompositions.markdownText;
import static com.slack.api.model.block.composition.BlockCompositions.plainText;

import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.composition.ConfirmationDialogObject;
import com.slack.api.model.block.element.BlockElement;
import com.slack.api.model.block.element.ButtonElement;
import de.tum.cit.aet.hephaestus.agent.mentor.weeklydigest.WeeklyDigestNotes;
import de.tum.cit.aet.hephaestus.agent.mentor.weeklydigest.WeeklyDigestPointer;
import de.tum.cit.aet.hephaestus.agent.mentor.weeklydigest.WeeklyDigestSubscriptions;
import de.tum.cit.aet.hephaestus.agent.mentor.weeklydigest.WeeklyDigestTransport;
import de.tum.cit.aet.hephaestus.agent.mentor.weeklydigest.WeeklyDigestWritten;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.action.ActionAdapter;
import de.tum.cit.aet.hephaestus.core.action.ActionAttempt;
import de.tum.cit.aet.hephaestus.core.action.ActionExecutor;
import de.tum.cit.aet.hephaestus.core.action.ActionKind;
import de.tum.cit.aet.hephaestus.core.action.ActionResult;
import de.tum.cit.aet.hephaestus.core.action.ActionStopReason;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.slack.SlackHephaestusUiLinks;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackTs;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.mentor.MentorConversation;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationService;
import de.tum.cit.aet.hephaestus.mentor.MentorSlackReplies;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceSummaryQuery;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

/**
 * The Slack note that a weekly digest is ready: one top-level message in the developer's DM with Hephaestus, posted through
 * the intent, effect and outcome path ({@link SlackMentorPoster}). It names the week and the workspace and nothing
 * else (see the {@code agent.mentor.weeklydigest} package). A reply in its thread is an ordinary Hephaestus thread on the
 * digest entry.
 */
@Component
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
@WorkspaceAgnostic("Each note carries its workspace; the Slack identity is resolved against that workspace")
public class SlackWeeklyDigestPointer implements WeeklyDigestTransport {

    private static final Logger log = LoggerFactory.getLogger(SlackWeeklyDigestPointer.class);

    static final String OPEN_ACTION = "weekly_digest_open";

    /** The {@code action_id} of the button that turns the weekly digest off. */
    public static final String TURN_OFF_ACTION = "weekly_digest_turn_off";

    /** What the developer sees, only themselves, after they press Turn off. */
    public static final String TURNED_OFF = "The weekly digest is off. You can turn it on again in User settings.";

    private final SlackMessageService slack;
    private final ConnectionService connections;
    private final SlackMentorIdentityResolver identities;
    private final SlackMentorPoster poster;
    private final MentorConversationService conversations;
    private final WeeklyDigestNotes notes;
    private final WeeklyDigestSubscriptions subscriptions;
    private final WorkspaceSummaryQuery workspaces;
    private final SlackHephaestusUiLinks links;
    private final MentorSlackReplies replies;
    private final ActionExecutor executor;

    /** Carries out the notes this server writes. */
    private final ActionAdapter ownNotes = new ActionAdapter() {
        @Override
        public ActionKind kind() {
            return ActionKind.SLACK_POST;
        }

        @Override
        public ActionResult execute(ActionAttempt attempt) {
            return SlackWeeklyDigestPointer.this.attempt(attempt, replies.payload(attempt));
        }
    };

    public SlackWeeklyDigestPointer(
            SlackMessageService slack,
            ConnectionService connections,
            SlackMentorIdentityResolver identities,
            SlackMentorPoster poster,
            MentorConversationService conversations,
            WeeklyDigestNotes notes,
            WeeklyDigestSubscriptions subscriptions,
            WorkspaceSummaryQuery workspaces,
            SlackHephaestusUiLinks links,
            MentorSlackReplies replies,
            ActionExecutor executor) {
        this.slack = slack;
        this.connections = connections;
        this.identities = identities;
        this.poster = poster;
        this.conversations = conversations;
        this.notes = notes;
        this.subscriptions = subscriptions;
        this.workspaces = workspaces;
        this.links = links;
        this.replies = replies;
        this.executor = executor;
    }

    @Override
    public WeeklyDigestPointer pointer() {
        return WeeklyDigestPointer.SLACK;
    }

    /** An active Slack connection and the account's Slack identity in its team. */
    @Override
    public boolean reaches(long workspaceId, long accountId) {
        return teamId(workspaceId)
                .flatMap(team -> identities.resolveSlackUserId(accountId, team))
                .isPresent();
    }

    /** Posts the note once, as an outbox action that this server carries out now ({@link MentorSlackReplies}). */
    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void on(WeeklyDigestWritten event) {
        if (event.pointer() != WeeklyDigestPointer.SLACK) {
            return;
        }
        notes.note(event.workspaceId(), event.entryId())
                .flatMap(note -> replies.digest(event.workspaceId(), note.accountId(), event.entryId()))
                .ifPresent(action -> executor.runNow(action, ownNotes));
    }

    /**
     * Carries out one attempt of a note's post. An attempt after one whose post may have reached Slack reads the DM's
     * history instead: the note is there, or it failed, and it is never posted again.
     */
    ActionResult attempt(ActionAttempt attempt, MentorSlackReplies.Payload payload) {
        if (!attempt.fence().beginWrite()) return new ActionResult.Abandoned();
        return send(
                Objects.requireNonNull(attempt.workspaceId()),
                payload.entryId(),
                payload.taken() || attempt.writeStarted());
    }

    /** Posts the note if the account still wants it: a later opt-in does not revive an older note. */
    private ActionResult send(long workspaceId, UUID entryId, boolean reconciling) {
        Optional<WeeklyDigestNotes.Note> found = notes.note(workspaceId, entryId);
        if (found.isEmpty()) {
            return new ActionResult.Cancelled(ActionStopReason.WITHDRAWN);
        }
        WeeklyDigestNotes.Note note = found.get();
        Optional<String> teamId = teamId(workspaceId);
        Optional<String> slackUserId = teamId.flatMap(team -> identities.resolveSlackUserId(note.accountId(), team));
        Optional<WorkspaceSummaryQuery.WorkspaceSummary> workspace = workspaces.findById(workspaceId);
        if (teamId.isEmpty() || slackUserId.isEmpty() || workspace.isEmpty()) {
            log.debug("Weekly digest note not posted to Slack, no Slack identity: workspaceId={}", workspaceId);
            return new ActionResult.Cancelled(ActionStopReason.RECIPIENT_UNAVAILABLE);
        }
        Optional<Instant> since = subscriptions.enabledSince(note.accountId());
        if (since.isEmpty() || since.get().isAfter(note.writtenAt())) {
            return new ActionResult.Cancelled(ActionStopReason.WITHDRAWN);
        }
        MentorConversation main = conversations.require(workspaceId, note.conversationId());
        String knownChannel =
                teamId.get().equals(main.getSlackTeamId()) && slackUserId.get().equals(main.getSlackUserId())
                        ? main.getSlackChannelId()
                        : null;
        boolean knownDm = knownChannel != null;
        // See SlackMessageService.Posted for a post to a user id.
        String channel = knownChannel != null ? knownChannel : slackUserId.get();
        String text = text(note);
        SlackMentorPoster.Post post =
                new SlackMentorPoster.Post(channel, null, blocks(note, workspace.get()), text, entryId);
        SlackMentorPoster.Result result;
        if (!reconciling) {
            result = poster.send(workspaceId, entryId, post);
        } else if (knownDm) {
            // The note cannot be older than its digest.
            result = poster.reconcile(workspaceId, entryId, post, SlackTs.ofInstant(note.writtenAt()));
        } else {
            // The DM's channel was never learned, so its history cannot be read: the note stays uncertain.
            log.warn(
                    "Weekly digest note stays uncertain, its DM is unknown: workspaceId={}, entryId={}",
                    workspaceId,
                    entryId);
            return new ActionResult.Uncertain(ActionStopReason.WRITE_UNCONFIRMED);
        }
        String landedIn = result.outcome() == SlackMentorPoster.Outcome.DELIVERED ? result.channelId() : null;
        if (landedIn != null && main.getSlackChannelId() == null) {
            // The thread on the note must find this conversation by its Slack DM.
            conversations.bindSlackIdentity(workspaceId, main.getId(), teamId.get(), slackUserId.get(), landedIn);
        }
        return SlackMentorPoster.result(result.outcome());
    }

    private Optional<String> teamId(long workspaceId) {
        if (!slack.isConnected(workspaceId)) {
            return Optional.empty();
        }
        return connections
                .findActive(workspaceId, IntegrationKind.SLACK)
                .flatMap(connection -> Optional.ofNullable(connection.getInstanceKey()))
                .filter(key -> !key.isBlank());
    }

    static String text(WeeklyDigestNotes.Note note) {
        return "Your weekly digest for " + note.weekDays() + " is ready in Hephaestus.";
    }

    private List<LayoutBlock> blocks(WeeklyDigestNotes.Note note, WorkspaceSummaryQuery.WorkspaceSummary workspace) {
        List<BlockElement> buttons = new ArrayList<>();
        String url = links.mentorEntryUrl(workspace.slug(), note.entryId());
        if (!url.isBlank()) {
            buttons.add(ButtonElement.builder()
                    .actionId(OPEN_ACTION)
                    .style("primary")
                    .text(plainText("Open in Hephaestus"))
                    .url(url)
                    .build());
        }
        buttons.add(ButtonElement.builder()
                .actionId(TURN_OFF_ACTION)
                .text(plainText("Turn off weekly digest"))
                .confirm(ConfirmationDialogObject.builder()
                        .title(plainText("Turn off weekly digest?"))
                        .text(plainText("You can turn it on again in User settings."))
                        .confirm(plainText("Turn off"))
                        .deny(plainText("Keep"))
                        .build())
                .build());
        return List.of(
                section(s -> s.text(markdownText("*Your weekly digest is ready*\n" + note.weekDays() + " · "
                        + escape(workspace.displayName())))),
                actions(a -> a.elements(buttons)),
                context(c -> c.elements(List.of(markdownText("Reply in this thread to go through it.")))));
    }

    /** Slack reads {@code &}, {@code <} and {@code >} in mrkdwn as markup. */
    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
