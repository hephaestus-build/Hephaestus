import { skipToken, useInfiniteQuery, useQuery, useQueryClient } from "@tanstack/react-query";
import { subDays } from "date-fns";

import {
	getActivitySummaryOptions,
	getActivityWorkInfiniteOptions,
	getActivityWorkOptions,
	getOpenWorkOptions,
	listMemberActivityOptions,
} from "@/api/@tanstack/react-query.gen";
import type { ActivityWork } from "@/api/types.gen";
import type { ActivityOverviewState } from "@/components/activity/activity-buckets";
import type { ActivityKind } from "@/components/activity/activity-kind-defs";
import { ACTIVITY_RANGE_DEFS, type ActivityRange } from "@/components/activity/activity-range";
import type { ActivityWorkLogState } from "@/components/activity/ActivityWorkLog";
import type { MemberActivityState } from "@/components/activity/MemberActivityTable";
import type { OpenWorkState } from "@/components/activity/OpenWorkSections";
import { workLogMarkdown } from "@/components/activity/work-log-markdown";
import { panelState } from "@/components/common/panel-state";
import { copyRichText } from "@/lib/clipboard";
import { formatDayRange } from "@/lib/dates";
import type { ProviderType } from "@/lib/provider/provider-terms";
import { infiniteListState } from "@/runtime/tanstack-query/infinite-list";
import { loadedPages } from "@/runtime/tanstack-query/spring-page";

/** Whose activity: one member by login, one team, or — with neither — everyone in the workspace. */
interface ActivityScopeRequest {
	workspaceSlug: string;
	login?: string;
	teamId?: number;
	/**
	 * Local midnight of the range's first day; the range runs to whenever the server reads it. Part
	 * of every query key, so a new day is a new read, and a refetch on focus reads up to the moment.
	 */
	from: Date;
	/** Off while the scope is not known yet, or while the level that shows it is closed. */
	enabled?: boolean;
}

/** The page size the timeline reads a page at a time; the server's own default. */
const WORK_PAGE_SIZE = 30;

/** The largest page the server serves, for reading the rest of a timeline at once to copy it. */
const COPY_PAGE_SIZE = 100;

/** The part of a query key that says whose activity, and of which kinds, a read is. */
interface SubjectKey {
	queryKey: readonly [
		{
			path?: { workspaceSlug?: string };
			query?: { login?: string; teamId?: number; kinds?: readonly string[] };
		},
	];
}

interface Subject {
	workspaceSlug: string;
	login?: string;
	teamId?: number;
	kinds?: readonly string[];
}

/**
 * While another range loads, what is on screen stays — marked stale — rather than blanking the
 * page. Only the same subject's figures may stand in: another workspace's, team's, member's or
 * category's activity never does, and the region shows its skeleton instead.
 */
function keepSameSubject({ workspaceSlug, login, teamId, kinds = [] }: Subject) {
	return <TData>(previous: TData | undefined, previousQuery: SubjectKey | undefined) => {
		const key = previousQuery?.queryKey[0];
		const same =
			key?.path?.workspaceSlug === workspaceSlug &&
			key.query?.login === login &&
			key.query?.teamId === teamId &&
			(key.query?.kinds ?? []).join(",") === kinds.join(",");
		return same ? previous : undefined;
	};
}

/** The browser's IANA time zone, whose midnights start the overview's days, weeks and months. */
function browserTimeZone(): string {
	return Intl.DateTimeFormat().resolvedOptions().timeZone;
}

/**
 * What the range adds up to, in total and per day, week or month in the reader's time zone — and
 * the period of the same length before it, for the figures set against it. The earlier period is
 * a second read that never holds up the first: while it loads or if it fails, the figures simply
 * go without a comparison.
 */
export function useActivityOverview({
	workspaceSlug,
	login,
	teamId,
	from,
	range,
	enabled = true,
}: ActivityScopeRequest & { range: ActivityRange }): ActivityOverviewState {
	const query = useQuery({
		...getActivitySummaryOptions({
			path: { workspaceSlug },
			query: { login, teamId, from, zone: browserTimeZone() },
		}),
		placeholderData: keepSameSubject({ workspaceSlug, login, teamId }),
		enabled,
	});
	const { days, previous: periodName } = ACTIVITY_RANGE_DEFS[range];
	const earlier = useQuery({
		...getActivitySummaryOptions({
			path: { workspaceSlug },
			query: { login, teamId, from: subDays(from, days), to: from, zone: browserTimeZone() },
		}),
		enabled,
	});
	const previous =
		earlier.data === undefined ? undefined : { summary: earlier.data.summary, name: periodName };
	// Placeholder data is another range's, read for a span this hook no longer knows, so it goes
	// unlabelled rather than labelled with the new range's days.
	return panelState(query, (overview) =>
		query.isPlaceholderData
			? { status: "ready" as const, overview, stale: true as const }
			: {
					status: "ready" as const,
					overview,
					stale: false as const,
					span: { from, to: new Date(query.dataUpdatedAt) },
					previous,
				},
	);
}

export function useMemberActivity({
	workspaceSlug,
	teamId,
	from,
	enabled = true,
}: Omit<ActivityScopeRequest, "login">): MemberActivityState {
	const query = useQuery({
		...listMemberActivityOptions({ path: { workspaceSlug }, query: { teamId, from } }),
		placeholderData: keepSameSubject({ workspaceSlug, teamId }),
		enabled,
	});
	return panelState(query, (members) => ({
		status: "ready" as const,
		members,
		stale: query.isPlaceholderData,
	}));
}

/** Open work is now, not a range, so it reads nothing until it knows whose it is. */
export function useOpenWork({
	workspaceSlug,
	login,
}: {
	workspaceSlug: string;
	login: string | undefined;
}): OpenWorkState {
	const options = getOpenWorkOptions({ path: { workspaceSlug, login: login ?? "" } });
	const query = useQuery({
		...options,
		queryFn: login === undefined ? skipToken : options.queryFn,
	});
	if (login === undefined) {
		return { status: "loading" };
	}
	return panelState(query, (openWork) => ({ status: "ready" as const, openWork, login }));
}

interface ActivityWorkRequest extends ActivityScopeRequest {
	kinds?: readonly ActivityKind[];
	/** How a copy of the timeline reads: its heading's words before the range, and its provider. */
	copy: {
		/** Whose work, and of which category: "Reviews · Ada Lovelace". */
		title: string;
		providerType: ProviderType;
		/** Several people's timeline, whose copied lines say whose work each was. */
		people: boolean;
	};
}

/**
 * The timeline, one piece of work per row, newest first, a page at a time — and a copy of all of
 * it: what is loaded, then the rest of the range read at the server's largest page size, as
 * Markdown and HTML.
 */
export function useActivityWork({
	workspaceSlug,
	login,
	teamId,
	from,
	kinds,
	copy,
	enabled = true,
}: ActivityWorkRequest): ActivityWorkLogState {
	const queryClient = useQueryClient();
	const scope = { login, teamId, from, kinds: kinds ? [...kinds] : undefined };
	const query = useInfiniteQuery({
		...getActivityWorkInfiniteOptions({
			path: { workspaceSlug },
			query: { ...scope, size: WORK_PAGE_SIZE },
		}),
		// A string page parameter is sent as the cursor; the first page sends none, which the
		// generated options take only as an object of request parts.
		initialPageParam: { path: { workspaceSlug }, query: { cursor: undefined } },
		getNextPageParam: (last) => last.nextCursor ?? undefined,
		placeholderData: keepSameSubject({ workspaceSlug, login, teamId, kinds }),
		enabled,
	});

	const everything = async (): Promise<ActivityWork[]> => {
		// A placeholder is the previous range's timeline, so a copy of this range starts over.
		const pages = query.isPlaceholderData ? [] : loadedPages(query.data);
		const items = pages.flatMap((page) => page.content);
		// `null` asks for the first page; `undefined` is past the last one.
		let cursor: string | null | undefined = pages.length === 0 ? null : pages.at(-1)?.nextCursor;
		while (cursor !== undefined) {
			const page = await queryClient.query(
				getActivityWorkOptions({
					path: { workspaceSlug },
					query: { ...scope, cursor: cursor ?? undefined, size: COPY_PAGE_SIZE },
				}),
			);
			items.push(...page.content);
			cursor = page.nextCursor;
		}
		return items;
	};

	// The server carries the range's end in its cursor, so the pages a copy walks end where the
	// first one did; the heading names the days up to the press.
	const onCopy = async () =>
		copyRichText(
			everything().then((items) =>
				workLogMarkdown(items, {
					title: `${copy.title}, ${formatDayRange(from, new Date())}`,
					provider: copy.providerType,
					people: copy.people,
				}),
			),
			({ count }) => `Copied ${count} ${count === 1 ? "item" : "items"} as Markdown`,
		);

	return infiniteListState(query, (pages) => ({
		items: pages.flatMap((page) => page.content),
		stale: query.isPlaceholderData,
		onCopy,
	}));
}
