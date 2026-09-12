package de.tum.cit.aet.hephaestus.integration.slack.onboarding;

import static com.slack.api.model.block.Blocks.actions;
import static com.slack.api.model.block.Blocks.divider;
import static com.slack.api.model.block.Blocks.header;
import static com.slack.api.model.block.Blocks.section;
import static com.slack.api.model.block.composition.BlockCompositions.markdownText;
import static com.slack.api.model.block.composition.BlockCompositions.plainText;
import static com.slack.api.model.block.element.BlockElements.asElements;
import static com.slack.api.model.block.element.BlockElements.button;

import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.composition.ConfirmationDialogObject;
import com.slack.api.model.view.View;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.MentorReadinessQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountPreferencesQuery;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.slack.SlackHephaestusUiLinks;
import de.tum.cit.aet.hephaestus.integration.slack.channel.SlackConsentBlocks;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel.ConsentState;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannelRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackParticipantConsentRepository;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackWorkspaceResolver;
import de.tum.cit.aet.hephaestus.integration.slack.mentor.SlackMentorIdentityResolver;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true", matchIfMissing = false)
public class SlackAppHomeService {

    private static final Logger log = LoggerFactory.getLogger(SlackAppHomeService.class);

    public static final String ACTION_CHANNEL_MESSAGES_OPT_OUT = "channel_messages_opt_out";
    public static final String ACTION_CHANNEL_MESSAGES_OPT_IN = "channel_messages_opt_in";
    public static final String ACTION_RESEARCH_OPT_OUT = "research_opt_out";
    public static final String ACTION_RESEARCH_OPT_IN = "research_opt_in";
    public static final String ACTION_OPEN_HEPHAESTUS = "open_hephaestus_ui";

    private final SlackWorkspaceResolver workspaceResolver;
    private final SlackMentorIdentityResolver identityResolver;
    private final AccountPreferencesQuery preferencesQuery;
    private final SlackParticipantConsentRepository participantConsentRepository;
    private final SlackMonitoredChannelRepository monitoredChannelRepository;
    private final MentorReadinessQuery mentorReadinessQuery;
    private final SlackMessageService messageService;
    private final SlackOnboardingService onboardingService;
    private final SlackHephaestusUiLinks uiLinks;

    public SlackAppHomeService(
            SlackWorkspaceResolver workspaceResolver,
            SlackMentorIdentityResolver identityResolver,
            AccountPreferencesQuery preferencesQuery,
            SlackParticipantConsentRepository participantConsentRepository,
            SlackMonitoredChannelRepository monitoredChannelRepository,
            MentorReadinessQuery mentorReadinessQuery,
            SlackMessageService messageService,
            SlackOnboardingService onboardingService,
            SlackHephaestusUiLinks uiLinks) {
        this.workspaceResolver = workspaceResolver;
        this.identityResolver = identityResolver;
        this.preferencesQuery = preferencesQuery;
        this.participantConsentRepository = participantConsentRepository;
        this.monitoredChannelRepository = monitoredChannelRepository;
        this.mentorReadinessQuery = mentorReadinessQuery;
        this.messageService = messageService;
        this.onboardingService = onboardingService;
        this.uiLinks = uiLinks;
    }

    public void onHomeOpened(String teamId, String slackUserId) {
        if (teamId == null || teamId.isBlank() || slackUserId == null || slackUserId.isBlank()) {
            return;
        }
        Optional<Long> workspaceId = workspaceResolver.resolveWorkspaceId(teamId);
        if (workspaceId.isEmpty()) {
            log.debug("slack.apphome: app_home_opened for team={} with no active connection — skipping", teamId);
            return;
        }
        long ws = workspaceId.get();
        messageService.publishHomeView(ws, slackUserId, buildHomeView(ws, teamId, slackUserId));
    }

    View buildHomeView(long workspaceId, String teamId, String slackUserId) {
        List<LayoutBlock> blocks = new ArrayList<>();
        blocks.add(header(h -> h.text(plainText("Hephaestus practice mentor"))));
        boolean mentorReady = mentorReadinessQuery.isReady(workspaceId);
        Optional<User> developer = identityResolver.resolveDeveloper(workspaceId, teamId, slackUserId);
        Optional<String> login = developer.map(User::getLogin);
        boolean channelMessagesAllowed =
                !participantConsentRepository.existsByWorkspaceIdAndSlackUserIdAndIngestionOptedOutTrue(
                        workspaceId, slackUserId);
        long activeChannels =
                monitoredChannelRepository.countByWorkspaceIdAndConsentState(workspaceId, ConsentState.ACTIVE);
        boolean participating = developer
                .map(User::getId)
                .flatMap(preferencesQuery::preferencesForUserId)
                .map(AccountPreferencesQuery.PreferencesView::participateInResearch)
                .orElse(false);

        blocks.addAll(overviewBlocks(
                new HomeOverviewState(mentorReady, login, channelMessagesAllowed, activeChannels, participating)));
        blocks.addAll(openHephaestusBlocks(uiLinks.userSettingsUrl()));
        blocks.add(divider());
        blocks.addAll(channelMessageBlocks(channelMessagesAllowed));
        blocks.add(divider());

        if (login.isEmpty()) {
            // Message-use control is Slack-id based and already shown. Research needs an account identity.
            blocks.addAll(onboardingService.linkCtaBlocks());
        } else {
            blocks.addAll(researchToggleBlocks(participating));
        }

        return View.builder().type("home").blocks(blocks).build();
    }

    List<LayoutBlock> overviewBlocks(HomeOverviewState state) {
        String mentorState;
        if (!state.mentorReady()) {
            mentorState = "Unavailable";
        } else if (state.login().isEmpty()) {
            mentorState = "Check account access";
        } else {
            mentorState = "Ready to answer";
        }
        String accountState =
                state.login().map(value -> "Linked as `" + value + "`").orElse("No active linked workspace member");
        String activeChannelText =
                state.activeChannels() == 1 ? "1 active channel" : state.activeChannels() + " active channels";
        String researchState = state.login().isEmpty()
                ? "Account access required"
                : state.participating() ? "Included" : "Not included";
        return List.of(
                section(s -> s.text(markdownText(leadText(state.mentorReady(), state.login())))),
                section(s -> s.fields(List.of(
                        markdownText("*Mentor*\n"
                                + stateIcon(state.mentorReady() && state.login().isPresent())
                                + " "
                                + mentorState),
                        markdownText("*Account*\n" + stateIcon(state.login().isPresent()) + " " + accountState),
                        markdownText("*Channel context*\n"
                                + stateIcon(state.channelMessagesAllowed() && state.activeChannels() > 0)
                                + " "
                                + (state.channelMessagesAllowed() ? "Allowed, " + activeChannelText : "Not allowed")),
                        markdownText(
                                "*Research use*\n" + stateIcon(state.login().isPresent() && state.participating())
                                        + " "
                                        + researchState)))),
                section(s -> s.text(markdownText(
                        "*Context and privacy.* Hephaestus can use your linked project work and new messages "
                                + "you send in monitored channels. It does not read channel history from before the "
                                + "channel was activated. It does not mentor in channels."))));
    }

    record HomeOverviewState(
            boolean mentorReady,
            Optional<String> login,
            boolean channelMessagesAllowed,
            long activeChannels,
            boolean participating) {}

    private static List<LayoutBlock> openHephaestusBlocks(String url) {
        if (url == null || url.isBlank()) {
            return List.of();
        }
        return List.of(
                section(s ->
                        s.text(markdownText("*Account settings.* Use this Home tab for Slack message-use controls. "
                                + "Open Hephaestus to manage sign-in, account linking, and web preferences."))),
                actions(a -> a.elements(asElements(button(b -> b.text(plainText("Open account settings"))
                        .url(url)
                        .actionId(ACTION_OPEN_HEPHAESTUS)
                        .style("primary"))))));
    }

    private static String leadText(boolean mentorReady, Optional<String> login) {
        if (!mentorReady) {
            return ("*Mentor unavailable.* The mentor is disabled or not configured for this workspace. "
                    + "You can still manage privacy here.");
        }
        if (login.isEmpty()) {
            return ("*Check your account access to use the mentor.* You need an active Hephaestus account "
                    + "linked to Slack and a project identity in this workspace. You can still manage "
                    + "channel-message privacy here.");
        }
        return ("*AI mentor for software project practices.* Ask in the Messages tab about PRs, reviews, issues, "
                + "tests, or team habits. Replies stay in DM.");
    }

    private static String stateIcon(boolean ok) {
        return ok ? ":white_check_mark:" : ":warning:";
    }

    List<LayoutBlock> channelMessageBlocks(boolean allowed) {
        String status = allowed
                ? "*Channel-message context is allowed.* New messages you send in monitored channels may personalize "
                        + "private mentoring. Turning this off stops future use and deletes channel-message data collected from you."
                : "*Channel-message context is not allowed.* Hephaestus does not use your messages in monitored channels.";
        return List.of(
                section(s -> s.text(markdownText(status))),
                actions(a -> a.elements(asElements(
                        allowed
                                ? button(b -> b.text(plainText("Stop using my messages"))
                                        .actionId(ACTION_CHANNEL_MESSAGES_OPT_OUT)
                                        .value("false")
                                        .style("danger")
                                        .confirm(SlackConsentBlocks.channelMessageOptOutConfirm()))
                                : button(b -> b.text(plainText("Allow future messages"))
                                        .actionId(ACTION_CHANNEL_MESSAGES_OPT_IN)
                                        .value("true")
                                        .style("primary")
                                        .confirm(channelMessageOptInConfirm()))))));
    }

    private static ConfirmationDialogObject channelMessageOptInConfirm() {
        return ConfirmationDialogObject.builder()
                .title(plainText("Allow future channel messages?"))
                .text(plainText(
                        "This allows Hephaestus to use new messages you send in monitored channels. Deleted data is "
                                + "not restored."))
                .confirm(plainText("Allow future messages"))
                .deny(plainText("Cancel"))
                .build();
    }

    List<LayoutBlock> researchToggleBlocks(boolean participating) {
        String status = participating
                ? "*Research use is included.* De-identified practice data may be used to improve Hephaestus research."
                : "*Research use is not included.* Your practice data is not used for research.";
        return List.of(
                section(s -> s.text(markdownText(status))),
                actions(a -> a.elements(asElements(
                        participating
                                ? button(b -> b.text(plainText("Stop research use"))
                                        .actionId(ACTION_RESEARCH_OPT_OUT)
                                        .value("false")
                                        .style("danger"))
                                : button(b -> b.text(plainText("Allow research use"))
                                        .actionId(ACTION_RESEARCH_OPT_IN)
                                        .value("true")
                                        .style("primary"))))));
    }
}
