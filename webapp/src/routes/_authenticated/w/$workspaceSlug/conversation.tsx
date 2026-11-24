import { useSuspenseQuery } from "@tanstack/react-query";
import { createFileRoute, useLocation } from "@tanstack/react-router";
// oxlint-disable-next-line no-restricted-imports -- React detaches and reattaches a ref callback whose identity changes. The observer must survive a render.
import { useCallback, useId, useState } from "react";
import { conversationSearchSchema } from "@/lib/conversation-search";

import { getMemberOnboardingOptions } from "@/api/@tanstack/react-query.gen";
import type { ThreadFocus } from "@/components/conversation/ConversationTimeline";
import { pixelsPerRem, ThreadPanel } from "@/components/conversation/ThreadPanel";
import { WorkspaceConversationNotice } from "@/components/onboarding/WorkspaceConversationNotice";
import { useConversationLive } from "@/hooks/use-conversation-live";
import { conversationNotice } from "@/lib/conversation-availability";
import { pageHead } from "@/lib/page-title";
import { useSearchState } from "@/lib/search-params";

import { MainConversation, ThreadConversation } from "@/routes/-ConversationView";

/**
 * `thread` opens the thread on that message beside the conversation, and `entry` brings a message
 * into view — in that thread when one is open — so a link from Slack or a weekly digest lands where
 * it points.
 */

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/conversation")({
	head: pageHead("Ask Hephaestus"),
	staticData: { surface: "fullscreen" },
	validateSearch: conversationSearchSchema,
	// A draft, an open thread and the live stream belong to one workspace.
	remountDeps: ({ params }) => params.workspaceSlug,
	// Usually a cache hit, since the workspace gate fetched this on the way in; when the gate's
	// fetch failed, a cold-load outage reaches the router error surface rather than a skeleton.
	loader: async ({ context, params }) =>
		context.queryClient.query({
			...getMemberOnboardingOptions({ path: params }),
			staleTime: "static",
			retry: false,
		}),
	component: ConversationPage,
});

/** The signed-in developer's conversation. A thread opens beside it from `?thread=`. */
function ConversationPage() {
	const { workspaceSlug } = Route.useParams();
	const preference = useSuspenseQuery(getMemberOnboardingOptions({ path: { workspaceSlug } }));
	const notice = conversationNotice(preference.data);
	const { thread, entry } = Route.useSearch();
	const returnTo = useLocation().href;
	const setSearch = useSearchState();
	const panelId = useId();
	const [threadFocus, setThreadFocus] = useState<ThreadFocus>();
	// The region the conversation and its thread share. Its width does not change with how the
	// thread shows, so the choice between a column and a drawer cannot flip back and forth, and it
	// already leaves out the sidebar, collapsed or open, and the rest of the page.
	const [availableRem, setAvailableRem] = useState<number>();
	const host = useCallback((element: HTMLDivElement | null) => {
		if (!element || typeof ResizeObserver === "undefined") {
			return;
		}
		const observer = new ResizeObserver(([measurement]) => {
			if (measurement) {
				const width = measurement.contentBoxSize[0]?.inlineSize ?? measurement.contentRect.width;
				// A text-size change can change the height while the width stays the same.
				setAvailableRem(width / pixelsPerRem());
			}
		});
		observer.observe(element);
		return () => observer.disconnect();
	}, []);
	const livePaused = useConversationLive(workspaceSlug, true);
	const canAsk = notice === undefined;

	return (
		<div ref={host} className="flex min-h-0 flex-1">
			<div className="flex min-h-0 min-w-0 flex-1 flex-col">
				{notice ? (
					<WorkspaceConversationNotice
						workspaceSlug={workspaceSlug}
						returnTo={returnTo}
						notice={notice}
					/>
				) : (
					<h1 className="sr-only">Ask Hephaestus</h1>
				)}
				<h2 className="sr-only">Your conversation</h2>
				<MainConversation
					workspaceSlug={workspaceSlug}
					readOnly={false}
					canAsk={canAsk}
					livePaused={livePaused}
					jumpTo={thread === undefined ? entry : undefined}
					threads={{
						panelId,
						...(thread === undefined ? {} : { openParentId: thread }),
						onOpen: (parentEntryId, focus) => {
							setThreadFocus(focus);
							void setSearch((previous) => ({
								...previous,
								thread: parentEntryId,
								entry: undefined,
							}));
						},
					}}
				/>
			</div>
			{thread !== undefined && (
				<ThreadPanel
					key={thread}
					id={panelId}
					focusHeading={threadFocus === "heading"}
					availableRem={availableRem}
					onClose={() => {
						setThreadFocus(undefined);
						void setSearch((previous) => ({ ...previous, thread: undefined, entry: undefined }));
					}}
				>
					<ThreadConversation
						workspaceSlug={workspaceSlug}
						parentEntryId={thread}
						readOnly={false}
						canAsk={canAsk}
						livePaused={livePaused}
						focusComposer={threadFocus === "composer"}
						jumpTo={entry}
					/>
				</ThreadPanel>
			)}
		</div>
	);
}
