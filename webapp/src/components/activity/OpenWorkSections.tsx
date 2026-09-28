import { CheckCircleIcon, ChevronRightIcon } from "@primer/octicons-react";
import { cn } from "cn";
import type { OpenWork, WorkItem, WorkItemList } from "@/api/types.gen";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { GitLabCheckCircleIcon } from "@/components/icons/gitlab-icons";
import { Section } from "@/components/layout/Section";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { Skeleton } from "@/components/ui/skeleton";
import { ARTIFACT_KIND, artifactKindNoun } from "@/lib/artifact-kinds";
import type { ProviderType } from "@/lib/provider/provider-terms";
import { andList } from "@/lib/text";

import { ACTIVITY_TONES, providerIcon } from "./activity-tones";
import {
	groupOpenWork,
	OPEN_WORK_GROUP_DEFS,
	OPEN_WORK_GROUPS,
	type OpenWorkGroup,
	type OpenWorkPerspective,
} from "./open-work-groups";
import { WorkItemRow } from "./WorkItemRow";

/** One person's open work, with the login that decides which reviewer entry is theirs. */
export type OpenWorkState = PanelState<{ openWork: OpenWork; login: string }>;

export interface OpenWorkSectionsProps {
	state: OpenWorkState;
	providerType: ProviderType;
	/**
	 * Whose open work: your own page speaks to you and its sections are the page's; a member's level
	 * names the work plainly and nests its sections under the level's title.
	 */
	perspective: OpenWorkPerspective;
}

const NOTHING_ICON = providerIcon(CheckCircleIcon, GitLabCheckCircleIcon);

const COUNTED = OPEN_WORK_GROUPS.filter((group) => OPEN_WORK_GROUP_DEFS[group].counted);
const WAITING = OPEN_WORK_GROUPS.filter((group) => !OPEN_WORK_GROUP_DEFS[group].counted);

/**
 * What is open right now, grouped by the action it needs, as GitHub's pull request inbox and
 * GitLab's merge request homepage group it: review requests and pull requests returned or approved
 * first; then, folded away, what waits on someone else — a request other reviewers already covered
 * sits there, never among the work to do. Assigned issues are their own section.
 */
export function OpenWorkSections({ state, providerType, perspective }: OpenWorkSectionsProps) {
	const self = perspective === "self";
	const level = self ? 2 : 3;
	const title = self ? "Needs you" : "Open work";
	const whose = self ? "your" : "their";
	const NothingIcon = NOTHING_ICON(providerType);
	if (state.status === "error") {
		return (
			<Section level={level} size="lg" title={title}>
				<QueryErrorAlert
					error={state.error}
					title="Couldn't load open work"
					onRetry={state.onRetry}
				/>
			</Section>
		);
	}
	const ready =
		state.status === "ready"
			? { ...state, groups: groupOpenWork(state.openWork, state.login) }
			: undefined;
	return (
		<>
			<Section level={level} size="lg" title={title}>
				{ready ? (
					<div className="space-y-4">
						<GroupLists
							groups={COUNTED}
							items={ready.groups}
							providerType={providerType}
							perspective={perspective}
							login={ready.login}
						/>
						{COUNTED.every((group) => ready.groups[group].length === 0) && (
							<p className="flex items-center gap-2 text-sm text-muted-foreground">
								<NothingIcon size={16} className={cn("shrink-0", ACTIVITY_TONES.success.text)} />
								{self ? "Nothing needs you" : "Nothing needs them"}
							</p>
						)}
						<WaitingOnOthers
							items={ready.groups}
							providerType={providerType}
							perspective={perspective}
							login={ready.login}
						/>
						<Truncation
							lists={[
								{ list: ready.openWork.reviewRequests, of: `${whose} review requests` },
								{ list: ready.openWork.teamReviewRequests, of: `${whose} teams' review requests` },
								{
									list: ready.openWork.pullRequests,
									of: `${whose} open ${artifactKindNoun(ARTIFACT_KIND.pullRequest, 2, providerType)}`,
								},
							]}
						/>
					</div>
				) : (
					<GroupSkeleton rows={2} />
				)}
			</Section>
			<Section level={level} size="lg" title="Assigned issues">
				{ready ? (
					<div className="space-y-4">
						<WorkList
							items={ready.openWork.issues.content}
							providerType={providerType}
							login={ready.login}
							empty={self ? "No issues assigned to you" : "No issues assigned"}
						/>
						<Truncation lists={[{ list: ready.openWork.issues, of: `${whose} assigned issues` }]} />
					</div>
				) : (
					<GroupSkeleton rows={1} />
				)}
			</Section>
		</>
	);
}

interface GroupListsProps {
	groups: readonly OpenWorkGroup[];
	items: Record<OpenWorkGroup, WorkItem[]>;
	providerType: ProviderType;
	perspective: OpenWorkPerspective;
	login: string;
}

/** Each group that holds anything, as a small header over its bordered list. */
function GroupLists({ groups, items, providerType, perspective, login }: GroupListsProps) {
	const Heading = perspective === "self" ? "h3" : "h4";
	return groups
		.filter((group) => items[group].length > 0)
		.map((group) => {
			const def = OPEN_WORK_GROUP_DEFS[group];
			const Icon = def.icon(providerType);
			const count = items[group].length;
			return (
				<div key={group} className="space-y-2">
					<Heading className="flex items-center gap-2 text-sm font-semibold">
						<Icon size={16} className={cn("shrink-0", ACTIVITY_TONES[def.tone].text)} />
						{def.label(perspective)}
						<Badge variant="secondary">{count}</Badge>
					</Heading>
					<WorkList items={items[group]} providerType={providerType} login={login} />
				</div>
			);
		});
}

/**
 * Where the server lists only the most recently updated of a person's review requests, pull
 * requests or assigned issues, one line says so, with the number it listed, since the groups are
 * drawn from those lists and cannot know what lies past them.
 */
function Truncation({ lists }: { lists: readonly { list: WorkItemList; of: string }[] }) {
	const cut = lists
		.filter(({ list }) => list.hasMore)
		.map(({ list, of }) => `${list.content.length} most recently updated of ${of}`);
	if (cut.length === 0) {
		return null;
	}
	return <p className="text-sm text-muted-foreground">Showing the {andList.format(cut)}.</p>;
}

/** What waits on someone else, folded under one count until the reader asks for it. */
function WaitingOnOthers({ items, ...rest }: Omit<GroupListsProps, "groups">) {
	const count = WAITING.reduce((sum, group) => sum + items[group].length, 0);
	if (count === 0) {
		return null;
	}
	return (
		<Collapsible>
			<CollapsibleTrigger render={<Button variant="ghost" size="sm" className="group -ml-2" />}>
				<ChevronRightIcon
					size={16}
					className="transition-transform group-aria-expanded:rotate-90 motion-reduce:transition-none"
				/>
				Waiting on others
				<span className="text-muted-foreground tabular-nums">· {count}</span>
			</CollapsibleTrigger>
			<CollapsibleContent className="space-y-4 pt-2">
				<GroupLists groups={WAITING} items={items} {...rest} />
			</CollapsibleContent>
		</Collapsible>
	);
}

function WorkList({
	items,
	providerType,
	login,
	empty,
}: {
	items: WorkItem[];
	providerType: ProviderType;
	login: string;
	/** What an empty list says in one line; a group with nothing in it is not drawn at all. */
	empty?: string;
}) {
	if (items.length === 0) {
		return <p className="text-sm text-muted-foreground">{empty}</p>;
	}
	return (
		<ul className="overflow-hidden rounded-xl border bg-card">
			{items.map((work) => (
				<WorkItemRow key={work.id} work={work} providerType={providerType} login={login} />
			))}
		</ul>
	);
}

/** A group's shape while open work loads: its header and a bordered list of rows. */
function GroupSkeleton({ rows }: { rows: number }) {
	return (
		<div aria-busy="true" className="space-y-2">
			<span className="sr-only">Loading open work</span>
			<div aria-hidden className="space-y-2">
				<Skeleton className="h-4 w-36" />
				<div className="overflow-hidden rounded-xl border bg-card">
					{Array.from({ length: rows }, (_, index) => (
						<div
							key={index}
							className="flex items-start gap-2.5 border-b px-3 py-2.5 last:border-b-0"
						>
							<Skeleton className="size-4 rounded-full" />
							<div className="flex-1 space-y-2">
								<Skeleton className="h-4 w-3/4" />
								<Skeleton className="h-3 w-1/3" />
							</div>
							<Skeleton className="size-6 rounded-full" />
						</div>
					))}
				</div>
			</div>
		</div>
	);
}
