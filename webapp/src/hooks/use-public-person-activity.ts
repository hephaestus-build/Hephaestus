import { useInfiniteQuery, useQuery } from "@tanstack/react-query";

import {
	getPublicActivityPersonOptions,
	getPublicActivityPersonWorkInfiniteOptions,
} from "@/api/@tanstack/react-query.gen";
import type { ActivityKind } from "@/components/activity/activity-kind-defs";
import { type ActivityPeriod, periodQuery } from "@/components/activity/activity-period";
import type { ActivityWorkLogState } from "@/components/activity/ActivityWorkLog";
import type { PublicPersonActivityState } from "@/components/activity/public-person-activity";
import { panelState } from "@/components/common/panel-state";
import { queryErrorIsRetryable } from "@/components/common/QueryErrorAlert";
import { infiniteListState } from "@/runtime/tanstack-query/infinite-list";
import { loadedPages } from "@/runtime/tanstack-query/list-pages";
import { useQueryReadRefusal } from "@/runtime/tanstack-query/use-query-read-refusal";

interface PublicPersonScope {
	workspaceSlug: string;
	login: string | undefined;
	period: ActivityPeriod;
	repo: readonly string[];
	enabled: boolean;
}

/** Published person data never uses a member read or a previous person's cached response. */
export function usePublicPersonActivity(scope: PublicPersonScope): PublicPersonActivityState {
	const { workspaceSlug, login, period, repo, enabled } = scope;
	const options = getPublicActivityPersonOptions({
		path: { slug: workspaceSlug, login: login ?? "" },
		query: { ...periodQuery(period), repo: repo.length > 0 ? [...repo] : undefined },
	});
	const query = useQuery({ ...options, enabled: enabled && login !== undefined, gcTime: 0 });
	useQueryReadRefusal(options.queryKey, query.isError, query.error);
	if (!enabled || login === undefined) {
		return { status: "loading", seedAllowed: false };
	}
	return {
		...panelState(query, (person) => ({ status: "ready" as const, person, stale: false })),
		seedAllowed: !query.isFetched,
	};
}

/** Independent paging retains no refused public rows on a later failed refresh. */
export function usePublicPersonWork({
	kinds,
	...scope
}: PublicPersonScope & { kinds?: readonly ActivityKind[] }): ActivityWorkLogState {
	const { workspaceSlug, login, period, repo, enabled } = scope;
	const path = { slug: workspaceSlug, login: login ?? "" };
	const options = getPublicActivityPersonWorkInfiniteOptions({
		path,
		query: {
			...periodQuery(period),
			repo: repo.length > 0 ? [...repo] : undefined,
			kinds: kinds && [...kinds],
			size: 30,
		},
	});
	const query = useInfiniteQuery({
		...options,
		initialPageParam: { path, query: { cursor: undefined } },
		getNextPageParam: (last) => last.nextCursor,
		enabled: enabled && login !== undefined,
		gcTime: 0,
	});
	useQueryReadRefusal(options.queryKey, query.isError, query.error);
	if (!enabled || login === undefined) {
		return { status: "loading" };
	}
	if (query.isFetchNextPageError && queryErrorIsRetryable(query.error)) {
		return infiniteListState(query, (pages) => ({
			items: pages.flatMap((page) => page.content),
			stale: false,
		}));
	}
	return panelState(query, () => ({
		status: "ready" as const,
		items: loadedPages(query.data).flatMap((page) => page.content),
		stale: false,
		hasMore: query.hasNextPage,
		isLoadingMore: query.isFetchingNextPage,
		isRefreshing: query.isFetching && !query.isFetchingNextPage,
		onLoadMore: () => {
			void query.fetchNextPage({ cancelRefetch: false });
		},
	}));
}
