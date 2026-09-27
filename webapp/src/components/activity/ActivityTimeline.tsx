import { isSameDay, startOfDay, subDays } from "date-fns";
import { ActivityIcon } from "lucide-react";
import { type ReactNode, useId } from "react";

import type { ActivityItem } from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import { MetaRow } from "@/components/common/MetaRow";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { SectionLabel } from "@/components/common/SectionLabel";
import { useNow } from "@/components/common/use-now";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
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

import { ACTIVITY_KIND_DEFS, goneWork } from "./activity-kind-defs";
import { memberLevel } from "./activity-search";
import { ActivityEmpty } from "./ActivityEmpty";
import { MemberAvatar } from "./MemberAvatar";

export type ActivityTimelineState = PanelState<{ items: ActivityItem[] } & MorePages>;

export interface ActivityTimelineProps {
	state: ActivityTimelineState;
	providerType: ProviderType;
	/**
	 * Whose activity: one person's, where an entry names nobody, or several people's, where each entry
	 * names whom it is about and opens them.
	 */
	people: "one" | "several";
	/** What an empty timeline says, in the scope's own words. */
	empty: { title: string; description: string };
}

const SKELETON_ROWS = 5;

/**
 * What happened, newest first and grouped by day. Each entry leads with the work and opens it on the
 * provider, where the review or comment itself lives.
 */
export function ActivityTimeline({ state, providerType, people, empty }: ActivityTimelineProps) {
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
				<div className="space-y-3" aria-hidden>
					<Skeleton className="h-4 w-24" />
					{Array.from({ length: SKELETON_ROWS }, (_, index) => (
						<div key={index} className="flex items-center gap-3">
							<Skeleton className="size-6 rounded-full" />
							<Skeleton className="h-4 flex-1" />
						</div>
					))}
				</div>
			</div>
		);
	}
	if (state.items.length === 0) {
		return <ActivityEmpty icon={<ActivityIcon />} {...empty} />;
	}
	const today = new Date(nowMs);
	const loadMoreFailed = state.loadMoreError !== undefined;
	return (
		<div className="space-y-6">
			{groupByDay(state.items).map(({ day, items }) => (
				<DayGroup key={day.toISOString()} label={dayLabel(day, today)}>
					{items.map((item) => (
						<TimelineEntry key={item.id} item={item} providerType={providerType} people={people} />
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
							Couldn’t load older activity.
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
	return failed ? "Try again" : "Show older activity";
}

/** One day's entries, named by the day as a list label rather than a heading, whatever level holds it. */
function DayGroup({ label, children }: { label: string; children: ReactNode }) {
	const labelId = useId();
	return (
		<div className="flex flex-col gap-1.5">
			<SectionLabel id={labelId}>{label}</SectionLabel>
			<ol aria-labelledby={labelId} className="rounded-xl border bg-card">
				{children}
			</ol>
		</div>
	);
}

function TimelineEntry({
	item,
	providerType,
	people,
}: {
	item: ActivityItem;
	providerType: ProviderType;
	people: ActivityTimelineProps["people"];
}) {
	const def = ACTIVITY_KIND_DEFS[item.kind];
	const Icon = def.icon;
	const { work } = item;
	const person =
		people === "several" ? (
			<InlineLink render={<DetailStackLink entry={memberLevel(item.actor.login)} />}>
				{item.actor.name}
			</InlineLink>
		) : undefined;
	const captions: ReactNode[] =
		def.credit === "actor"
			? [
					<span key="what">
						{def.label}
						{person && <> by {person}</>}
					</span>,
				]
			: [def.label, person && <span key="author">opened by {person}</span>];
	if (work) {
		captions.push(workReference(providerType, work));
	}
	return (
		<Item render={<li />} variant="row" className="items-start">
			<ItemMedia className="text-muted-foreground">
				{people === "several" ? (
					<MemberAvatar user={item.actor} size="sm" />
				) : (
					<Icon className="size-4" aria-hidden />
				)}
			</ItemMedia>
			<ItemContent className="min-w-0">
				<ItemTitle className="w-full min-w-0 font-medium">
					{work ? (
						<InlineLink
							href={item.htmlUrl ?? work.htmlUrl}
							external
							className="min-w-0 break-words"
						>
							{work.title}
						</InlineLink>
					) : (
						<span className="text-muted-foreground italic">
							{goneWork(item.kind, providerType)}
						</span>
					)}
				</ItemTitle>
				<ItemDescription className="line-clamp-none text-xs">
					<MetaRow captions={captions} />
				</ItemDescription>
			</ItemContent>
			<ItemActions>
				<time
					dateTime={item.occurredAt.toISOString()}
					className="text-xs text-muted-foreground tabular-nums"
				>
					{formatTime(item.occurredAt)}
				</time>
			</ItemActions>
		</Item>
	);
}

function groupByDay(items: ActivityItem[]): { day: Date; items: ActivityItem[] }[] {
	const groups: { day: Date; items: ActivityItem[] }[] = [];
	for (const item of items) {
		const last = groups.at(-1);
		if (last && isSameDay(last.day, item.occurredAt)) {
			last.items.push(item);
		} else {
			groups.push({ day: startOfDay(item.occurredAt), items: [item] });
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
