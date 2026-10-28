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
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.slack.SlackHephaestusUiLinks;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackTs;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.mentor.MentorConversation;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationService;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceSummaryQuery;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

    public SlackWeeklyDigestPointer(
            SlackMessageService slack,
            ConnectionService connections,
            SlackMentorIdentityResolver identities,
            SlackMentorPoster poster,
            MentorConversationService conversations,
            WeeklyDigestNotes notes,
            WeeklyDigestSubscriptions subscriptions,
            WorkspaceSummaryQuery workspaces,
            SlackHephaestusUiLinks links) {
        this.slack = slack;
        this.connections = connections;
        this.identities = identities;
        this.poster = poster;
        this.conversations = conversations;
        this.notes = notes;
        this.subscriptions = subscriptions;
        this.workspaces = workspaces;
        this.links = links;
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

    /** Posts the note once. */
    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void on(WeeklyDigestWritten event) {
        if (event.pointer() != WeeklyDigestPointer.SLACK) {
            return;
        }
        send(event.workspaceId(), event.entryId(), false);
    }

    /**
     * Settles a note whose post was taken and not settled, by one read of the DM's history: it is there, or it failed.
     * It is never posted again ({@link SlackMentorPoster#reconcile}).
     */
    public void reconcile(long workspaceId, UUID entryId) {
        send(workspaceId, entryId, true);
    }

    /** Posts the note if the account still wants it: a later opt-in does not revive an older note. */
    private void send(long workspaceId, UUID entryId, boolean reconciling) {
        Optional<WeeklyDigestNotes.Note> found = notes.note(workspaceId, entryId);
        if (found.isEmpty()) {
            return;
        }
        WeeklyDigestNotes.Note note = found.get();
        Optional<String> teamId = teamId(workspaceId);
        Optional<String> slackUserId = teamId.flatMap(team -> identities.resolveSlackUserId(note.accountId(), team));
        Optional<WorkspaceSummaryQuery.WorkspaceSummary> workspace = workspaces.findById(workspaceId);
        if (teamId.isEmpty() || slackUserId.isEmpty() || workspace.isEmpty()) {
            log.debug("Weekly digest note not posted to Slack, no Slack identity: workspaceId={}", workspaceId);
            return;
        }
        Optional<Instant> since = subscriptions.enabledSince(note.accountId());
        if (since.isEmpty() || since.get().isAfter(note.writtenAt())) {
            return;
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
        Optional<SlackMentorPoster.Result> result;
        if (!reconciling) {
            result = poster.post(workspaceId, entryId, post);
        } else if (knownDm) {
            // The note cannot be older than its digest.
            result = Optional.of(poster.reconcile(workspaceId, entryId, post, SlackTs.ofInstant(note.writtenAt())));
        } else {
            // The DM's channel was never learned, so its history cannot be read: the note stays uncertain.
            log.warn(
                    "Weekly digest note stays uncertain, its DM is unknown: workspaceId={}, entryId={}",
                    workspaceId,
                    entryId);
            result = Optional.empty();
        }
        String landedIn = result.filter(posted -> posted.outcome() == SlackMentorPoster.Outcome.DELIVERED)
                .map(SlackMentorPoster.Result::channelId)
                .orElse(null);
        if (landedIn != null && main.getSlackChannelId() == null) {
            // The thread on the note must find this conversation by its Slack DM.
            conversations.bindSlackIdentity(workspaceId, main.getId(), teamId.get(), slackUserId.get(), landedIn);
        }
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
