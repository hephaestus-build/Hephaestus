import { HistoryIcon } from "@primer/octicons-react";
import { isSameDay, startOfDay, subDays } from "date-fns";
import { type ReactNode, useId } from "react";

import { cn } from "cn";
import type { ActivityWork } from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import { MetaRow } from "@/components/common/MetaRow";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { SectionLabel } from "@/components/common/SectionLabel";
import { useNow } from "@/components/common/use-now";
import { Button } from "@/components/ui/button";
import {
	Item,
	ItemActions,
	ItemContent,
	ItemDescription,
	ItemMedia,
	ItemTitle,
} from "@/components/ui/item";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { formatTime, formatWeekdayDay } from "@/lib/dates";
import { type ProviderType, workReference } from "@/lib/provider/provider-terms";
import type { MorePages } from "@/runtime/tanstack-query/infinite-list";

import { ActionChips } from "./ActionChip";
import { goneWork, workOf } from "./activity-kind-defs";
import { STALE } from "./activity-tones";
import { ActivityEmpty } from "./ActivityEmpty";
import { PeopleStack } from "./PeopleStack";
import { goneWorkVisual, workStateVisual } from "./work-state-defs";

/**
 * A work log once its first page is in: the work loaded so far, the paging, whether it is the
 * previous range's standing in while the range just chosen loads, and `onCopy`, which copies every
 * page of it — so no caller can offer a copy of a list that is not there.
 */
export type ActivityWorkLogState = PanelState<
	{ items: ActivityWork[]; stale: boolean; onCopy: () => Promise<void> } & MorePages
>;

/**
 * Whose work the log lists: one person's, whose rows name the author only when it is someone else,
 * or several people's, whose rows show who did it.
 */
export type WorkLogSubject = { people: "one"; login: string | undefined } | { people: "several" };

export interface ActivityWorkLogProps {
	state: ActivityWorkLogState;
	providerType: ProviderType;
	subject: WorkLogSubject;
}

const SKELETON_ROWS = 4;

const LOAD_MORE_FAILED = "Couldn't load more activity.";

/**
 * What happened, one row per pull request or issue rather than per event — forty comments on one
 * pull request are one row with "40" — newest first and grouped by the local day of the latest
 * event. Each row leads with the work and opens it on the provider.
 */
export function ActivityWorkLog({ state, providerType, subject }: ActivityWorkLogProps) {
	const nowMs = useNow();
	if (state.status === "error") {
		return (
			<QueryErrorAlert error={state.error} title="Couldn't load activity" onRetry={state.onRetry} />
		);
	}
	if (state.status === "loading") {
		return (
			<div aria-busy="true">
				<span className="sr-only">Loading activity</span>
				<div aria-hidden className="space-y-2">
					<Skeleton className="h-4 w-24" />
					<div className="overflow-hidden rounded-xl border bg-card">
						{Array.from({ length: SKELETON_ROWS }, (_, index) => (
							<div
								key={index}
								className="flex items-start gap-2.5 border-b px-3 py-2.5 last:border-b-0"
							>
								<Skeleton className="size-4 rounded-full" />
								<div className="flex-1 space-y-2">
									<Skeleton className="h-4 w-2/3" />
									<Skeleton className="h-3 w-1/4" />
								</div>
								<Skeleton className="h-4 w-20" />
							</div>
						))}
					</div>
				</div>
			</div>
		);
	}
	if (state.items.length === 0) {
		return <ActivityEmpty icon={<HistoryIcon />} title="No activity in this range" />;
	}
	const today = new Date(nowMs);
	const loadMoreFailed = state.loadMoreError !== undefined;
	return (
		<div aria-busy={state.stale || undefined} className={cn("space-y-6", state.stale && STALE)}>
			{groupByDay(state.items).map(({ day, items }) => (
				<DayGroup key={day.getTime()} label={dayLabel(day, today)}>
					{items.map((item) => (
						<WorkLogRow key={item.id} item={item} providerType={providerType} subject={subject} />
					))}
				</DayGroup>
			))}
			{state.hasMore && (
				<div className="flex flex-wrap items-center gap-x-3 gap-y-2">
					<Button
						variant="outline"
						size="sm"
						onClick={state.onLoadMore}
						disabled={state.isLoadingMore}
					>
						{state.isLoadingMore && <Spinner />}
						{loadMoreLabel(state.isLoadingMore, loadMoreFailed)}
					</Button>
					{loadMoreFailed && (
						<p role="alert" className="text-sm text-muted-foreground">
							{LOAD_MORE_FAILED}
						</p>
					)}
				</div>
			)}
		</div>
	);
}

function loadMoreLabel(isLoadingMore: boolean, failed: boolean): string {
	if (isLoadingMore) {
		return "Loading…";
	}
	return failed ? "Try again" : "Show more";
}

/** One day's rows, named by the day as a list label rather than a heading, whatever level holds it. */
function DayGroup({ label, children }: { label: string; children: ReactNode }) {
	const labelId = useId();
	return (
		<div className="flex flex-col gap-2">
			<SectionLabel id={labelId}>{label}</SectionLabel>
			<ol aria-labelledby={labelId} className="overflow-hidden rounded-xl border bg-card">
				{children}
			</ol>
		</div>
	);
}

function WorkLogRow({
	item,
	providerType,
	subject,
}: {
	item: ActivityWork;
	providerType: ProviderType;
	subject: WorkLogSubject;
}) {
	const { work } = item;
	const kinds = item.actions.map((action) => action.kind);
	const state = work
		? workStateVisual(work, providerType)
		: goneWorkVisual(workOf(kinds), providerType);
	const author =
		work?.author && (subject.people === "several" || work.author.login !== subject.login)
			? `by ${work.author.name}`
			: undefined;
	return (
		<Item render={<li />} variant="row" size="sm" className="items-start">
			<ItemMedia className={cn("mt-0.5", state.colorClass)}>
				<state.icon size={16} aria-label={state.label} />
			</ItemMedia>
			<ItemContent className="min-w-0 basis-56 gap-1">
				<ItemTitle className="w-full min-w-0 font-medium">
					{work ? (
						<InlineLink href={work.htmlUrl} external className="min-w-0 break-words">
							{work.title}
						</InlineLink>
					) : (
						<span className="text-muted-foreground">{goneWork(kinds, providerType)}</span>
					)}
				</ItemTitle>
				{work && (
					<ItemDescription className="line-clamp-none text-xs">
						<MetaRow captions={[workReference(providerType, work), author]} />
					</ItemDescription>
				)}
			</ItemContent>
			<ItemActions className="flex-wrap gap-x-4 gap-y-2">
				<ActionChips actions={item.actions} providerType={providerType} display="work" />
				{subject.people === "several" && item.people.length > 0 && (
					<PeopleStack
						people={item.people.map((user) => ({ user }))}
						providerType={providerType}
						aria-label="People"
					/>
				)}
				<time
					dateTime={item.lastOccurredAt.toISOString()}
					className="w-16 text-right text-xs text-muted-foreground tabular-nums"
				>
					{formatTime(item.lastOccurredAt)}
				</time>
			</ItemActions>
		</Item>
	);
}

function groupByDay(items: ActivityWork[]): { day: Date; items: ActivityWork[] }[] {
	const groups: { day: Date; items: ActivityWork[] }[] = [];
	for (const item of items) {
		const last = groups.at(-1);
		if (last && isSameDay(last.day, item.lastOccurredAt)) {
			last.items.push(item);
		} else {
			groups.push({ day: startOfDay(item.lastOccurredAt), items: [item] });
		}
	}
	return groups;
}

function dayLabel(day: Date, today: Date): string {
	if (isSameDay(day, today)) {
		return "Today";
	}
	if (isSameDay(day, subDays(today, 1))) {
		return "Yesterday";
	}
	return formatWeekdayDay(day, today);
}
