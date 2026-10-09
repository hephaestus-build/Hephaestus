import { useSuspenseQuery } from "@tanstack/react-query";
import { createFileRoute, useLocation } from "@tanstack/react-router";
import { useId, useState } from "react";
import { z } from "zod";

import { getMemberOnboardingOptions } from "@/api/@tanstack/react-query.gen";
import type { ThreadFocus } from "@/components/mentor/HephTimeline";
import { ThreadPanel } from "@/components/mentor/ThreadPanel";
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
const searchSchema = z.object({
	thread: z.uuid().optional().catch(undefined),
	entry: z.uuid().optional().catch(undefined),
});

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/mentor")({
	head: pageHead("Heph"),
	staticData: { surface: "fullscreen" },
	validateSearch: searchSchema,
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
 * The developer's one conversation with Heph, the same one the Heph panel shows. A thread
 * opens beside it from `?thread=`. A user view reads the viewed member's conversation without a
 * live stream; a member whose AI choice keeps Heph from answering reads theirs without a composer.
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
	const livePaused = useHephLive(workspaceSlug, !readOnly);
	const canAsk = notice === undefined && !readOnly;

	return (
		<div className="flex min-h-0 flex-1">
			<div className="flex min-h-0 min-w-0 flex-1 flex-col">
				{notice ? (
					<WorkspaceMentorPreferenceNotice
						workspaceSlug={workspaceSlug}
						returnTo={returnTo}
						notice={notice}
					/>
				) : (
					<h1 className="sr-only">Heph</h1>
				)}
				<h2 className="sr-only">Conversation with Heph</h2>
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
						focusComposer={threadFocus === "composer"}
						jumpTo={entry}
					/>
				</ThreadPanel>
			)}
		</div>
	);
}
