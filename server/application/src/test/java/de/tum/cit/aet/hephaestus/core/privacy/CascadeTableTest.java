package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.privacy.spi.CascadeCause;
import de.tum.cit.aet.hephaestus.core.privacy.spi.CascadeDependent;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The cascade table of ADR 0128 decision 3: what each {@link CascadeCause} does to each {@link CascadeDependent}. A cell
 * holds one cause, one dependent kind and one action.
 *
 * <ul>
 *   <li>The action holds for every dependent of the kind under the cause. Where a stronger action holds for some of
 *       them, the reason says so.
 *   <li>A cell that changes the dependent names the tests that prove the action, entered through the cause.
 *   <li>A {@link Action#KEEP} cell states why the dependent stays. A gap names the issue that owns it.
 * </ul>
 *
 * <p>A producer of a dependent kind adds its constant to {@link CascadeDependent} and one cell here for each cause. A new
 * cause adds its constant to {@link CascadeCause} and one cell here for each dependent kind. Each store of the
 * person-data map names the dependent kinds it holds, so a new store must decide.
 */
class CascadeTableTest extends BaseUnitTest {

    enum Action {
        /**
         * The dependent holds none of the source's words when the transaction of the cause commits: its words are
         * redacted, or its row goes.
         */
        ERASE,

        /** The dependent goes before the cause completes. */
        DELETE,

        /** The turn stops in the transaction of the cause and stores nothing more. */
        STOP,

        /** The turn stores nothing of what it produced after the cause. */
        DISCARD,

        /** Every server drops the dependent from its memory when it hears of the committed cause. */
        EVICT,

        /**
         * A pending effect whose lane reads its source after the cause commits sends nothing: the lane withdraws it, and
         * its end deletes it.
         */
        WITHDRAW,

        /** The credentials of the dependent are revoked in the transaction of the cause. */
        REVOKE,

        /** Nothing changes. The cell states why, or names the issue that owns the gap. */
        KEEP
    }

    /**
     * @param proofs the tests that fail when the cause does not take this action, as {@code Class#method} under
     *     {@code de.tum.cit.aet.hephaestus}; optional for {@link Action#KEEP}
     * @param reason why the dependent stays, for {@link Action#KEEP}; otherwise a stronger action for some dependents,
     *     or empty
     */
    record Cell(CascadeCause cause, CascadeDependent dependent, Action action, List<String> proofs, String reason) {
        @Override
        public String toString() {
            return cause + " x " + dependent + " -> " + action;
        }
    }

    private static final String PACKAGE = "de.tum.cit.aet.hephaestus.";

    private static final String ENTRIES = "conversation.ConversationEntryServiceIntegrationTest#";
    private static final String LEDGER = "conversation.ConversationLedgerConcurrencyIntegrationTest#";
    private static final String TURNS = "conversation.ConversationTurnServiceIntegrationTest#";
    private static final String CONTROLLER = "conversation.ConversationControllerIntegrationTest#";
    private static final String RETENTION = "conversation.ConversationRetentionIntegrationTest#";
    private static final String CONTEXT = "conversation.ConversationContextChangesTest#";
    private static final String PERSON_ERASURE = "core.privacy.ConversationTurnPersonErasureIntegrationTest#";
    private static final String SANDBOX_ERASURE = "core.privacy.ConversationSandboxErasureIntegrationTest#";
    private static final String SLACK = "integration.slack.conversation.SlackConversationProjectorIntegrationTest#";
    private static final String SANDBOX_RUNS = "agent.conversation.host.TurnSandboxRunKindIntegrationTest#";
    private static final String DERIVED_RESULTS = "practices.adapter.ProviderDerivedErasureIntegrationTest#";

    private static final String DELETE_PROOF =
            ENTRIES + "shouldEraseEverySummaryAndSearchResultOfAMessageWhenTheDeveloperDeletesIt";
    private static final String DISCONNECT_PROOF =
            RETENTION + "shouldEraseEveryCopyingToolResultWhenTheSlackContentIsErased";
    private static final String SLACK_ENTRIES_PROOF =
            ENTRIES + "shouldEraseEverySlackMessageAndStopSlackTurnsWhenSlackContentIsErased";
    private static final String COPY_ERASURE_PROOF =
            PERSON_ERASURE + "shouldEraseTheSummaryInAnotherPersonsConversationThatCoversACopyOfTheErasedPersonsData";
    private static final String OWN_ERASURE_PROOF =
            PERSON_ERASURE + "shouldDiscardTheRunningTurnOfADeveloperWhoseErasureRuns";
    private static final String WITHDRAW_PROOF =
            SLACK + "shouldWithdrawThePendingReplyAndSendNothingWhenItsQuestionIsRemoved";
    private static final String REVOCATION_PROOF =
            SANDBOX_RUNS + "shouldRevokeTheWorkspacesConversationSandboxInTheAccessChangeTransaction";
    private static final String NARROWING_PROOF =
            CONTEXT + "shouldTellTheWorkersOfTheWorkspaceWhenWhatItsTurnsMayReadChanges";
    private static final String AI_CHOICE_PROOF =
            SANDBOX_RUNS + "shouldRevokeTheSandboxAndRefuseTheRunningAttemptWhenTheDeveloperChangesTheirAiChoice";
    private static final String WIRED_REVOCATION_PROOF =
            SANDBOX_RUNS + "shouldRevokeTheSandboxAndRefuseTheRunningAttemptWhenAnAccessChangeIsAnnounced";

    /** How each source erasure changes the context, which revokes the workspace's conversation sandboxes. */
    private static final String CONTEXT_CHANGING_ERASURES = "A disconnect of an active connection deactivates it"
            + " (ConnectionLifecycleEffects), a channel erasure revokes the channel's consent, and an admin's collection removal,"
            + " an SCM mirror erasure and a repository removal announce the change. Each change revokes the"
            + " workspace's conversation sandboxes.";

    /** Each source erasure's context change, entered through its cause, and the revocation that a change makes. */
    private static final List<String> CONTEXT_CHANGE_PROOFS = List.of(
            "integration.core.connection.ConnectionServiceTest#"
                    + "disconnect_erasesUnderTheFenceAndKeepsTheFootprintForTheProviderTeardown",
            "integration.slack.channel.SlackChannelConsentServiceTest#"
                    + "transitionToRevoked_tellsRunningTurnsTheContextChanged",
            "integration.outline.collection.OutlineCollectionAdminServiceTest#"
                    + "delete_erasesTheCollectionsDocuments_andTheRegistryRow",
            "integration.scm.workspace.ScmWorkspaceContentEraserTest#"
                    + "erase_publishesTheDerivedRowEventBeforeDroppingTheItemsItPointsAt",
            "integration.scm.domain.item.ScmRepositoryItemLifecycleIntegrationTest#"
                    + "shouldRemoveTheItemBeforeDeletingTheLastMonitoredRepository",
            NARROWING_PROOF,
            REVOCATION_PROOF,
            WIRED_REVOCATION_PROOF);

    /** The source erasure that reaches no sandbox. */
    private static final String SUSPENDED_GAP = "Gap, owned by #5158: a disconnect of a suspended Slack or Outline"
            + " connection, an Outline collection deleted upstream, and the withdrawal of a stopped repository's"
            + " retained work publish no context change, so they reach no sandbox. The withdrawal publishes"
            + " RepositoryWithdrawnFromWorkspaceEvent, which ConversationContextChanges does not consume, and with no"
            + " monitor left no monitoring change follows. An SCM disconnect erases its mirror, which announces the"
            + " change.";

    /** A partial answer is Hephaestus's own words, which a revoked turn keeps until it ends. */
    private static final String PARTIAL_REVOKED = "A partial answer holds Hephaestus's own words, as an answer does."
            + " The revocation refuses the attempt's next write, but does not end the turn. Gap, owned by #5141: a"
            + " server serves the last partial answer until the turn ends.";

    /** The gap of every stored dependent under a narrowing: #4907 owns the authorization generations of ADR 0134. */
    private static final String NARROWING_GAP =
            "Gap, owned by #4907: narrowing changes no stored entry, so a turn of the"
                    + " conversation can read the copy again. The authorization generations of ADR 0134 make it stale on read.";

    private static final String PROVIDER = "A completed request to the model provider cannot be retracted. The"
            + " provider's terms bound its copy (personal-data map, configured LLM provider).";

    private static final String BACKUPS = "A backup keeps its copy until the backup expires. A restore reapplies the"
            + " message deletions and person erasures that its recovery manifest names before it serves data (ADR 0128"
            + " decision 9).";

    /** What a ready export holds, and how long. */
    private static final String EXPORT = "A ready export holds the account's unredacted questions, answers and"
            + " digests as they were when it was built, without tool results. It keeps them until its download window"
            + " ends, 48 hours after it was ready, also when the conversation redacts one later.";

    private static final String POSTED = "Hephaestus deletes no message that it posted in Slack. The provider keeps its"
            + " copy, and removal goes through the operator path (personal-data map, delivery destination).";

    static final List<Cell> CELLS = List.of(
            // CONVERSATION_COPY
            new Cell(
                    CascadeCause.REMOVAL,
                    CascadeDependent.CONVERSATION_COPY,
                    Action.ERASE,
                    List.of(
                            DELETE_PROOF,
                            ENTRIES + "shouldEraseEverySummaryAndSearchResultOfAMessageWhenItsRetentionEnds",
                            LEDGER
                                    + "shouldEraseWhatAnEndingTurnStoresWhenTheDeveloperDeletesTheQuotedMessageMeanwhile",
                            ENTRIES + "shouldRefuseToStoreASearchResultWhenTheDeveloperDeletedTheQuotedMessageFirst"),
                    ""),
            new Cell(
                    CascadeCause.SOURCE_ERASURE,
                    CascadeDependent.CONVERSATION_COPY,
                    Action.KEEP,
                    List.of(DISCONNECT_PROOF),
                    "A Slack disconnect erases every tool result that could have copied a message. Gap, owned by #5158:"
                            + " another source erasure leaves them to the copy retention, and a running turn can still"
                            + " store a copy after a Slack disconnect commits."),
            new Cell(
                    CascadeCause.ERASURE,
                    CascadeDependent.CONVERSATION_COPY,
                    Action.KEEP,
                    List.of(
                            COPY_ERASURE_PROOF,
                            PERSON_ERASURE + "shouldEraseTheToolResultsOfTurnsThatRanWhileTheErasureFailedAndResumed",
                            PERSON_ERASURE + "shouldDeleteTheSummaryAndSearchResultOfTheErasedDevelopersConversation",
                            "core.privacy.PersonOutlineCopiesIntegrationTest#outlineErasureStateTable"),
                    "The erasure redacts every copying tool result of the person's workspaces, with the summaries that"
                            + " cover one, and deletes the person's own conversations. Gap, owned by #4979: a result"
                            + " that the Slack copy retention erased earlier leaves its answer, and a later summary over"
                            + " that answer stays."),
            new Cell(CascadeCause.NARROWING, CascadeDependent.CONVERSATION_COPY, Action.KEEP, List.of(), NARROWING_GAP),
            // ANSWERING_TURN
            new Cell(
                    CascadeCause.REMOVAL,
                    CascadeDependent.ANSWERING_TURN,
                    Action.STOP,
                    List.of(
                            CONTROLLER + "shouldEraseTheMessageAndStopItsTurnWhenTheDeveloperDeletesTheirQuestion",
                            TURNS
                                    + "shouldStoreNothingOfTheTurnWhenTheDeveloperDeletedTheQuestionWhileHephaestusAnswered"),
                    "A running reply that could read the removed message stops even when its own question is recent."),
            new Cell(
                    CascadeCause.SOURCE_ERASURE,
                    CascadeDependent.ANSWERING_TURN,
                    Action.KEEP,
                    Stream.concat(CONTEXT_CHANGE_PROOFS.stream(), Stream.of(SLACK_ENTRIES_PROOF))
                            .toList(),
                    CONTEXT_CHANGING_ERASURES + " The revocation refuses the attempt's next write, and a Slack"
                            + " disconnect also stops every Slack turn. " + SUSPENDED_GAP + " A write in flight still"
                            + " commits what it read."),
            new Cell(
                    CascadeCause.ERASURE,
                    CascadeDependent.ANSWERING_TURN,
                    Action.DISCARD,
                    List.of(
                            OWN_ERASURE_PROOF,
                            PERSON_ERASURE
                                    + "shouldStoreNoConversationOrQuestionOfADeveloperWhoseErasureCameAfterTheAdmission"),
                    ""),
            new Cell(
                    CascadeCause.NARROWING,
                    CascadeDependent.ANSWERING_TURN,
                    Action.DISCARD,
                    List.of(NARROWING_PROOF, AI_CHOICE_PROOF, REVOCATION_PROOF, WIRED_REVOCATION_PROOF),
                    "The sandbox revocation is the fence: the attempt's next write is refused."),
            // PARTIAL_ANSWER
            new Cell(
                    CascadeCause.REMOVAL,
                    CascadeDependent.PARTIAL_ANSWER,
                    Action.EVICT,
                    List.of(
                            "agent.conversation.host.TurnSandboxRunKindIntegrationTest#shouldFenceBothReplicasWhenConversationHistoryIsRemoved"),
                    "An epoch check also rejects old partials while a replica has yet to hear the removal."),
            new Cell(
                    CascadeCause.SOURCE_ERASURE,
                    CascadeDependent.PARTIAL_ANSWER,
                    Action.KEEP,
                    List.of(),
                    PARTIAL_REVOKED),
            new Cell(
                    CascadeCause.ERASURE,
                    CascadeDependent.PARTIAL_ANSWER,
                    Action.KEEP,
                    List.of(),
                    "A partial frame is refused once the erasure holds the copy fence. Gap, owned by #5141: a frame"
                            + " written between the erasure's admission and the fence, and the last frame, are served"
                            + " until the turn ends."),
            new Cell(CascadeCause.NARROWING, CascadeDependent.PARTIAL_ANSWER, Action.KEEP, List.of(), PARTIAL_REVOKED),
            // DERIVED_ANSWER
            new Cell(
                    CascadeCause.REMOVAL,
                    CascadeDependent.DERIVED_ANSWER,
                    Action.KEEP,
                    List.of(
                            DELETE_PROOF,
                            RETENTION
                                    + "shouldEraseACopyingToolResultWithTheSlackRetentionAndKeepTheMessagesAndOtherResults"),
                    "An answer is Hephaestus's own message: it stays when the developer deletes the question, also where"
                            + " it repeats it. A tool result that its retention erases leaves the answers that used it to"
                            + " the conversation retention (personal-data map, conversations)."),
            new Cell(
                    CascadeCause.SOURCE_ERASURE,
                    CascadeDependent.DERIVED_ANSWER,
                    Action.KEEP,
                    List.of(),
                    "An app answer that repeats an erased source keeps the conversation retention (personal-data map,"
                            + " conversations). A Slack disconnect also erases every entry written in Slack."),
            new Cell(
                    CascadeCause.ERASURE,
                    CascadeDependent.DERIVED_ANSWER,
                    Action.KEEP,
                    List.of(
                            PERSON_ERASURE + "shouldEraseTheToolResultsOfTurnsThatRanWhileTheErasureFailedAndResumed",
                            PERSON_ERASURE + "shouldEraseAnAnswerThatUsesEarlierContextWithoutAnotherToolCall",
                            "core.privacy.PersonOutlineCopiesIntegrationTest#outlineErasureStateTable"),
                    "The answers of turns that read the person's data are erased. Gap, owned by #4979: a later answer"
                            + " that repeats an erased feedback copy without reading it again stays, until its answer"
                            + " basis makes it stale."),
            new Cell(CascadeCause.NARROWING, CascadeDependent.DERIVED_ANSWER, Action.KEEP, List.of(), NARROWING_GAP),
            // SLACK_POST
            new Cell(
                    CascadeCause.REMOVAL,
                    CascadeDependent.SLACK_POST,
                    Action.WITHDRAW,
                    List.of(
                            WITHDRAW_PROOF,
                            SLACK + "shouldFailUnsentFeedbackEvenWhenItsQuestionOrSlackDestinationIsGone",
                            SLACK + "shouldRemoveAnUnknownOutcomeWhenRedactionCommitsDuringItsSettlement"),
                    "A post that ended with an unknown outcome goes in a transaction of its own, after the cause"
                            + " commits. Gap, owned by #5147: a lane that read the question before the cause commits can"
                            + " still write."),
            new Cell(
                    CascadeCause.SOURCE_ERASURE,
                    CascadeDependent.SLACK_POST,
                    Action.KEEP,
                    List.of(WITHDRAW_PROOF),
                    "A disconnect withdraws the pending replies of the Slack entries that it erases. Another source"
                            + " erasure removes no entry that a post replies to."),
            new Cell(
                    CascadeCause.ERASURE,
                    CascadeDependent.SLACK_POST,
                    Action.KEEP,
                    List.of(SLACK + "shouldEraseThePendingPayloadAndSendNothingAfterErasure"),
                    "The erased person's own pending posts are deleted. Gap, owned by #5146: a pending reply in another"
                            + " person's Slack conversation, whose answer the erasure redacts, posts a false failure."),
            new Cell(
                    CascadeCause.NARROWING,
                    CascadeDependent.SLACK_POST,
                    Action.KEEP,
                    List.of(),
                    "No narrowing path changes a pending post: a narrowing revokes sandboxes only."),
            // POSTED_SLACK_MESSAGE
            new Cell(CascadeCause.REMOVAL, CascadeDependent.POSTED_SLACK_MESSAGE, Action.KEEP, List.of(), POSTED),
            new Cell(
                    CascadeCause.SOURCE_ERASURE, CascadeDependent.POSTED_SLACK_MESSAGE, Action.KEEP, List.of(), POSTED),
            new Cell(CascadeCause.ERASURE, CascadeDependent.POSTED_SLACK_MESSAGE, Action.KEEP, List.of(), POSTED),
            new Cell(CascadeCause.NARROWING, CascadeDependent.POSTED_SLACK_MESSAGE, Action.KEEP, List.of(), POSTED),
            // SANDBOX
            new Cell(
                    CascadeCause.REMOVAL,
                    CascadeDependent.SANDBOX,
                    Action.REVOKE,
                    List.of(
                            "agent.conversation.host.TurnSandboxRunKindIntegrationTest#shouldFenceBothReplicasWhenConversationHistoryIsRemoved"),
                    ""),
            new Cell(
                    CascadeCause.SOURCE_ERASURE,
                    CascadeDependent.SANDBOX,
                    Action.KEEP,
                    CONTEXT_CHANGE_PROOFS,
                    CONTEXT_CHANGING_ERASURES + " " + SUSPENDED_GAP),
            new Cell(
                    CascadeCause.ERASURE,
                    CascadeDependent.SANDBOX,
                    Action.DELETE,
                    List.of(SANDBOX_ERASURE
                            + "shouldAcknowledgeOnlyAfterPhysicalCopiesAreGoneAndKeepOtherWorkspacesAndReviews"),
                    ""),
            new Cell(
                    CascadeCause.NARROWING,
                    CascadeDependent.SANDBOX,
                    Action.REVOKE,
                    List.of(NARROWING_PROOF, AI_CHOICE_PROOF, REVOCATION_PROOF, WIRED_REVOCATION_PROOF),
                    ""),
            // MODEL_PROVIDER_COPY
            new Cell(CascadeCause.REMOVAL, CascadeDependent.MODEL_PROVIDER_COPY, Action.KEEP, List.of(), PROVIDER),
            new Cell(
                    CascadeCause.SOURCE_ERASURE,
                    CascadeDependent.MODEL_PROVIDER_COPY,
                    Action.KEEP,
                    List.of(),
                    PROVIDER),
            new Cell(CascadeCause.ERASURE, CascadeDependent.MODEL_PROVIDER_COPY, Action.KEEP, List.of(), PROVIDER),
            new Cell(CascadeCause.NARROWING, CascadeDependent.MODEL_PROVIDER_COPY, Action.KEEP, List.of(), PROVIDER),
            // BACKUP
            new Cell(CascadeCause.REMOVAL, CascadeDependent.BACKUP, Action.KEEP, List.of(), BACKUPS),
            new Cell(
                    CascadeCause.SOURCE_ERASURE,
                    CascadeDependent.BACKUP,
                    Action.KEEP,
                    List.of(),
                    "A backup keeps its copy until the backup expires. A restore reapplies the journaled disconnects,"
                            + " collection removals and Slack channel erasures before it serves data. A disconnect"
                            + " erases the SCM mirror again. Gap, owned by #4968: it reapplies no repository removal."),
            new Cell(CascadeCause.ERASURE, CascadeDependent.BACKUP, Action.KEEP, List.of(), BACKUPS),
            new Cell(CascadeCause.NARROWING, CascadeDependent.BACKUP, Action.KEEP, List.of(), BACKUPS),
            // EXPORT
            new Cell(
                    CascadeCause.REMOVAL,
                    CascadeDependent.EXPORT,
                    Action.KEEP,
                    List.of(),
                    EXPORT + " The account built it for itself before it removed the message."),
            new Cell(
                    CascadeCause.SOURCE_ERASURE,
                    CascadeDependent.EXPORT,
                    Action.KEEP,
                    List.of(),
                    EXPORT + " A Slack disconnect redacts the entries written in Slack in the conversation, not in a"
                            + " ready export. An app reply that repeats an erased source stays in both."),
            new Cell(
                    CascadeCause.ERASURE,
                    CascadeDependent.EXPORT,
                    Action.KEEP,
                    List.of(),
                    "The erased person's own exports go with the account. " + EXPORT + " Gap, owned by #5090: another"
                            + " account's ready export keeps a reply that the erasure redacts in its conversation."),
            new Cell(
                    CascadeCause.NARROWING,
                    CascadeDependent.EXPORT,
                    Action.KEEP,
                    List.of(),
                    EXPORT + " A narrowing changes no message."),
            // PRACTICE_RESULT
            new Cell(
                    CascadeCause.REMOVAL,
                    CascadeDependent.PRACTICE_RESULT,
                    Action.KEEP,
                    List.of(),
                    "A practice result is about a provider item's work, such as a Slack thread, not about a"
                            + " conversation entry. Removing an entry changes no result."),
            new Cell(
                    CascadeCause.SOURCE_ERASURE,
                    CascadeDependent.PRACTICE_RESULT,
                    Action.KEEP,
                    List.of(
                            "integration.slack.events.SlackIngestServiceTest#"
                                    + "eraseChannel_revokesConsentErasesDerivedFeedbackAndDeletesStoredContentPromptly",
                            DERIVED_RESULTS + "shouldEraseOnlyTheRowsOfTheErasedThreadsWhenASlackChannelIsErased",
                            DERIVED_RESULTS + "shouldEraseOnlyTheThreadRowsWhenSlackIsDisconnected",
                            DERIVED_RESULTS + "shouldEraseOnlyTheDocumentRowsWhenOutlineDisconnects",
                            DERIVED_RESULTS + "shouldEraseOnlyThePullRequestRowsWhenTheScmMirrorIsErased"),
                    "A Slack channel erasure, a Slack disconnect, an Outline disconnect and an SCM mirror erasure"
                            + " delete the results about their work, with the feedback based on them and the"
                            + " scan-report members that name them (DerivedResultEraser). An Outline collection removal"
                            + " and a repository removal keep the results about the removed work, which follow the"
                            + " feedback retention (personal-data map, practice proof)."),
            new Cell(
                    CascadeCause.ERASURE,
                    CascadeDependent.PRACTICE_RESULT,
                    Action.ERASE,
                    List.of(
                            "core.privacy.AccountErasureIntegrationTest"
                                    + "#shouldEraseNotMetResultsAndTheirProblemsWithoutTouchingAnotherPersonsResult",
                            "practices.scan.ScanReportIntegrationTest"
                                    + "#erasureRemovesProofReferencesAndKeepsLabelledCloseCounts"),
                    "The person's own results go, with their problems, their feedback and the scan-report"
                            + " references to them. Another person's result loses the proof that names the person."),
            new Cell(
                    CascadeCause.NARROWING,
                    CascadeDependent.PRACTICE_RESULT,
                    Action.KEEP,
                    List.of(),
                    "A narrowing keeps the source, so the results about its work stay: a stopped repository keeps"
                            + " its indexed work, and a paused channel consent or an AI-choice change removes no"
                            + " result."));

    /**
     * The rows of the person-data map's main table that hold copies of their own, such as a copy outside PostgreSQL,
     * keyed by the row's name, with the dependent kinds each holds.
     */
    static final Map<String, Set<CascadeDependent>> OUTSIDE_ROWS = Map.ofEntries(
            Map.entry("PostgreSQL product feedback cleanup", EnumSet.noneOf(CascadeDependent.class)),
            Map.entry("PostgreSQL retained occasion evidence", EnumSet.noneOf(CascadeDependent.class)),
            Map.entry("Temporary review capture and context copies", EnumSet.noneOf(CascadeDependent.class)),
            Map.entry(
                    "Server-local repository mirrors, checkout caches and attempt folders",
                    EnumSet.noneOf(CascadeDependent.class)),
            Map.entry(
                    "Research datasets outside the instance database, held by the research team at `[location]`",
                    EnumSet.noneOf(CascadeDependent.class)),
            Map.entry("Configured LLM provider", EnumSet.of(CascadeDependent.MODEL_PROVIDER_COPY)),
            Map.entry("Product feedback model provider", EnumSet.noneOf(CascadeDependent.class)),
            Map.entry(
                    "GitHub product feedback repositories, public and private", EnumSet.noneOf(CascadeDependent.class)),
            Map.entry("GitHub/GitLab/Slack delivery destination", EnumSet.of(CascadeDependent.POSTED_SLACK_MESSAGE)),
            Map.entry("Application metrics", EnumSet.noneOf(CascadeDependent.class)),
            Map.entry("Optional trace export and log collection", EnumSet.noneOf(CascadeDependent.class)),
            Map.entry("External avatar hosts and browser caches", EnumSet.noneOf(CascadeDependent.class)),
            Map.entry("Container logs and support bundles", EnumSet.noneOf(CascadeDependent.class)),
            Map.entry("PostgreSQL and filesystem backups", EnumSet.of(CascadeDependent.BACKUP)),
            Map.entry("S3 privacy journal objects", EnumSet.noneOf(CascadeDependent.class)),
            Map.entry("Conversation sandboxes", EnumSet.of(CascadeDependent.SANDBOX)));

    /**
     * The rows of the person-data map's main table whose copies are the stores that they name, keyed by the row's name.
     * Every row of the main table is here or in {@link #OUTSIDE_ROWS}, so a new row decides, also when it names a known
     * store.
     */
    static final Set<String> STORE_ROWS = Set.of(
            "PostgreSQL GitHub App installations",
            "PostgreSQL MCP client grants",
            "PostgreSQL Outline mirror",
            "PostgreSQL SCM mirror",
            "PostgreSQL Slack mirror",
            "PostgreSQL `llm_usage_event`",
            "PostgreSQL account passkeys",
            "PostgreSQL account, identity and authentication rows",
            "PostgreSQL commit link corrections",
            "PostgreSQL contributor profiles",
            "PostgreSQL conversations",
            "PostgreSQL durable work",
            "PostgreSQL installed-client sessions",
            "PostgreSQL instance-administration records",
            "PostgreSQL notification subscriptions",
            "PostgreSQL operation history",
            "PostgreSQL outbox",
            "PostgreSQL practice proof",
            "PostgreSQL practice versions",
            "PostgreSQL product feedback",
            "PostgreSQL product feedback choices",
            "PostgreSQL product feedback destinations",
            "PostgreSQL product feedback filing",
            "PostgreSQL product reviews",
            "PostgreSQL provider event inbox",
            "PostgreSQL recovery facts",
            "PostgreSQL runtime",
            "PostgreSQL sign-in rate limits",
            "PostgreSQL transient composition");

    /**
     * The dependent kinds that each store of the person-data map holds. A store that holds none is in
     * {@link #NO_DEPENDENT}.
     */
    static final Map<String, Set<CascadeDependent>> DEPENDENT_STORES = Map.of(
            "conversation_entry",
            EnumSet.of(CascadeDependent.CONVERSATION_COPY, CascadeDependent.DERIVED_ANSWER),
            "conversation_entry_tool_result_copy",
            EnumSet.of(CascadeDependent.CONVERSATION_COPY),
            "conversation_entry_feedback_copy",
            EnumSet.of(CascadeDependent.DERIVED_ANSWER),
            "conversation_turn",
            EnumSet.of(CascadeDependent.ANSWERING_TURN),
            "agent_action",
            EnumSet.of(CascadeDependent.SLACK_POST),
            "account_export",
            EnumSet.of(CascadeDependent.EXPORT),
            "result",
            EnumSet.of(CascadeDependent.PRACTICE_RESULT),
            "feedback",
            EnumSet.of(CascadeDependent.PRACTICE_RESULT),
            "review_item",
            EnumSet.of(CascadeDependent.PRACTICE_RESULT));

    /**
     * The stores of the person-data map that hold no words of a conversation entry. A provider's own record of a Slack
     * message ({@code slack_message}, {@code provider_event}) is a source, not a copy of an entry: the Slack erasures
     * and the retentions delete it.
     */
    static final Set<String> NO_DEPENDENT = Set.of(
            "account",
            "account_feature",
            "account_public_activity_hidden_by_default",
            "activity_automation",
            "activity_event",
            "activity_publication",
            "auth_event",
            "auth_rate_limit_bucket",
            "client_session",
            "client_sign_in_handoff",
            "commit_contributor",
            "commit_file_change",
            "commit_identity_change",
            "composer_transcript_part",
            "config_audit_event",
            "config_audit_event_acting_account",
            "config_audit_event_membership_subject",
            "connection_audit",
            "consent_decision",
            "conversation",
            "event_publication",
            "feedback_basis",
            "feedback_copy",
            "feedback_decision",
            "feedback_decision_actor",
            "feedback_response",
            "feedback_thread_resolution",
            "feedback_thread_resolution_actor",
            "git_commit",
            "git_commit_authored_content",
            "git_commit_committer",
            "git_repository_history",
            "github_installation_request",
            "github_installation_request_adopted",
            "github_installation_request_decider",
            "identity",
            "identity_restriction",
            "installation_account",
            "instance_llm_settings",
            "instance_settings",
            "issue",
            "issue_assignee",
            "issue_comment",
            "issue_merger",
            "issued_jwt",
            "item_event",
            "item_event_evidence",
            "item_event_requester",
            "llm_usage_event",
            "mcp_client_grant",
            "membership",
            "milestone",
            "notification_subscription",
            "oauth_state_nonce",
            "organization",
            "organization_membership",
            "outline_collection",
            "outline_document",
            "outline_document_collaborator",
            "outline_document_content",
            "outline_document_editor",
            "passkey_challenge",
            "passkey_credential",
            "passkey_recovery_code",
            "person_data_request",
            "person_data_request_administration",
            "person_data_store_administration",
            "person_evidence_copy",
            "practice_version",
            "privacy_journal",
            "problem",
            "problem_lineage",
            "product_feedback",
            "product_feedback_consent",
            "product_feedback_destination",
            "product_feedback_filing",
            "product_feedback_resolver",
            "provider_event",
            "provider_event_replay",
            "pull_request_requested_reviewers",
            "pull_request_review",
            "pull_request_review_comment",
            "pull_request_review_thread",
            "repository",
            "repository_collaborator",
            "research_disclosure",
            "research_disclosure_subject",
            "result_mark",
            "result_mark_created_actor",
            "result_mark_removed_actor",
            "review",
            "review_attempt_input",
            "review_batch",
            "review_composition_basis",
            "review_item_copied_identity",
            "review_job",
            "review_schedule",
            "slack_channel_consent_event",
            "slack_message",
            "slack_monitored_channel",
            "slack_participant_consent",
            "slack_thread",
            "sync_job",
            "team",
            "team_membership",
            "team_repository_permission",
            "trial",
            "user",
            "workspace",
            "workspace_onboarding_settings");

    private static final Path MAP = Path.of("../../docs/admin/dsms/personal-data-map.mdx");
    private static final String MAIN_TABLE = "| Store / copy |";
    private static final String INVENTORY = "| Store | Person export implementation | Person erasure implementation |";
    private static final Pattern CODE_SPAN = Pattern.compile("`([^`]+)`");

    static Stream<CascadeDependent> dependents() {
        return Arrays.stream(CascadeDependent.values());
    }

    static Stream<Cell> cells() {
        return CELLS.stream();
    }

    static Stream<String> proofs() {
        return CELLS.stream().flatMap(cell -> cell.proofs().stream()).distinct();
    }

    @ParameterizedTest
    @MethodSource("dependents")
    void shouldHaveOneCellForEachCauseWhenADependentKindIsRegistered(CascadeDependent dependent) {
        assertThat(CELLS.stream().filter(cell -> cell.dependent() == dependent).map(Cell::cause))
                .as("the causes with a cell for %s", dependent)
                .containsExactlyInAnyOrder(CascadeCause.values());
    }

    @ParameterizedTest
    @MethodSource("cells")
    void shouldNameItsProofOrItsReasonWhenACellIsRegistered(Cell cell) {
        if (cell.action() == Action.KEEP) {
            assertThat(cell.reason()).as("why %s keeps the dependent", cell).isNotBlank();
        } else {
            assertThat(cell.proofs()).as("the tests that prove %s", cell).isNotEmpty();
        }
    }

    @ParameterizedTest
    @MethodSource("proofs")
    void shouldFindAnEnabledTestWhenACellNamesAProof(String proof) throws ClassNotFoundException {
        String[] parts = proof.split("#", -1);
        assertThat(parts).as("Class#method: %s", proof).hasSize(2);
        Class<?> test = Class.forName(PACKAGE + parts[0]);
        for (Class<?> enclosing = test; enclosing != null; enclosing = enclosing.getEnclosingClass()) {
            assertThat(enclosing.isAnnotationPresent(Disabled.class))
                    .as("%s is disabled", enclosing)
                    .isFalse();
        }
        assertThat(Arrays.stream(test.getDeclaredMethods())
                        .filter(method -> method.getName().equals(parts[1]))
                        .filter(CascadeTableTest::isEnabledTest))
                .as("an enabled test %s", proof)
                .hasSize(1);
    }

    /** The stores come from the person-data map, which {@code PersonalDataMapArchTest} holds to the runtime registry. */
    @Test
    void shouldDecideForEveryStoreOfThePersonDataMapWhichDependentKindsItHolds() throws IOException {
        Set<String> stores = mapStores();
        assertThat(DEPENDENT_STORES.keySet())
                .as("a store is a dependent or holds none, not both")
                .doesNotContainAnyElementsOf(NO_DEPENDENT);
        assertThat(new TreeSet<>(Stream.concat(DEPENDENT_STORES.keySet().stream(), NO_DEPENDENT.stream())
                        .collect(Collectors.toSet())))
                .as("every store of the person-data map, and no other, says which dependent kinds it holds")
                .containsExactlyElementsOf(stores);
        Set<CascadeDependent> stored = DEPENDENT_STORES.values().stream()
                .flatMap(Set::stream)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(CascadeDependent.class)));
        assertThat(stored)
                .as("the kinds that live in a store are the kinds that a store holds")
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(CascadeDependent.values())
                        .filter(kind -> kind.home() == CascadeDependent.Home.STORE)
                        .toList());
    }

    /**
     * Each row of the map's main table decides by its name, whether or not it names a store. A provider's copy, or a
     * remote copy of a store, cannot pass under a store that another row already decided for.
     */
    @Test
    void shouldDecideForEveryRowOfThePersonDataMapWhichDependentKindsItHolds() throws IOException {
        Set<String> stores = mapStores();
        String map = Files.readString(MAP);
        String table = map.substring(map.indexOf(MAIN_TABLE));
        Map<String, String> rows = table.lines()
                .skip(2)
                .takeWhile(line -> line.startsWith("|"))
                .map(line -> line.split("\\|", -1)[1].strip())
                .collect(Collectors.toMap(
                        name -> name.contains(" — ") ? name.substring(0, name.indexOf(" — ")) : name,
                        name -> name,
                        (first, second) -> {
                            throw new AssertionError("two rows of the main table share the name " + first);
                        },
                        TreeMap::new));
        assertThat(STORE_ROWS)
                .as("a row is a store row or holds its own copies, not both")
                .doesNotContainAnyElementsOf(OUTSIDE_ROWS.keySet());
        assertThat(new TreeSet<>(Stream.concat(STORE_ROWS.stream(), OUTSIDE_ROWS.keySet().stream())
                        .collect(Collectors.toSet())))
                .as("every row of the main table, and no other, says which dependent kinds it holds")
                .containsExactlyElementsOf(rows.keySet());
        STORE_ROWS.forEach(row -> assertThat(CODE_SPAN.matcher(rows.get(row)).results())
                .as("the store row %s names a store of the inventory", row)
                .anySatisfy(span -> assertThat(stores).contains(span.group(1))));
        Set<CascadeDependent> held = OUTSIDE_ROWS.values().stream()
                .flatMap(Set::stream)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(CascadeDependent.class)));
        assertThat(held)
                .as("the kinds that live outside PostgreSQL are the kinds that a row outside the inventory holds")
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(CascadeDependent.values())
                        .filter(kind -> kind.home() == CascadeDependent.Home.OUTSIDE)
                        .toList());
    }

    private static Set<String> mapStores() throws IOException {
        String map = Files.readString(MAP);
        int start = map.indexOf(INVENTORY);
        assertThat(start).as("the store inventory of the person-data map").isNotNegative();
        return map.substring(start + INVENTORY.length())
                .stripLeading()
                .lines()
                .takeWhile(line -> line.startsWith("|"))
                .filter(line -> line.startsWith("| `"))
                .flatMap(line -> CODE_SPAN.matcher(line.split("\\|", -1)[1]).results())
                .map(match -> match.group(1))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static boolean isEnabledTest(Method method) {
        return (method.isAnnotationPresent(Test.class) || method.isAnnotationPresent(ParameterizedTest.class))
                && !method.isAnnotationPresent(Disabled.class);
    }
}
