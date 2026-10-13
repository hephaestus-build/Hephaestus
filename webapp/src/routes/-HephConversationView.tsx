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
import { Marker, MarkerContent } from "@/components/ui/marker";
import {
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

const copy = (text: string) => copyToClipboard(text, "Copied.");

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
			actions={readOnly ? undefined : actions}
			empty={canAsk ? <Greeting /> : undefined}
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
	/** The thread opened to reply in, so its composer takes focus. */
	focusComposer: boolean;
	jumpTo?: string;
}

export function ThreadConversation({
	workspaceSlug,
	parentEntryId,
	readOnly,
	canAsk,
	focusComposer,
	jumpTo,
}: ThreadConversationProps) {
	const developer = useDeveloper();
	const thread = useHephThread(workspaceSlug, parentEntryId, readOnly);
	const actions = useHephActions(workspaceSlug);
	switch (thread.status) {
		case "loading": {
			return <HephTimelineSkeleton rows={2} />;
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
				/>
			);
		}
		case "ready": {
			const { parent, timeline } = thread;
			const stoppable = stoppableTurn(timeline);
			const replies = parent.thread?.replyCount ?? 0;
			return (
				<HephTimeline
					label="Thread"
					workspaceSlug={workspaceSlug}
					state={timeline}
					developer={developer}
					onCopy={copy}
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
					actions={readOnly ? undefined : actions}
					composer={
						canAsk ? (
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
