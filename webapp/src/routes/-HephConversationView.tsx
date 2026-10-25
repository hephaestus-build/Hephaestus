import type { ReactNode } from "react";

import { Greeting } from "@/components/mentor/Greeting";
import { HephComposer } from "@/components/mentor/HephComposer";
import { HephMessage, type MessageAuthor } from "@/components/mentor/HephMessage";
import {
	HephTimeline,
	type HephTimelineState,
	type TimelineThreads,
} from "@/components/mentor/HephTimeline";
import { HephTimelineSkeleton } from "@/components/mentor/HephTimelineSkeleton";
import { ThreadGone } from "@/components/mentor/ThreadPanel";
import { liveTurnOf } from "@/components/mentor/timeline-rows";
import { WeeklyDigestCard } from "@/components/mentor/WeeklyDigestCard";
import { Empty, EmptyDescription, EmptyHeader, EmptyTitle } from "@/components/ui/empty";
import { Marker, MarkerContent } from "@/components/ui/marker";
import {
	type HephActions,
	useHephActions,
	useHephThread,
	useHephTimeline,
	useMarkHephRead,
} from "@/hooks/use-heph-conversation";
import { copyToClipboard } from "@/lib/clipboard";
import { firstNonBlank } from "@/lib/text";
import { useAuth } from "@/runtime/auth/AuthContext";

/**
 * The containers of the developer's Heph conversation, shared by the mentor page and the Heph panel:
 * they read the conversation through the hooks and hand the components plain props.
 */

const copy = (text: string) => copyToClipboard(text, "Message copied");

/** Signs the developer's messages: the viewed member's in a user view, else the signed-in account's. */
function useDeveloper(): MessageAuthor {
	const { userView, userProfile, username, getUserProfilePictureUrl } = useAuth();
	if (userView) {
		return { name: userView.name };
	}
	return {
		name: firstNonBlank(userProfile?.name, username) ?? "You",
		avatarUrl: getUserProfilePictureUrl(),
	};
}

/**
 * The writes the conversation offers: none in a user view, and no asking again — Reply in thread,
 * Try again, Retry — while the developer cannot ask Heph.
 */
function offeredActions(actions: HephActions, { readOnly, canAsk }: ConversationAccess) {
	if (readOnly) {
		return;
	}
	return canAsk ? actions : { ...actions, ask: undefined };
}

/** A user view of a conversation with no message: whose conversation it is, and what appears in it. */
function NoMessagesYet({ name }: { name: string }) {
	return (
		<Empty>
			<EmptyHeader>
				<EmptyTitle>No messages yet</EmptyTitle>
				<EmptyDescription>
					When {name} asks Heph something, here or in Slack, the question and Heph’s answer appear
					here.
				</EmptyDescription>
			</EmptyHeader>
		</Empty>
	);
}

/**
 * What stands in for a conversation with no message. Where the developer cannot ask, the notice
 * above the conversation already says why and what to do, so a second empty state under it would
 * only repeat that nothing is there.
 */
function emptyConversation({ readOnly, canAsk }: ConversationAccess, developer: MessageAuthor) {
	if (canAsk) {
		return <Greeting />;
	}
	return readOnly ? <NoMessagesYet name={developer.name} /> : undefined;
}

/** The turn the composer's Stop ends: the live one. */
function stoppableTurn(state: HephTimelineState): string | undefined {
	return state.status === "ready" ? liveTurnOf(state.turns)?.id : undefined;
}

/** Who may do what, as the page that shows the conversation decides it. */
interface ConversationAccess {
	/** A user view: no writes at all. */
	readOnly: boolean;
	/** A composer to ask in. */
	canAsk: boolean;
}

export interface MainConversationProps extends ConversationAccess {
	workspaceSlug: string;
	livePaused: boolean;
	threads: TimelineThreads;
	/** The page the developer asks from, sent with each message while the chip stays. */
	pageContext?: { label: string; chip: ReactNode };
	jumpTo?: string;
	/** The Heph panel was just opened, so its composer takes focus. */
	focusComposer?: boolean;
}

export function MainConversation({
	workspaceSlug,
	readOnly,
	canAsk,
	livePaused,
	threads,
	pageContext,
	jumpTo,
	focusComposer = false,
}: MainConversationProps) {
	const developer = useDeveloper();
	const state = useHephTimeline(workspaceSlug, readOnly);
	const actions = useHephActions(workspaceSlug);
	// Reading the conversation here, on the mentor page or in the Heph panel, clears the launcher's dot,
	// for answers in its threads too. A user view reads another member's conversation, not the reader's.
	useMarkHephRead(workspaceSlug, !readOnly);
	const stoppable = stoppableTurn(state);
	return (
		<HephTimeline
			label="Conversation with Heph"
			workspaceSlug={workspaceSlug}
			state={state}
			developer={developer}
			onCopy={copy}
			threads={threads}
			livePaused={livePaused}
			jumpTo={jumpTo}
			actions={offeredActions(actions, { readOnly, canAsk })}
			empty={emptyConversation({ readOnly, canAsk }, developer)}
			composer={
				canAsk ? (
					<HephComposer
						label="Message Heph"
						placeholder="Message Heph"
						focusOnMount={focusComposer}
						draftKey={`${workspaceSlug}:main`}
						context={pageContext?.chip}
						onSend={(text) => actions.send(text, { pageContext: pageContext?.label })}
						stop={
							stoppable === undefined
								? undefined
								: { label: "Stop Heph", onStop: () => actions.onStop(stoppable) }
						}
					/>
				) : undefined
			}
		/>
	);
}

export interface ThreadConversationProps extends ConversationAccess {
	workspaceSlug: string;
	parentEntryId: string;
	livePaused: boolean;
	/** The thread opened to reply in, so its composer takes focus. */
	focusComposer: boolean;
	jumpTo?: string;
}

export function ThreadConversation({
	workspaceSlug,
	parentEntryId,
	readOnly,
	canAsk,
	livePaused,
	focusComposer,
	jumpTo,
}: ThreadConversationProps) {
	const developer = useDeveloper();
	const thread = useHephThread(workspaceSlug, parentEntryId, readOnly);
	const actions = useHephActions(workspaceSlug);
	switch (thread.status) {
		case "loading": {
			return <HephTimelineSkeleton rows={2} label="Loading the thread…" />;
		}
		case "missing": {
			return <ThreadGone />;
		}
		case "error": {
			return (
				<HephTimeline
					label="Thread"
					workspaceSlug={workspaceSlug}
					state={thread}
					developer={developer}
					onCopy={copy}
					livePaused={livePaused}
				/>
			);
		}
		case "ready": {
			const { parent, timeline } = thread;
			const stoppable = stoppableTurn(timeline);
			const replies = parent.thread?.replyCount ?? 0;
			// The server refuses a reply to a deleted message. Its thread stays to read, rate and delete
			// in, and a running answer keeps Stop on its own line.
			const canAskHere = canAsk && !parent.deleted;
			return (
				<HephTimeline
					label="Thread"
					workspaceSlug={workspaceSlug}
					state={timeline}
					developer={developer}
					onCopy={copy}
					livePaused={livePaused}
					jumpTo={jumpTo}
					lead={
						<div className="flex flex-col gap-4">
							<HephMessage message={parent} developer={developer} workspaceSlug={workspaceSlug}>
								{parent.kind === "DIGEST" && !parent.deleted && (
									<WeeklyDigestCard digest={parent.digest} workspaceSlug={workspaceSlug} />
								)}
							</HephMessage>
							{replies > 0 && (
								<Marker variant="separator">
									<MarkerContent>{replies === 1 ? "1 reply" : `${replies} replies`}</MarkerContent>
								</Marker>
							)}
						</div>
					}
					actions={offeredActions(actions, { readOnly, canAsk: canAskHere })}
					composer={
						canAskHere ? (
							<HephComposer
								label="Reply in thread"
								placeholder="Reply in thread"
								focusOnMount={focusComposer}
								draftKey={`${workspaceSlug}:thread:${parentEntryId}`}
								onSend={(text) => actions.send(text, { parentEntryId: parent.id })}
								stop={
									stoppable === undefined
										? undefined
										: { label: "Stop Heph in this thread", onStop: () => actions.onStop(stoppable) }
								}
							/>
						) : undefined
					}
				/>
			);
		}
	}
}
