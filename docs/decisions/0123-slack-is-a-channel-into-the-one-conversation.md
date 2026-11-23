# ADR 0123: Slack is a channel into the one conversation

**Status:** Proposed
**Date:** 2026-11-23
**Authors:** Felix T.J. Dietrich

**Amends:** [ADR 0082](0082-a-developer-has-one-heph-conversation-and-its-ledger-is-hephs-memory.md) decisions 5 and 6, and it answers that ADR's revisit trigger on team channels.

**Builds on:** [ADR 0120](0120-a-conversation-thread-has-one-inbox-and-runs-one-turn-at-a-time.md) (one route for each turn), [ADR 0121](0121-a-composer-on-a-shared-thread-writes-feedback-on-the-work-and-cannot-read-private-data.md) (the audience rule and shared threads), [ADR 0122](0122-a-copy-on-the-work-that-misses-its-deadline-is-withheld.md) (delivery keys and readback)

## Context

On main, a person can ask Hephaestus in a Slack direct message.
The question and its answer go into the person's one conversation ([ADR 0082](0082-a-developer-has-one-heph-conversation-and-its-ledger-is-hephs-memory.md) decision 5).
A Slack question gets its answer in Slack, and an app question never goes to Slack.
A Slack reply is never posted twice (decision 6).
One Slack team connects to one workspace.
Main has no handler for a mention in a Slack channel.

With [ADR 0120](0120-a-conversation-thread-has-one-inbox-and-runs-one-turn-at-a-time.md), Hephaestus also speaks first: results, check-ins and item cards.
Some people live in Slack and want them there.
A Slack workspace's owner can export direct messages, so Slack is a place that other people can read later.
A Slack channel is read by everyone in it.
ADR 0082 names team channels as a revisit trigger, and says that a channel needs its own ADR.

## Decision drivers

- One conversation, with one record of each message. No place holds a fact that the app does not have.
- The app is complete. Another place may lack a control, never a fact.
- Personal context never enters a place that other people read.
- A Slack post is never duplicated, also after Slack retries or a restore.
- A team can ask about the work in its channel without a private leak.

## Considered options

1. **Slack as a mirror.** Every app message and answer is copied to Slack. App-only text lands in a place that the employer can export, and every copy must stay in sync.
2. **Sync both ways.** Edits and deletes cross in both directions. Two writers of one message need conflict rules, and Slack's rate limits delay the app.
3. **Slack as a second home.** Slack keeps its own state, for example as the phone client. It drifts from the app and cannot show cards, threads, memory or every reverse state.
4. **Slack as a channel into the one conversation (chosen).** Each entry has one origin. Hephaestus-owned state flows to Slack one way, by revision.

## Decision

Option 4.

1. **One conversation for each account and workspace, in PostgreSQL.** Slack reads and writes it as a client. No Slack state exists that the app does not have.
2. **Each entry has one origin and is written once.**
   - A person's message belongs to the place where the person typed it.
   - Nothing typed in the app is copied to Slack.
   - A Slack edit of the person's message changes the entry's text and starts no new answer.
   - A Slack delete of the person's message redacts the entry and stops its turn.
3. **The route is fixed when a turn is claimed.** Each turn has one route ([ADR 0120](0120-a-conversation-thread-has-one-inbox-and-runs-one-turn-at-a-time.md) decision 8).
   - A message pins the route of its surface: the app or the Slack direct message.
   - A requested result follows the route where the person asked.
   - For other background input, the contact policy chooses the app or Slack at the claim.
   - The contact policy is the server rule that decides whether and where Hephaestus may reach a person now. Its own ADR follows.
   - Input that only the app may show waits for an app turn.
4. **A Slack turn reads only what Slack may show.**
   - It never uses, mentions or paraphrases an app-only entry.
   - A server-rendered line can count the omitted app replies: "2 replies in the app since your last message here".
5. **The Slack detail level.** The person chooses **Full** or **Notice**. Every Slack render uses the current level.
   - Full carries the message summary and the card blocks.
   - Notice carries a fixed notice line and an Open button.
   - The default is Notice. Full needs the export notice first. "Keep my context out of Slack" selects Notice.
6. **Cards go to Slack one way, by revision.**
   - An update renders the card's current revision at send time. It goes only to posts with a lower posted revision.
   - Each post has at most one pending update. A card update notifies nobody.
   - Only the 3 newest posts of a card within 14 days update. Older posts stay frozen with their "as of" time.
   - A card action in Slack, such as Mark card done, writes the record in PostgreSQL.
7. **Slack delivery keys** follow [ADR 0122](0122-a-copy-on-the-work-that-misses-its-deadline-is-withheld.md) decision 10.
   - An answer or a message keys on the direct message and the source keys that it covers, at most 10.
   - The Slack message metadata carries that set. Readback and restore read it.
   - A card update keys on the post and its update generation.
8. **Recovery.** A scan of the active direct messages runs at startup and every hour.
   - A recovered message and its live event share one source key.
   - The inbox removes the duplicate before it changes a timer or cancels a turn.
9. **A mention in a Slack channel opens a channel thread.** A channel thread is a shared thread of the workspace. Its key is the Slack team, the channel and the thread `ts`.
   - Each mention is one inbox item. One turn takes the mentions of one person only.
   - The turn reads the Slack thread, public repository data and catalog entries marked channel-readable. Nothing else.
   - It never reads practice-page copies, conversation feedback, private check outcomes, cards or a person's standing.
   - Its one tool posts one complete answer in the mention's thread, through the outbox.
   - Each answer has **Continue privately**. It opens a message thread in the clicker's own conversation, with a link to the channel thread. Nothing moves back to the channel.
10. **Who may ask in a channel.** A linked, active member whose AI choice allows a model. Others get one ephemeral reply. The workspace's model budget pays.
11. **Channel threads have no memory and post nothing first.** A channel thread is erased 30 days after its last turn, or with its channel or connection.
12. **Admin controls, each with its reverse.** A workspace admin turns channel answers on or off, for the workspace and for each channel. Each catalog entry is channel-readable only when an admin marks it. The default is off.
13. **One Slack team for each workspace.** Slack guests and Slack Connect people are never answered. Organization-wide installation is a later decision.

### What this changes in other ADRs

- [ADR 0082](0082-a-developer-has-one-heph-conversation-and-its-ledger-is-hephs-memory.md) decision 5 stays for answers. A background message can now go to the Slack direct message, by decision 3.
- ADR 0082 decision 6 stays. Its key is now the covered set of source keys of decision 7, not one question.
- ADR 0082's revisit trigger "Team channels need Heph" is answered by decisions 9 to 12.

## Consequences

- Good: the app shows everything. A person who leaves Slack loses nothing.
- Good: nothing that the person typed in the app reaches a place that the employer can export.
- Good: a team asks about the work in its channel, and private data stays out of it.
- Good: a Slack post is never duplicated after Slack retries, a recovery scan or a restore.
- Bad: a person who reads only Slack misses app-only entries. The count line says that they exist.
- Bad: at Notice, Slack shows less, and the person must open the app for content.
- Bad: old card posts in Slack stay frozen and can show an old state, with their "as of" time.
- Bad: a channel answer cannot use private repository content. The asker must continue privately.

## Confirmation

Not enforced yet. The implementation adds these tests:

- `AppCanaryNotInSlackTest` fails when text from an app-only entry of a mixed thread reaches a Slack answer.
- `AppCheckInNotInSlackTurnTest` and `AppSummaryNotInSlackTurnTest` fail when app-only context reaches a Slack turn.
- `RestoreBatchedSlackAnswerTest` fails when a restore gives a second answer to a covered message.
- `SlackCardUpdateKeyTest` fails when a card update and a delete of one post share a key.
- `InterleavedMentionsTest` fails when one channel turn holds another person's pending mention.
- `ChannelMessageDeletedBeforeClaimTest` fails when a mention that Slack deleted before its claim gets an answer.
- A channel fixture fails when a catalog entry that is not channel-readable appears in a channel answer.

## Revisit trigger

- People ask for proactive posts in a team channel.
- A workspace needs more than one Slack team, or an organization-wide installation.
- Over four weeks, most Slack users choose Full. Then the default can change.

## Sources

- Slack, [Export your workspace data](https://slack.com/help/articles/201658943-Export-your-workspace-data). On some plans, owners can export "public channels, private channels, and direct messages". This is why Notice is the default and Full needs a notice first.
- Slack, [chat.update](https://docs.slack.dev/reference/methods/chat.update/). "Any message with a bot_id never shows the (edited) label." A card update by revision therefore reads as the current state and notifies nobody.
- OpenClaw, [Channels and routing](https://docs.openclaw.ai/channels/channel-routing). "The model does not choose a channel", and fixed rules route each reply. Decision 3 fixes the route in code at the claim.
