import { isSameDay, startOfDay, subDays } from "date-fns";
import { type ReactNode, useId } from "react";

import { cn } from "cn";
import type { ActivityWork, RepositoryInfo, UserInfo, WorkItem } from "@/api/types.gen";
import { InfiniteListEnd, LOADING_MORE_ROWS } from "@/components/common/InfiniteListEnd";
import { InlineLink } from "@/components/common/InlineLink";
import { MetaRow } from "@/components/common/MetaRow";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { SectionLabel } from "@/components/common/SectionLabel";
import { useNow } from "@/components/common/use-now";
import {
	Item,
	ItemActions,
	ItemContent,
	ItemDescription,
	ItemMedia,
	ItemTitle,
} from "@/components/ui/item";
import { Skeleton } from "@/components/ui/skeleton";
import { formatTime, formatWeekdayDay } from "@/lib/dates";
import { type ProviderType, workReference } from "@/lib/provider/provider-terms";
import type { MorePages } from "@/runtime/tanstack-query/infinite-list";

import { ActionChips } from "./ActionChip";
import { goneWork, workOf } from "./activity-kind-defs";
import { STALE } from "./activity-tones";
import { ActivityEmpty, HistoryMark } from "./ActivityEmpty";
import { PeopleStack } from "./PeopleStack";
import { goneWorkVisual, workStateVisual } from "./work-state-defs";

/** The work facts a timeline renders; public reads need no account or review metadata. */
export type ActivityWorkLogItem = Pick<ActivityWork, "id" | "actions" | "lastOccurredAt"> & {
	work?: Pick<WorkItem, "type" | "state" | "isDraft" | "number" | "title" | "htmlUrl"> & {
		repository?: Pick<RepositoryInfo, "name">;
		author?: Pick<UserInfo, "login" | "name">;
	};
	people?: UserInfo[];
};

/**
 * A work log once its first page is in: the work loaded so far, the paging, whether it is the
 * previous range's standing in while the range just chosen loads. A supplied `onCopy` copies every
 * page; without that capability, the region offers no copy action.
 */
export type ActivityWorkLogState = PanelState<
	{
		items: ActivityWorkLogItem[];
		stale: boolean;
		onCopy?: () => Promise<void>;
		/** A sync is running, so an empty list may not stay empty. */
		syncing?: boolean;
	} & MorePages
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

/**
 * What happened, one row per pull request or issue rather than per event — forty comments on one
 * pull request are one row with "40" — newest first and grouped by the local day of the latest
 * event. Each row leads with the work and opens it on the provider.
 */
export function ActivityWorkLog({ state, providerType, subject }: ActivityWorkLogProps) {
	const nowMs = useNow();
	if (state.status === "error") {
		return (
			<QueryErrorAlert
				error={state.error}
				title="We could not load activity"
				onRetry={state.onRetry}
			/>
		);
	}
	if (state.status === "loading") {
		return (
			<div aria-busy="true">
				<span className="sr-only">Loading activity…</span>
				<div aria-hidden className="space-y-2">
					<Skeleton className="h-4 w-24" />
					<RowsSkeleton rows={SKELETON_ROWS} />
				</div>
			</div>
		);
	}
	// The work already read stays, but a reader must know it may be out of date.
	const refreshAlert =
		state.refreshFailure === undefined ? undefined : (
			<QueryErrorAlert
				error={state.refreshFailure.error}
				title="We could not refresh activity"
				pending={state.refreshFailure.pending}
				onRetry={state.refreshFailure.onRetry}
			/>
		);
	if (state.items.length === 0) {
		return (
			<div className="space-y-6">
				{refreshAlert}
				<ActivityEmpty
					icon={<HistoryMark providerType={providerType} />}
					title="No activity in this range"
					syncing={state.syncing}
				/>
			</div>
		);
	}
	const today = new Date(nowMs);
	return (
		<div
			aria-busy={state.stale || state.isLoadingMore || undefined}
			className={cn("space-y-6", state.stale && STALE)}
		>
			{refreshAlert}
			{groupByDay(state.items).map(({ day, items }) => (
				<DayGroup key={day.getTime()} label={dayLabel(day, today)}>
					{items.map((item) => (
						<WorkLogRow key={item.id} item={item} providerType={providerType} subject={subject} />
					))}
				</DayGroup>
			))}
			{/* An earlier page starts at an earlier day, so its placeholder is a day of rows. */}
			{state.isLoadingMore && <DayGroupSkeleton rows={LOADING_MORE_ROWS} />}
			<InfiniteListEnd
				hasMore={state.hasMore}
				isLoadingMore={state.isLoadingMore}
				isRefreshing={state.isRefreshing}
				loadMoreError={state.loadMoreError}
				onLoadMore={state.onLoadMore}
				moreLabel="Show earlier activity"
				failedLabel="We could not load earlier activity."
			/>
		</div>
	);
}

/** A day's label over its rows, in the shape of the log's own, while an earlier page loads. */
function DayGroupSkeleton({ rows }: { rows: number }) {
	return (
		<div aria-hidden data-loading-row className="flex flex-col gap-2">
			<Skeleton className="h-4 w-24" />
			<RowsSkeleton rows={rows} />
		</div>
	);
}

/** Rows in the shape of the log's own, for its first page and for each page after. */
function RowsSkeleton({ rows }: { rows: number }) {
	return (
		<div className="overflow-hidden rounded-xl border bg-card">
			{Array.from({ length: rows }, (_, index) => (
				<div key={index} className="flex items-start gap-2.5 border-b px-3 py-2.5 last:border-b-0">
					<Skeleton className="size-4 rounded-full" />
					<div className="flex-1 space-y-2">
						<Skeleton className="h-4 w-2/3" />
						<Skeleton className="h-3 w-1/4" />
					</div>
					<Skeleton className="h-4 w-20" />
				</div>
			))}
		</div>
	);
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
	item: ActivityWorkLogItem;
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
				{subject.people === "several" && item.people !== undefined && item.people.length > 0 && (
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

function groupByDay(items: ActivityWorkLogItem[]): { day: Date; items: ActivityWorkLogItem[] }[] {
	const groups: { day: Date; items: ActivityWorkLogItem[] }[] = [];
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
