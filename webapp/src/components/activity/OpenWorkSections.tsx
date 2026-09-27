import { ChevronDownIcon, CircleDotIcon, GitPullRequestIcon, InboxIcon } from "lucide-react";
import { useId, useState } from "react";

import type { OpenWork, WorkItemList } from "@/api/types.gen";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { Section } from "@/components/layout/Section";
import { spell } from "@/components/practice-vocabulary/feedback-text";
import { Button } from "@/components/ui/button";
import { Collapsible, CollapsibleTrigger } from "@/components/ui/collapsible";
import { Skeleton } from "@/components/ui/skeleton";
import { ARTIFACT_KIND, artifactKindNoun } from "@/lib/artifact-kinds";
import type { ProviderType } from "@/lib/provider/provider-terms";
import { capitalise } from "@/lib/text";

import { ActivityEmpty, type ActivityEmptyProps } from "./ActivityEmpty";
import { WorkItemRow } from "./WorkItemRow";

export interface OpenWorkSectionsProps {
	state: PanelState<{ openWork: OpenWork }>;
	providerType: ProviderType;
	/**
	 * Whose work: your own page speaks to you and its sections are the page's; a member's level names the
	 * work plainly and nests its sections under the level's title.
	 */
	perspective: "self" | "member";
	/**
	 * The person the open work is about, once the page knows it; a row names its author only when it is
	 * someone else.
	 */
	login?: string;
}

const issueNoun = (n: number) => artifactKindNoun(ARTIFACT_KIND.issue, n);

/** How many rows a list shows before it asks for the rest. */
const FIRST_ROWS = 5;

/** What is open right now, most recently moved first: what waits on this person, then their own open work. */
export function OpenWorkSections({
	state,
	providerType,
	perspective,
	login,
}: OpenWorkSectionsProps) {
	const pullRequests = artifactKindNoun(ARTIFACT_KIND.pullRequest, 2, providerType);
	const pullRequestNoun = (n: number) =>
		artifactKindNoun(ARTIFACT_KIND.pullRequest, n, providerType);
	const self = perspective === "self";
	const whose = self ? "your" : "their";
	const level = self ? 2 : 3;
	const size = self ? "lg" : "md";

	if (state.status === "error") {
		return (
			<QueryErrorAlert
				error={state.error}
				title="Couldn't load open work"
				onRetry={state.onRetry}
			/>
		);
	}
	const openWork = state.status === "ready" ? state.openWork : undefined;
	return (
		<>
			<Section
				level={level}
				size={size}
				title={
					<Counted
						title={self ? "Waiting on you" : "Review requests"}
						items={openWork?.reviewRequests}
						noun={pullRequestNoun}
					/>
				}
				description={self ? `Open ${pullRequests} that ask for your review.` : undefined}
			>
				<WorkList
					items={openWork?.reviewRequests}
					providerType={providerType}
					login={login}
					empty={{
						icon: <InboxIcon />,
						title: self ? "Nobody is waiting on your review" : "No review requests",
						description: `Open ${pullRequests} that ask for ${whose} review show up here.`,
					}}
				/>
			</Section>
			<Section
				level={level}
				size={size}
				title={
					<Counted
						title={self ? `Your open ${pullRequests}` : `Open ${pullRequests}`}
						items={openWork?.pullRequests}
						noun={pullRequestNoun}
					/>
				}
			>
				<WorkList
					items={openWork?.pullRequests}
					providerType={providerType}
					login={login}
					empty={{
						icon: <GitPullRequestIcon />,
						title: self ? `You have no open ${pullRequests}` : `No open ${pullRequests}`,
						description: `${capitalise(pullRequests)} ${self ? "you" : "they"} open show up here until they are merged or closed.`,
					}}
				/>
			</Section>
			<Section
				level={level}
				size={size}
				title={
					<Counted
						title={self ? "Assigned to you" : "Assigned issues"}
						items={openWork?.issues}
						noun={issueNoun}
					/>
				}
			>
				<WorkList
					items={openWork?.issues}
					providerType={providerType}
					login={login}
					empty={{
						icon: <CircleDotIcon />,
						title: self ? "No open issues assigned to you" : "No open issues assigned",
						description: `Issues assigned to ${self ? "you" : "them"} show up here until they are closed.`,
					}}
				/>
			</Section>
		</>
	);
}

/** The section's title with its count, drawn as a tab counts its contents. */
function Counted({
	title,
	items,
	noun,
}: {
	title: string;
	items: WorkItemList | undefined;
	noun: (n: number) => string;
}) {
	const count = items?.content.length ?? 0;
	if (count === 0) {
		return title;
	}
	return (
		<>
			{title}{" "}
			<span className="font-normal text-muted-foreground tabular-nums">
				{count}
				{items?.hasMore === true && "+"}
				<span className="sr-only"> {noun(count)}</span>
			</span>
		</>
	);
}

function WorkList({
	items,
	providerType,
	login,
	empty,
}: {
	items: WorkItemList | undefined;
	providerType: ProviderType;
	login: string | undefined;
	empty: ActivityEmptyProps;
}) {
	const listId = useId();
	const [expanded, setExpanded] = useState(false);
	if (items === undefined) {
		return (
			<div aria-busy="true">
				<span className="sr-only">Loading open work</span>
				<div className="space-y-2 rounded-xl border bg-card p-3" aria-hidden>
					<Skeleton className="h-4 w-3/4" />
					<Skeleton className="h-3.5 w-1/2" />
				</div>
			</div>
		);
	}
	const { content, hasMore } = items;
	if (content.length === 0) {
		return <ActivityEmpty {...empty} />;
	}
	const rest = content.length - FIRST_ROWS;
	const shown = expanded ? content : content.slice(0, FIRST_ROWS);
	return (
		<Collapsible open={expanded} onOpenChange={setExpanded}>
			<ul id={listId} className="overflow-hidden rounded-xl border bg-card">
				{shown.map((work) => (
					<WorkItemRow key={work.id} work={work} providerType={providerType} login={login} />
				))}
			</ul>
			{rest > 0 && (
				<CollapsibleTrigger
					aria-controls={listId}
					render={<Button variant="link" size="inline" className="group mt-2 w-fit text-sm" />}
				>
					{expanded ? "Show less" : `Show ${spell(rest)} more`}
					<ChevronDownIcon
						className="size-3.5 transition-transform group-aria-expanded:rotate-180"
						aria-hidden
					/>
				</CollapsibleTrigger>
			)}
			{hasMore && expanded && (
				<p className="mt-2 text-sm text-muted-foreground">
					These are the {spell(content.length)} most recently updated.
				</p>
			)}
		</Collapsible>
	);
}
