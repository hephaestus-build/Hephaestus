import { useQuery } from "@tanstack/react-query";
import { useLocation, useMatches, useNavigate } from "@tanstack/react-router";
import { cn } from "cn";
import { useId, useRef, useState } from "react";
import { flushSync } from "react-dom";

import { getMemberOnboardingOptions } from "@/api/@tanstack/react-query.gen";
import { RemovableToken } from "@/components/common/RemovableToken";
import { HephPanel } from "@/components/mentor/HephPanel";
import type { ThreadFocus } from "@/components/mentor/HephTimeline";
import { useActiveWorkspaceSlug } from "@/hooks/use-active-workspace";
import { useHephUnread } from "@/hooks/use-heph-conversation";
import { useHephLive } from "@/hooks/use-heph-live";
import { isRecord } from "@/lib/is-record";
import { mentorNotice } from "@/lib/mentor-preference";
import { withoutAppName } from "@/lib/page-title";
import { hasText } from "@/lib/text";
import { useAuth } from "@/runtime/auth/AuthContext";

import { MainConversation, ThreadConversation } from "./-HephConversationView";

/**
 * Keyed on the workspace so the panel never shows another workspace's conversation, and gated on
 * the member's AI choice there: whenever Heph cannot answer them, the launcher hides rather than
 * letting a send fail.
 */
export default function GlobalHephPanel() {
	const { workspaceSlug } = useActiveWorkspaceSlug();
	const { isAuthenticated, isLoading } = useAuth();
	const preference = useQuery({
		...getMemberOnboardingOptions({ path: { workspaceSlug: workspaceSlug ?? "" } }),
		enabled: Boolean(workspaceSlug),
	});
	if (
		!hasText(workspaceSlug) ||
		isLoading ||
		!isAuthenticated ||
		!preference.isSuccess ||
		mentorNotice(preference.data)
	) {
		return null;
	}
	return <WorkspaceHephPanel key={workspaceSlug} workspaceSlug={workspaceSlug} />;
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

/** The open page as its title names it, such as "Practice profile", for Heph to know where it is asked. */
function usePageName(): string | undefined {
	return useMatches({
		select: (matches) =>
			matches
				.map(({ meta }) => titleOf(meta))
				.reverse()
				.find(hasText),
	});
}

function WorkspaceHephPanel({ workspaceSlug }: { workspaceSlug: string }) {
	const navigate = useNavigate();
	// The stream runs while the launcher shows, so an answer that lands with the panel closed marks it.
	const livePaused = useHephLive(workspaceSlug, true);
	const unread = useHephUnread(workspaceSlug);
	const [thread, setThread] = useState<{ parentEntryId: string; focus: ThreadFocus }>();
	// The control that opened the thread, which Back gives focus back to.
	const opener = useRef<HTMLElement>(null);
	return (
		<HephPanel
			unread={unread}
			onOpenInMentor={() => {
				void navigate({
					to: "/w/$workspaceSlug/mentor",
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
			<HephPanelConversation
				workspaceSlug={workspaceSlug}
				livePaused={livePaused}
				thread={thread}
				onOpenThread={(parentEntryId, focus) => {
					opener.current =
						document.activeElement instanceof HTMLElement ? document.activeElement : null;
					setThread({ parentEntryId, focus });
				}}
			/>
		</HephPanel>
	);
}

/** Mounted only while the panel is open. */
function HephPanelConversation({
	workspaceSlug,
	livePaused,
	thread,
	onOpenThread,
}: {
	workspaceSlug: string;
	livePaused: boolean;
	thread: { parentEntryId: string; focus: ThreadFocus } | undefined;
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
					focusComposer
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
						focusComposer={thread.focus === "composer"}
					/>
				</div>
			)}
		</div>
	);
}
