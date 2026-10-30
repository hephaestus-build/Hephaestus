import { useQuery } from "@tanstack/react-query";
import { CatchBoundary, useLocation, useMatches, useNavigate } from "@tanstack/react-router";
import { cn } from "cn";
import { useEffect, useId, useRef, useState } from "react";
import { flushSync } from "react-dom";

import { getMemberOnboardingOptions } from "@/api/@tanstack/react-query.gen";
import { RemovableToken } from "@/components/common/RemovableToken";
import { ConversationPanel } from "@/components/conversation/ConversationPanel";
import type { ThreadFocus } from "@/components/conversation/ConversationTimeline";
import { useActiveWorkspaceSlug } from "@/hooks/use-active-workspace";
import { useConversationUnread } from "@/hooks/use-conversation";
import { useConversationLive } from "@/hooks/use-conversation-live";
import { conversationNotice } from "@/lib/conversation-availability";
import { isRecord } from "@/lib/is-record";
import { withoutAppName } from "@/lib/page-title";
import { hasText } from "@/lib/text";
import { useAuth } from "@/runtime/auth/AuthContext";

import {
	dismissConversationUnavailable,
	showConversationUnavailable,
} from "./-conversation-unavailable";
import { MainConversation, ThreadConversation } from "./-ConversationView";

/**
 * Keyed on the workspace so the panel never shows another workspace's conversation, and gated on
 * the member's AI choice there: whenever Hephaestus cannot answer them, the launcher hides rather than
 * letting a send fail.
 */
export default function GlobalConversationPanel() {
	const { workspaceSlug } = useActiveWorkspaceSlug();
	const { isAuthenticated, isLoading } = useAuth();
	const preference = useQuery({
		...getMemberOnboardingOptions({ path: { workspaceSlug: workspaceSlug ?? "" } }),
		enabled: Boolean(workspaceSlug),
	});
	// Each Retry renders the panel again; one that fails after a retry offers a page load instead.
	const [retries, setRetries] = useState(0);
	if (
		!hasText(workspaceSlug) ||
		isLoading ||
		!isAuthenticated ||
		!preference.isSuccess ||
		conversationNotice(preference.data)
	) {
		return null;
	}
	// Inside the gate above, so the message shows only where Hephaestus was going to show.
	return (
		<CatchBoundary
			getResetKey={() => `${workspaceSlug}/${retries}`}
			onCatch={() => {
				showConversationUnavailable(
					retries === 0
						? { again: false, retry: () => setRetries((count) => count + 1) }
						: { again: true },
				);
			}}
			errorComponent={ConversationPanelFailed}
		>
			<WorkspaceConversationPanel key={workspaceSlug} workspaceSlug={workspaceSlug} />
		</CatchBoundary>
	);
}

/** The toast from `onCatch` says what happened; the launcher's place stays empty. */
function ConversationPanelFailed() {
	return null;
}

function titleOf(meta: unknown): string | undefined {
	if (!Array.isArray(meta)) {
		return undefined;
	}
	const title = meta.find(
		(tag): tag is { title: string } => isRecord(tag) && typeof tag.title === "string",
	)?.title;
	return title === undefined ? undefined : withoutAppName(title);
}

/** The open page as its title names it, such as "Practice profile", for Hephaestus to know where it is asked. */
function usePageName(): string | undefined {
	return useMatches({
		select: (matches) =>
			matches
				.map(({ meta }) => titleOf(meta))
				.reverse()
				.find(hasText),
	});
}

/** The thread pushed over the conversation, and where opening it put focus until the next open. */
interface PanelThread {
	parentEntryId: string;
	focus?: ThreadFocus;
}

function WorkspaceConversationPanel({ workspaceSlug }: { workspaceSlug: string }) {
	// Rendered again, as after a Retry: Hephaestus is back, so the message about it goes.
	useEffect(() => {
		dismissConversationUnavailable();
	}, []);
	const navigate = useNavigate();
	// The stream runs while the launcher shows, so an answer that lands with the panel closed marks it.
	const livePaused = useConversationLive(workspaceSlug, true);
	const unread = useConversationUnread(workspaceSlug);
	const [thread, setThread] = useState<PanelThread>();
	// The control that opened the thread, which Back gives focus back to.
	const opener = useRef<HTMLElement>(null);
	// A new page closes the panel, so a link followed from an answer opens its page in front of the
	// panel rather than under it. Adjusted during render, not in an effect, so the page never paints
	// under the panel first; the stream and the open thread stay for the next open.
	const { pathname } = useLocation();
	const [open, setOpen] = useState(false);
	const [openedOn, setOpenedOn] = useState(pathname);
	if (pathname !== openedOn) {
		setOpenedOn(pathname);
		setOpen(false);
	}
	return (
		<ConversationPanel
			open={open}
			onOpenChange={(next) => {
				setOpen(next);
				// The thread stays open, but where Reply in thread put focus applied once: opening the panel
				// again is an ordinary open, which raises no keyboard on a touch screen.
				if (next) {
					setThread((kept) => kept && { parentEntryId: kept.parentEntryId });
				}
			}}
			unread={unread}
			onOpenInConversation={() => {
				void navigate({
					to: "/w/$workspaceSlug/conversation",
					params: { workspaceSlug },
					search: thread ? { thread: thread.parentEntryId } : {},
				});
			}}
			thread={
				thread && {
					focusTitle: thread.focus === "heading",
					onBack: () => {
						flushSync(() => setThread(undefined));
						opener.current?.focus();
					},
				}
			}
		>
			<ConversationPanelBody
				workspaceSlug={workspaceSlug}
				livePaused={livePaused}
				thread={thread}
				onOpenThread={(parentEntryId, focus) => {
					opener.current =
						document.activeElement instanceof HTMLElement ? document.activeElement : null;
					setThread({ parentEntryId, focus });
				}}
			/>
		</ConversationPanel>
	);
}

/** Mounted only while the panel is open. */
function ConversationPanelBody({
	workspaceSlug,
	livePaused,
	thread,
	onOpenThread,
}: {
	workspaceSlug: string;
	livePaused: boolean;
	thread: PanelThread | undefined;
	onOpenThread: (parentEntryId: string, focus: ThreadFocus) => void;
}) {
	const panelId = useId();
	const pageName = usePageName();
	// The chip comes back on the next page: removing it drops this page only.
	const { pathname } = useLocation();
	const [removedFrom, setRemovedFrom] = useState<string>();
	const pageContext =
		hasText(pageName) && removedFrom !== pathname
			? {
					label: pageName,
					chip: (
						<RemovableToken
							label={`From ${pageName}`}
							removeLabel="Do not send this page with your message"
							onRemove={() => setRemovedFrom(pathname)}
						/>
					),
				}
			: undefined;

	return (
		<div className="grid min-h-0 flex-1 grid-cols-1 grid-rows-1">
			{/* Hidden, not unmounted, under an open thread: Back finds the reader's place and focus as they left them. */}
			<div
				className={cn("col-start-1 row-start-1 flex min-h-0 flex-col", thread && "invisible")}
				inert={thread !== undefined}
			>
				<MainConversation
					workspaceSlug={workspaceSlug}
					readOnly={false}
					canAsk
					livePaused={livePaused}
					// Not on a touch screen, where focusing the field raises the keyboard over the answer the
					// developer opened the panel to read. Reply in thread still focuses its composer there.
					focusComposer={window.matchMedia("(pointer: fine)").matches}
					threads={{ panelId, onOpen: onOpenThread }}
					pageContext={pageContext}
				/>
			</div>
			{thread && (
				<div className="col-start-1 row-start-1 flex min-h-0 flex-col bg-background">
					<ThreadConversation
						key={thread.parentEntryId}
						workspaceSlug={workspaceSlug}
						parentEntryId={thread.parentEntryId}
						readOnly={false}
						canAsk
						livePaused={livePaused}
						focusComposer={thread.focus === "composer"}
					/>
				</div>
			)}
		</div>
	);
}
