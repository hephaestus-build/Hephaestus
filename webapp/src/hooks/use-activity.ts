import { skipToken, useInfiniteQuery, useQuery, useQueryClient } from "@tanstack/react-query";

import {
	getActivityPeopleOptions,
	getActivityPersonOptions,
	getActivityPersonWorkInfiniteOptions,
	getActivityPersonWorkOptions,
	getActivityWorkInfiniteOptions,
	getActivityWorkOptions,
	getOpenWorkOptions,
} from "@/api/@tanstack/react-query.gen";
import type { ActivityPeople, ActivityWork } from "@/api/types.gen";
import type { ActivityOverviewState } from "@/components/activity/activity-buckets";
import type { ActivityKind } from "@/components/activity/activity-kind-defs";
import {
	type ActivityPeriod,
	periodDays,
	periodQuery,
	previousPeriod,
} from "@/components/activity/activity-period";
import { overviewOf, tallyOf } from "@/components/activity/activity-tally";
import type { ActivityPeopleState } from "@/components/activity/ActivityPeopleTable";
import type { ActivityWorkLogState } from "@/components/activity/ActivityWorkLog";
import type { OpenWorkState } from "@/components/activity/OpenWorkSections";
import { workLogMarkdown } from "@/components/activity/work-log-markdown";
import type { ActivityFacets } from "@/components/activity/WorkspaceActivityPage";
import { panelState } from "@/components/common/panel-state";
import { copyRichText } from "@/lib/clipboard";
import type { ProviderType } from "@/lib/provider/provider-terms";
import { infiniteListState } from "@/runtime/tanstack-query/infinite-list";
import { keepSameSubject } from "@/runtime/tanstack-query/keep-same-subject";
import { loadedPages } from "@/runtime/tanstack-query/spring-page";

/** What activity counts: the period, and — on Workspace activity — a team and repositories. */
export interface ActivityScope {
	workspaceSlug: string;
	period: ActivityPeriod;
	/** The team's slug; without one, everyone. */
	team?: string;
	/** Repositories by full path; without any, every repository. */
	repo?: readonly string[];
}

/** Off while whose activity it is, is not known yet, or while the level that shows it is closed. */
interface ActivityRequest extends ActivityScope {
	enabled?: boolean;
}

/** The page size the timeline reads a page at a time; the server's own default. */
const WORK_PAGE_SIZE = 30;

/** The largest page the server serves, for reading the rest of a timeline at once to copy it. */
const COPY_PAGE_SIZE = 100;

function scopeQuery({ period, team, repo }: ActivityScope) {
	return { ...periodQuery(period), team, repo: repo && repo.length > 0 ? [...repo] : undefined };
}

/**
 * Everyone who contributed in the scope, in one response the table sorts in the browser. While
 * another period loads, the previous period's people stand in, marked stale.
 */
export function useActivityPeople({
	enabled = true,
	...scope
}: ActivityRequest): ActivityPeopleState {
	const { workspaceSlug, team, repo } = scope;
	const query = useQuery({
		...getActivityPeopleOptions({ path: { workspaceSlug }, query: scopeQuery(scope) }),
		placeholderData: keepSameSubject({ workspaceSlug, team, repo }),
		enabled,
	});
	return panelState(query, (people) => ({
		status: "ready" as const,
		people,
		stale: query.isPlaceholderData,
	}));
}

/**
 * The teams and repositories the scope picks from, which no scope changes. While another scope
 * loads, the previous one's stand in, so a picker keeps its options. It shares the people query's
 * cache entry; a cold timeline also reads it to obtain the filter options.
 */
export function useActivityFacets({
	enabled = true,
	...scope
}: ActivityRequest): ActivityFacets | undefined {
	const { workspaceSlug } = scope;
	const query = useQuery({
		...getActivityPeopleOptions({ path: { workspaceSlug }, query: scopeQuery(scope) }),
		placeholderData: (previous, previousQuery) =>
			previousQuery?.queryKey[0].path.workspaceSlug === workspaceSlug ? previous : undefined,
		select: ({ teams, repositories }: ActivityPeople) => ({ teams, repositories }),
		enabled: (cached) => enabled || cached.state.data === undefined,
	});
	return query.data;
}

/**
 * One person's activity in the scope, by week, kind and repository, and — once it is in — the
 * period of the same length before it, which the figures are set against. The earlier period never
 * holds up the first: while it loads or if it fails, the figures go without a comparison.
 */
export function useActivityPerson({
	userId,
	enabled = true,
	...scope
}: ActivityRequest & { userId: number | undefined }): ActivityOverviewState {
	const { workspaceSlug, team, repo, period } = scope;
	const path = { workspaceSlug, userId: userId ?? 0 };
	const query = useQuery({
		...getActivityPersonOptions({ path, query: scopeQuery(scope) }),
		placeholderData: keepSameSubject({ workspaceSlug, userId, team, repo }),
		enabled: enabled && userId !== undefined,
	});
	const current = query.isPlaceholderData ? undefined : query.data;
	const before = current && previousPeriod(period, current);
	const earlier = useQuery({
		...getActivityPersonOptions({ path, query: { ...scopeQuery(scope), ...before?.query } }),
		enabled: enabled && userId !== undefined && before !== undefined,
	});
	return panelState(query, (data) =>
		query.isPlaceholderData
			? { status: "ready" as const, overview: overviewOf(data), stale: true as const }
			: {
					status: "ready" as const,
					overview: overviewOf(data),
					stale: false as const,
					span: { from: data.from, to: data.to },
					previous:
						before && earlier.data
							? { tally: tallyOf(earlier.data.counts, earlier.data.breakdown), name: before.name }
							: undefined,
				},
	);
}

/** Open work is now, not a period, so it reads nothing until it knows whose it is. */
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

interface ActivityWorkRequest extends ActivityRequest {
	/** One person's timeline; without one, everyone's in the scope. */
	userId?: number;
	kinds?: readonly ActivityKind[];
	/** How a copy of the timeline reads: its heading's words before the period, and its provider. */
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
 * it: what is loaded, then the rest of the period read at the server's largest page size, as
 * Markdown and HTML.
 */
export function useActivityWork({
	userId,
	kinds,
	copy,
	enabled = true,
	...scope
}: ActivityWorkRequest): ActivityWorkLogState {
	const { workspaceSlug, team, repo, period } = scope;
	const queryClient = useQueryClient();
	const filter = { ...scopeQuery(scope), kinds: kinds && [...kinds] };
	const workspaceQuery = useInfiniteQuery({
		...getActivityWorkInfiniteOptions({
			path: { workspaceSlug },
			query: { ...filter, size: WORK_PAGE_SIZE },
		}),
		initialPageParam: { path: { workspaceSlug }, query: { cursor: undefined } },
		getNextPageParam: (last) => last.nextCursor ?? undefined,
		placeholderData: keepSameSubject({ workspaceSlug, team, repo, kinds }),
		enabled: enabled && userId === undefined,
	});
	const personPath = { workspaceSlug, userId: userId ?? 0 };
	const personQuery = useInfiniteQuery({
		...getActivityPersonWorkInfiniteOptions({
			path: personPath,
			query: { ...filter, size: WORK_PAGE_SIZE },
		}),
		initialPageParam: { path: personPath, query: { cursor: undefined } },
		getNextPageParam: (last) => last.nextCursor ?? undefined,
		placeholderData: keepSameSubject({ workspaceSlug, userId, team, repo, kinds }),
		enabled: enabled && userId !== undefined,
	});
	const query = userId === undefined ? workspaceQuery : personQuery;

	const everything = async (): Promise<ActivityWork[]> => {
		// A placeholder is the previous period's timeline, so a copy of this period starts over.
		const pages = query.isPlaceholderData ? [] : loadedPages(query.data);
		const items = pages.flatMap((page) => page.content);
		// `null` asks for the first page; `undefined` is past the last one.
		let cursor: string | null | undefined = pages.length === 0 ? null : pages.at(-1)?.nextCursor;
		while (cursor !== undefined) {
			const request = { ...filter, cursor: cursor ?? undefined, size: COPY_PAGE_SIZE };
			const page =
				userId === undefined
					? await queryClient.query(
							getActivityWorkOptions({ path: { workspaceSlug }, query: request }),
						)
					: await queryClient.query(
							getActivityPersonWorkOptions({ path: personPath, query: request }),
						);
			items.push(...page.content);
			cursor = page.nextCursor;
		}
		return items;
	};

	// The server carries the period's end in its cursor, so the pages a copy walks end where the
	// first one did; the heading names the days up to the press.
	const onCopy = async () =>
		copyRichText(
			everything().then((items) =>
				workLogMarkdown(items, {
					title: `${copy.title}, ${periodDays(period, new Date())}`,
					provider: copy.providerType,
					people: copy.people,
				}),
			),
			({ count }) => `Copied ${count} ${count === 1 ? "item" : "items"} as Markdown`,
		);

	// A disabled query still hands back what the cache holds under its key — for a person not known
	// yet, that is everyone's timeline, which must never pass for theirs.
	if (!enabled) {
		return { status: "loading" };
	}
	return infiniteListState(query, (pages) => ({
		items: pages.flatMap((page) => page.content),
		stale: query.isPlaceholderData,
		onCopy,
	}));
}
