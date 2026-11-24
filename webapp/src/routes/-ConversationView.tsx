import { type ReactNode, useState } from "react";

import { ConversationComposer } from "@/components/conversation/ConversationComposer";
import {
	ConversationMessage,
	type MessageAuthor,
} from "@/components/conversation/ConversationMessage";
import {
	ConversationTimeline,
	type ConversationTimelineState,
	type TimelineThreads,
} from "@/components/conversation/ConversationTimeline";
import { ConversationTimelineSkeleton } from "@/components/conversation/ConversationTimelineSkeleton";
import { Greeting } from "@/components/conversation/Greeting";
import { ThreadGone } from "@/components/conversation/ThreadPanel";
import { liveTurnOf } from "@/components/conversation/timeline-rows";
import { WeeklyDigestCard } from "@/components/conversation/WeeklyDigestCard";
import { Marker, MarkerContent } from "@/components/ui/marker";
import { useActiveWorkspaceSlug } from "@/hooks/use-active-workspace";
import {
	type ConversationActions,
	useConversationActions,
	useConversationThread,
	useConversationTimeline,
	useMarkConversationRead,
} from "@/hooks/use-conversation";
import { copyToClipboard } from "@/lib/clipboard";
import { readDraft, writeDraft } from "@/lib/composer-drafts";
import { firstNonBlank, hasText } from "@/lib/text";
import { useAuth } from "@/runtime/auth/AuthContext";

/**
 * The containers of the developer's Hephaestus conversation, shared by the conversation page and the Hephaestus panel:
 * they read the conversation through the hooks and hand the components plain props.
 */

const copy = (text: string) => copyToClipboard(text, "Message copied");

/**
 * The name of the workspace whose conversation this is, for the AI disclosure. The workspace list is
 * the reader's own, so a workspace it does not hold leaves the name unknown.
 */
function useWorkspaceName(workspaceSlug: string): string | undefined {
	const { workspaces } = useActiveWorkspaceSlug();
	return workspaces.find((workspace) => workspace.workspaceSlug === workspaceSlug)?.displayName;
}

/** Signs the developer's messages with the signed-in account. */
function useDeveloper(): MessageAuthor {
	const { userProfile, username, getUserProfilePictureUrl } = useAuth();
	return {
		name: firstNonBlank(userProfile?.name, username) ?? "You",
		avatarUrl: getUserProfilePictureUrl(),
	};
}

/**
 * The writes the conversation offers: no asking again — Reply in thread, Try again, Retry — while
 * the developer cannot ask Hephaestus.
 */
function offeredActions(actions: ConversationActions, canAsk: boolean) {
	return canAsk ? actions : { ...actions, ask: undefined };
}

/**
 * What stands in for a conversation with no message. Where the developer cannot ask, the notice
 * above the conversation already says why and what to do, so a second empty state under it would
 * only repeat that nothing is there.
 */
function emptyConversation(canAsk: boolean, onAsk: ((question: string) => void) | undefined) {
	return canAsk ? <Greeting onAsk={onAsk} /> : undefined;
}

/** The turn the composer's Stop ends: the live one. */
function stoppableTurn(state: ConversationTimelineState): string | undefined {
	return state.status === "ready" ? liveTurnOf(state.turns)?.id : undefined;
}

/** Who may do what, as the page that shows the conversation decides it. */
interface ConversationAccess {
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
	/** The Hephaestus panel was just opened, so its composer takes focus. */
	focusComposer?: boolean;
}

export function MainConversation({
	workspaceSlug,
	canAsk,
	livePaused,
	threads,
	pageContext,
	jumpTo,
	focusComposer = false,
}: MainConversationProps) {
	const developer = useDeveloper();
	const workspaceName = useWorkspaceName(workspaceSlug);
	const state = useConversationTimeline(workspaceSlug);
	const actions = useConversationActions(workspaceSlug);
	// Reading the conversation here, on the conversation page or in the Hephaestus panel, clears the
	// header button's dot, for answers in its threads too.
	useMarkConversationRead(workspaceSlug);
	const stoppable = stoppableTurn(state);
	const draftKey = `${workspaceSlug}:main`;
	// An example question goes into an empty draft, and the composer remounts to read it and take
	// focus. Examples show only while nothing is typed, so one never replaces the developer's words.
	const [composerMount, setComposerMount] = useState(0);
	const [drafting, setDrafting] = useState(() => hasText(readDraft(draftKey)));
	const askExample = (question: string) => {
		writeDraft(draftKey, question);
		setDrafting(true);
		setComposerMount((mount) => mount + 1);
	};
	return (
		<ConversationTimeline
			label="Your conversation"
			workspaceSlug={workspaceSlug}
			workspaceName={workspaceName}
			state={state}
			developer={developer}
			onCopy={copy}
			threads={threads}
			livePaused={livePaused}
			jumpTo={jumpTo}
			actions={offeredActions(actions, canAsk)}
			empty={emptyConversation(canAsk, drafting ? undefined : askExample)}
			composer={
				canAsk ? (
					<ConversationComposer
						key={composerMount}
						label="Message Hephaestus"
						placeholder="Message Hephaestus"
						focusOnMount={focusComposer || composerMount > 0}
						draftKey={draftKey}
						onDraftChange={(text) => setDrafting(hasText(text))}
						context={pageContext?.chip}
						onSend={(text) => actions.send(text, { pageContext: pageContext?.label })}
						stop={
							stoppable === undefined
								? undefined
								: { label: "Stop answer", onStop: () => actions.onStop(stoppable) }
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
	canAsk,
	livePaused,
	focusComposer,
	jumpTo,
}: ThreadConversationProps) {
	const developer = useDeveloper();
	const workspaceName = useWorkspaceName(workspaceSlug);
	const thread = useConversationThread(workspaceSlug, parentEntryId);
	const actions = useConversationActions(workspaceSlug);
	switch (thread.status) {
		case "loading": {
			return <ConversationTimelineSkeleton rows={2} label="Loading the thread…" />;
		}
		case "missing": {
			return <ThreadGone />;
		}
		case "error": {
			return (
				<ConversationTimeline
					label="Thread"
					workspaceSlug={workspaceSlug}
					workspaceName={workspaceName}
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
				<ConversationTimeline
					label="Thread"
					workspaceSlug={workspaceSlug}
					workspaceName={workspaceName}
					state={timeline}
					developer={developer}
					onCopy={copy}
					livePaused={livePaused}
					jumpTo={jumpTo}
					lead={
						<div className="flex flex-col gap-4">
							<ConversationMessage
								message={parent}
								developer={developer}
								workspaceSlug={workspaceSlug}
							>
								{parent.kind === "DIGEST" && !parent.deleted && (
									<WeeklyDigestCard digest={parent.digest} workspaceSlug={workspaceSlug} />
								)}
							</ConversationMessage>
							{replies > 0 && (
								<Marker variant="separator">
									<MarkerContent>{replies === 1 ? "1 reply" : `${replies} replies`}</MarkerContent>
								</Marker>
							)}
						</div>
					}
					actions={offeredActions(actions, canAskHere)}
					composer={
						canAskHere ? (
							<ConversationComposer
								label="Reply in thread"
								placeholder="Reply in thread"
								focusOnMount={focusComposer}
								draftKey={`${workspaceSlug}:thread:${parentEntryId}`}
								onSend={(text) => actions.send(text, { parentEntryId: parent.id })}
								stop={
									stoppable === undefined
										? undefined
										: {
												label: "Stop answer in this thread",
												onStop: () => actions.onStop(stoppable),
											}
								}
							/>
						) : undefined
					}
				/>
			);
		}
	}
}
