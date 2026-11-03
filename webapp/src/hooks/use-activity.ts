import { skipToken, useInfiniteQuery, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect } from "react";

import {
	getActivityFacetsOptions,
	getActivityPeopleOptions,
	getActivityPeopleQueryKey,
	getActivityPersonQueryKey,
	getActivityPersonWorkInfiniteQueryKey,
	getActivityPersonWorkQueryKey,
	getActivityWorkInfiniteQueryKey,
	getActivityWorkQueryKey,
	getOpenWorkOptions,
	getWorkspaceSyncProgressOptions,
	getOwnActivityQueryKey,
	getOwnActivityWorkInfiniteQueryKey,
	getOwnActivityWorkQueryKey,
} from "@/api/@tanstack/react-query.gen";
import {
	getActivityPerson,
	getOwnActivity,
	getActivityPersonWork,
	getOwnActivityWork,
	getActivityWork,
} from "@/api/sdk.gen";
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
import { panelState } from "@/components/common/panel-state";
import { queryErrorIsRetryable } from "@/components/common/QueryErrorAlert";
import { copyRichText } from "@/lib/clipboard";
import type { ProviderType } from "@/lib/provider/provider-terms";
import { infiniteListState } from "@/runtime/tanstack-query/infinite-list";
import { keepSameSubject } from "@/runtime/tanstack-query/keep-same-subject";
import { loadedPages } from "@/runtime/tanstack-query/list-pages";
import { useQueryReadRefusal } from "@/runtime/tanstack-query/use-query-read-refusal";

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

const firstCursor: string | undefined = undefined;

function scopeQuery({ period, team, repo }: ActivityScope) {
	return { ...periodQuery(period), team, repo: repo && repo.length > 0 ? [...repo] : undefined };
}

/**
 * Everyone who contributed in the scope, in one response the table sorts in the browser. While
 * another period loads, the previous period's people stand in, marked stale. A failed refresh that
 * can be retried keeps this scope's people, with the failure beside them; a refused one does not.
 */
export function useActivityPeople({
	enabled = true,
	...scope
}: ActivityRequest): ActivityPeopleState {
	const { workspaceSlug, team, repo } = scope;
	const options = getActivityPeopleOptions({ path: { workspaceSlug }, query: scopeQuery(scope) });
	const query = useQuery({
		...options,
		placeholderData: keepSameSubject({ workspaceSlug, team, repo }),
		enabled,
	});
	useQueryReadRefusal(options.queryKey, query.isError, query.error);
	const ready = (people: ActivityPeople) => ({
		status: "ready" as const,
		people,
		stale: query.isPlaceholderData,
	});
	if (query.isRefetchError && queryErrorIsRetryable(query.error)) {
		return {
			...ready(query.data),
			refreshFailure: {
				error: query.error,
				pending: query.isFetching,
				onRetry: () => {
					void query.refetch();
				},
			},
		};
	}
	return panelState(query, ready);
}

/**
 * The teams and repositories the scope picks from, which no scope changes. While another scope
 * loads, the previous one's stand in, so a picker keeps its options. They come from the people
 * query's cache entry when it has one; otherwise, as on a cold timeline, from the facets alone, so
 * nobody is counted only to fill a picker.
 */
export function useActivityFacets({ enabled = true, ...scope }: ActivityRequest) {
	const { workspaceSlug } = scope;
	const people = useQuery({
		...getActivityPeopleOptions({ path: { workspaceSlug }, query: scopeQuery(scope) }),
		placeholderData: (previous, previousQuery) =>
			previousQuery?.queryKey[0].path.workspaceSlug === workspaceSlug ? previous : undefined,
		select: ({ teams, repositories }: ActivityPeople) => ({ teams, repositories }),
		enabled,
	});
	const facetsOptions = getActivityFacetsOptions({ path: { workspaceSlug } });
	const facets = useQuery({
		...facetsOptions,
		enabled: !enabled && people.data === undefined,
	});
	useQueryReadRefusal(facetsOptions.queryKey, facets.isError, facets.error);
	const query = enabled || people.data !== undefined ? people : facets;
	// A failed refresh that can be retried keeps the options beside its failure; a refused one does
	// not, even before its cache entry is evicted.
	const readable = !query.isError || (query.isRefetchError && queryErrorIsRetryable(query.error));
	return {
		data: readable ? query.data : undefined,
		error:
			!enabled && query.isError
				? {
						error: query.error,
						pending: query.isFetching,
						onRetry: () => {
							void query.refetch();
						},
					}
				: undefined,
	};
}

/**
 * One person's activity in the scope, by week, kind and repository, and — once it is in — the
 * period of the same length before it, which the figures are set against. The earlier period never
 * holds up the first: while it loads or if it fails, the figures go without a comparison.
 */
export function useActivityPerson({
	userId,
	own = false,
	enabled = true,
	...scope
}: ActivityRequest &
	(
		| { own: true; userId?: never }
		| { own?: false; userId: number | undefined }
	)): ActivityOverviewState {
	const { workspaceSlug, team, repo, period } = scope;
	const optionsFor = (query: ReturnType<typeof scopeQuery>) => {
		if (!own && userId !== undefined) {
			const request = { path: { workspaceSlug, userId }, query };
			return {
				queryKey: getActivityPersonQueryKey(request),
				queryFn: async ({ signal }: { signal: AbortSignal }) => {
					const { data } = await getActivityPerson({ ...request, signal, throwOnError: true });
					return data;
				},
			};
		}
		const request = { path: { workspaceSlug }, query };
		return {
			queryKey: getOwnActivityQueryKey(request),
			queryFn: async ({ signal }: { signal: AbortSignal }) => {
				const { data } = await getOwnActivity({ ...request, signal, throwOnError: true });
				return data;
			},
		};
	};
	const subjectKnown = own || userId !== undefined;
	const options = optionsFor(scopeQuery(scope));
	const query = useQuery({
		...options,
		queryFn: subjectKnown ? options.queryFn : skipToken,
		enabled,
		placeholderData: keepSameSubject({ workspaceSlug, userId, team, repo }),
	});
	const current = query.isPlaceholderData ? undefined : query.data;
	const before = current && previousPeriod(period, current);
	const earlierOptions = optionsFor({ ...scopeQuery(scope), ...before?.query });
	const earlier = useQuery({
		...earlierOptions,
		queryFn: subjectKnown ? earlierOptions.queryFn : skipToken,
		enabled: enabled && before !== undefined,
	});
	if (!enabled || !subjectKnown) {
		return { status: "loading" };
	}
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

type ActivityWorkRequest = ActivityRequest &
	(
		| { own: true; workspace?: never; userId?: never }
		| { own?: false; workspace: true; userId?: never }
		| { own?: false; workspace?: false; userId: number | undefined }
	) & {
		kinds?: readonly ActivityKind[];
		/** How a copy of the timeline reads: its heading's words before the period, and its provider. */
		copy: {
			/** Whose work, and of which category: "Reviews · Ada Lovelace". */
			title: string;
			providerType: ProviderType;
			/** Several people's timeline, whose copied lines say whose work each was. */
			people: boolean;
		};
	};

/**
 * The timeline, one piece of work per row, newest first, a page at a time — and a copy of all of
 * it: what is loaded, then the rest of the period read at the server's largest page size, as
 * Markdown and HTML.
 */
const SYNC_PROGRESS_POLL_MS = 30_000;

/**
 * Whether a sync is running. A failed read says no, which is what the page said without this fact.
 * Live sync events reach administrators only, so while a sync runs, and once when it ends, this reads
 * the lists again: work appears for everyone without a reload.
 */
export function useWorkspaceSyncing(workspaceSlug: string, enabled: boolean): boolean {
	const queryClient = useQueryClient();
	const { data } = useQuery({
		...getWorkspaceSyncProgressOptions({ path: { workspaceSlug } }),
		enabled,
		refetchInterval: (query) =>
			query.state.data?.syncing === true ? SYNC_PROGRESS_POLL_MS : false,
	});
	const syncing = enabled && data?.syncing === true;
	useEffect(() => {
		if (!syncing) {
			return;
		}
		const refresh = () => {
			const path = { workspaceSlug };
			for (const queryKey of [
				getOwnActivityWorkInfiniteQueryKey({ path }),
				getActivityWorkInfiniteQueryKey({ path }),
				getActivityPeopleQueryKey({ path }),
			]) {
				void queryClient.invalidateQueries({ queryKey });
			}
		};
		const timer = setInterval(refresh, SYNC_PROGRESS_POLL_MS);
		return () => {
			clearInterval(timer);
			refresh();
		};
	}, [syncing, queryClient, workspaceSlug]);
	return syncing;
}

export function useActivityWork({
	userId,
	own = false,
	workspace = false,
	kinds,
	copy,
	enabled = true,
	...scope
}: ActivityWorkRequest): ActivityWorkLogState {
	const { workspaceSlug, team, repo, period } = scope;
	const queryClient = useQueryClient();
	const filter = { ...scopeQuery(scope), kinds: kinds && [...kinds] };
	const path = { workspaceSlug, ...(userId === undefined ? {} : { userId }) };
	const request = { path, query: { ...filter, size: WORK_PAGE_SIZE } };
	const subjectKnown = own || workspace || userId !== undefined;
	const reader = (() => {
		if (own || !subjectKnown) {
			return {
				key: (query: typeof request.query & { cursor?: string }) =>
					getOwnActivityWorkQueryKey({ path, query }),
				infiniteKey: getOwnActivityWorkInfiniteQueryKey(request),
				read: async (query: typeof request.query & { cursor?: string }, signal?: AbortSignal) =>
					getOwnActivityWork({ path, query, signal, throwOnError: true }),
			};
		}
		if (userId !== undefined) {
			const personPath = { workspaceSlug, userId };
			return {
				key: (query: typeof request.query & { cursor?: string }) =>
					getActivityPersonWorkQueryKey({ path: personPath, query }),
				infiniteKey: getActivityPersonWorkInfiniteQueryKey({ ...request, path: personPath }),
				read: async (query: typeof request.query & { cursor?: string }, signal?: AbortSignal) =>
					getActivityPersonWork({ path: personPath, query, signal, throwOnError: true }),
			};
		}
		return {
			key: (query: typeof request.query & { cursor?: string }) =>
				getActivityWorkQueryKey({ path, query }),
			infiniteKey: getActivityWorkInfiniteQueryKey(request),
			read: async (query: typeof request.query & { cursor?: string }, signal?: AbortSignal) =>
				getActivityWork({ path, query, signal, throwOnError: true }),
		};
	})();
	const query = useInfiniteQuery({
		queryKey: reader.infiniteKey,
		queryFn: subjectKnown
			? async ({ pageParam, signal }) => {
					const { data } = await reader.read({ ...request.query, cursor: pageParam }, signal);
					return data;
				}
			: skipToken,
		enabled,
		initialPageParam: firstCursor,
		getNextPageParam: (last) => last.nextCursor,
		placeholderData: keepSameSubject({
			workspaceSlug,
			userId,
			team,
			repo,
			kinds,
			operation: reader.infiniteKey[0]._id,
		}),
	});
	useQueryReadRefusal(reader.infiniteKey, query.isError, query.error);
	const syncing = useWorkspaceSyncing(
		workspaceSlug,
		query.isSuccess &&
			!query.isPlaceholderData &&
			loadedPages(query.data).every((page) => page.content.length === 0),
	);

	const everything = async (): Promise<ActivityWork[]> => {
		// A placeholder is the previous period's timeline, so a copy of this period starts over.
		const pages = query.isPlaceholderData ? [] : loadedPages(query.data);
		const items = pages.flatMap((page) => page.content);
		// `null` asks for the first page; `undefined` is past the last one.
		let cursor: string | null | undefined = pages.length === 0 ? null : pages.at(-1)?.nextCursor;
		while (cursor !== undefined) {
			const pageRequest = { ...filter, cursor: cursor ?? undefined, size: COPY_PAGE_SIZE };
			const page = await queryClient.query({
				queryKey: reader.key(pageRequest),
				retry: false,
				queryFn: async ({ signal }) => {
					const { data } = await reader.read(pageRequest, signal);
					return data;
				},
			});
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

	if (!enabled || !subjectKnown) {
		return { status: "loading" };
	}
	return infiniteListState(query, (pages) => ({
		items: pages.flatMap((page) => page.content),
		stale: query.isPlaceholderData,
		onCopy,
		syncing,
	}));
}
