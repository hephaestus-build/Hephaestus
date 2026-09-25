import { useQueryClient } from "@tanstack/react-query";
import { createFileRoute, useNavigate } from "@tanstack/react-router";
import { MessagesSquare } from "lucide-react";
import { useEffect, useRef } from "react";
import { v4 as uuidv4 } from "uuid";

import { getThreadQueryKey, listThreadsQueryKey } from "@/api/@tanstack/react-query.gen";
import type { ChatThreadSummary } from "@/api/types.gen";
import { EmptyState } from "@/components/common/EmptyState";
import { NoWorkspace } from "@/components/common/NoWorkspace";
import { Greeting } from "@/components/mentor/Greeting";
import { useActiveWorkspaceSlug } from "@/hooks/use-active-workspace";
import { hasText } from "@/lib/text";
import { useAuth } from "@/runtime/auth/AuthContext";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/mentor/")({
	component: MentorContainer,
});

function MentorContainer() {
	const readOnly = useAuth().userView !== undefined;
	const queryClient = useQueryClient();
	const navigate = useNavigate({ from: Route.fullPath });
	const { workspaceSlug } = useActiveWorkspaceSlug();
	const slug = workspaceSlug ?? "";
	const hasStartedRef = useRef(false);

	// The id is minted here and nothing announces it: no endpoint creates a thread, so this one does
	// not exist server-side until the first message is sent. Both cache writes below stand in for the
	// reads that would otherwise 404 — the thread page fetches this key on mount.
	//
	// Once per mount, guarded by a ref rather than by the dependency list, which cannot promise it:
	// a second run would mint a second id and strand an empty "New chat" in the list.
	useEffect(() => {
		if (readOnly || !hasText(workspaceSlug) || hasStartedRef.current) {
			return;
		}
		hasStartedRef.current = true;

		const threadId = uuidv4();

		queryClient.setQueryData(getThreadQueryKey({ path: { workspaceSlug: slug, threadId } }), {
			messages: [],
		});

		// Flat: `NavMentorThreads` buckets by `createdAt`, so ordering here is not load-bearing.
		queryClient.setQueryData<ChatThreadSummary[]>(
			listThreadsQueryKey({ path: { workspaceSlug: slug } }),
			(prev) => {
				const threads = prev ?? [];
				if (threads.some((t) => t.id === threadId)) {
					return threads;
				}
				const newSummary: ChatThreadSummary = {
					id: threadId,
					title: "New chat",
					createdAt: new Date(),
				};
				return [newSummary, ...threads];
			},
		);

		// `replace`, so Back leaves the mentor rather than landing here and minting another thread.
		// The seeded empty transcript is what makes the thread page render its static greeting; the
		// first message is what starts a real chat turn.
		void navigate({
			to: "/w/$workspaceSlug/mentor/$threadId",
			params: { workspaceSlug: slug, threadId },
			replace: true,
		});
	}, [readOnly, workspaceSlug, slug, queryClient, navigate]);

	if (!hasText(workspaceSlug)) {
		return <NoWorkspace />;
	}
	if (readOnly) {
		return (
			<div className="flex h-full items-center justify-center p-6">
				<EmptyState
					icon={<MessagesSquare />}
					title="No conversation selected"
					description="Open a saved conversation from the conversation list."
				/>
			</div>
		);
	}

	return (
		<div className="flex h-full min-h-0 flex-1 flex-col">
			<Greeting />
		</div>
	);
}
