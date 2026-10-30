import { useSuspenseQuery } from "@tanstack/react-query";
import { createFileRoute, useLocation } from "@tanstack/react-router";
import { type RefObject, useId, useRef, useState } from "react";
import { useResizeObserver } from "usehooks-ts";
import { conversationSearchSchema } from "@/lib/conversation-search";

import { getMemberOnboardingOptions } from "@/api/@tanstack/react-query.gen";
import type { ThreadFocus } from "@/components/mentor/HephTimeline";
import { pixelsPerRem, ThreadPanel } from "@/components/mentor/ThreadPanel";
import { WorkspaceMentorPreferenceNotice } from "@/components/onboarding/WorkspaceMentorPreferenceNotice";
import { useHephLive } from "@/hooks/use-heph-live";
import { type MentorNotice, mentorNotice } from "@/lib/mentor-preference";
import { pageHead } from "@/lib/page-title";
import { useSearchState } from "@/lib/search-params";
import { useAuth } from "@/runtime/auth/AuthContext";
import { getUserViewSession } from "@/runtime/user-view/session";

import { MainConversation, ThreadConversation } from "@/routes/-HephConversationView";

/**
 * `thread` opens the thread on that message beside the conversation, and `entry` brings a message
 * into view — in that thread when one is open — so a link from Slack or a weekly digest lands where
 * it points.
 */

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/conversation")({
	head: () => pageHead(getUserViewSession() === undefined ? "Ask Hephaestus" : "Conversation")(),
	staticData: { surface: "fullscreen" },
	validateSearch: conversationSearchSchema,
	// A draft, an open thread and the live stream belong to one workspace.
	remountDeps: ({ params }) => params.workspaceSlug,
	// Usually a cache hit, since the workspace gate fetched this on the way in; when the gate's
	// fetch failed, a cold-load outage reaches the router error surface rather than a skeleton.
	// A user view never asks for the saved AI choice: it belongs to the signed-in account.
	loader: async ({ context, params }) =>
		getUserViewSession() === undefined
			? context.queryClient.query({
					...getMemberOnboardingOptions({ path: params }),
					staleTime: "static",
					retry: false,
				})
			: undefined,
	component: MentorRoute,
});

function MentorRoute() {
	const { workspaceSlug } = Route.useParams();
	const { userView } = useAuth();
	return userView ? (
		<MentorPage workspaceSlug={workspaceSlug} readOnly />
	) : (
		<MemberMentorPage workspaceSlug={workspaceSlug} />
	);
}

function MemberMentorPage({ workspaceSlug }: { workspaceSlug: string }) {
	// The loader settled this; a refetch that fails later keeps the last answer on screen rather
	// than hiding a readable conversation behind an alert.
	const preference = useSuspenseQuery(getMemberOnboardingOptions({ path: { workspaceSlug } }));
	const notice = mentorNotice(preference.data);
	return <MentorPage workspaceSlug={workspaceSlug} readOnly={false} notice={notice} />;
}

/**
 * The developer's one conversation with Hephaestus, the same one the Hephaestus panel shows. A thread
 * opens beside it from `?thread=`. A user view reads the viewed member's conversation without a
 * live stream; a member whose AI choice keeps Hephaestus from answering reads theirs without a composer.
 */
function MentorPage({
	workspaceSlug,
	readOnly,
	notice,
}: {
	workspaceSlug: string;
	readOnly: boolean;
	notice?: MentorNotice;
}) {
	const { thread, entry } = Route.useSearch();
	const returnTo = useLocation().href;
	const setSearch = useSearchState();
	const panelId = useId();
	const [threadFocus, setThreadFocus] = useState<ThreadFocus>();
	// The region the conversation and its thread share. Its width does not change with how the
	// thread shows, so the choice between a column and a drawer cannot flip back and forth, and it
	// already leaves out the sidebar, collapsed or open, and the rest of the page.
	const host = useRef<HTMLDivElement>(null);
	const { width: availableWidth } = useResizeObserver({
		// oxlint-disable-next-line typescript/no-unsafe-type-assertion -- usehooks-ts 3 types `ref` without the `| null` that React 19 gives `useRef(null)`; the hook reads `current` only once mounted.
		ref: host as RefObject<HTMLDivElement>,
	});
	// Read with each new measurement, so it follows the reader's text size as it stands then.
	const availableRem = availableWidth === undefined ? undefined : availableWidth / pixelsPerRem();
	const livePaused = useHephLive(workspaceSlug, !readOnly);
	const canAsk = notice === undefined && !readOnly;

	return (
		<div ref={host} className="flex min-h-0 flex-1">
			<div className="flex min-h-0 min-w-0 flex-1 flex-col">
				{notice ? (
					<WorkspaceMentorPreferenceNotice
						workspaceSlug={workspaceSlug}
						returnTo={returnTo}
						notice={notice}
					/>
				) : (
					<h1 className="sr-only">{readOnly ? "Conversation" : "Ask Hephaestus"}</h1>
				)}
				<h2 className="sr-only">{readOnly ? "Conversation messages" : "Your conversation"}</h2>
				<MainConversation
					workspaceSlug={workspaceSlug}
					readOnly={readOnly}
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
						readOnly={readOnly}
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
