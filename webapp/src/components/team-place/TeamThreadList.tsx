import { MessagesSquareIcon } from "lucide-react";
import type { ReactElement } from "react";

import { EmptyState } from "@/components/common/EmptyState";
import { InlineLink } from "@/components/common/InlineLink";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { RelativeTime } from "@/components/common/RelativeTime";
import { Skeleton } from "@/components/ui/skeleton";

import { type TeamThreadData, versionGoneText } from "./team-threads";

export type TeamThreadListState =
	| { status: "loading"; rows: number }
	| { status: "error"; error: unknown; onRetry?: () => void }
	| { status: "ready"; threads: readonly TeamThreadData[] };

export interface TeamThreadListProps {
	state: TeamThreadListState;
	/** The review open beside the list. */
	selectedId?: string;
	/** Opens a review and its discussion, such as a router `Link` that sets it in the address. */
	threadLink: (thread: TeamThreadData) => ReactElement;
}

function threadSubtitle(thread: TeamThreadData): string {
	const { versionSequence, versionState } = thread.review;
	return versionGoneText(versionSequence, versionState) ?? `Review of version ${versionSequence}`;
}

/** A team project's reviews, each with its review discussion, most recent activity first. */
export function TeamThreadList({ state, selectedId, threadLink }: TeamThreadListProps) {
	switch (state.status) {
		case "loading": {
			return (
				<ul aria-hidden className="flex flex-col gap-2">
					{Array.from({ length: state.rows }, (_, index) => (
						<li key={index}>
							<Skeleton className="h-14 w-full" />
						</li>
					))}
				</ul>
			);
		}
		case "error": {
			return (
				<QueryErrorAlert
					error={state.error}
					title="Reviews could not load"
					onRetry={state.onRetry}
				/>
			);
		}
		case "ready": {
			if (state.threads.length === 0) {
				return (
					<EmptyState
						icon={<MessagesSquareIcon />}
						title="No reviews yet"
						headingLevel={3}
						description="A review of your team’s work shows here when it is done, with its discussion."
					/>
				);
			}
			const threads = [...state.threads].sort(
				(a, b) => b.lastActivityAt.getTime() - a.lastActivityAt.getTime(),
			);
			return (
				<ul aria-label="Reviews" className="flex flex-col gap-1">
					{threads.map((thread) => (
						<li
							key={thread.id}
							aria-current={thread.id === selectedId ? "true" : undefined}
							className="flex flex-col gap-0.5 rounded-md p-2 text-sm aria-[current=true]:bg-muted"
						>
							<InlineLink render={threadLink(thread)}>
								<bdi>{thread.title}</bdi>
							</InlineLink>
							<span className="flex flex-wrap gap-x-2 text-muted-foreground">
								<span>{threadSubtitle(thread)}</span>
								<RelativeTime value={thread.lastActivityAt} />
							</span>
						</li>
					))}
				</ul>
			);
		}
	}
}
