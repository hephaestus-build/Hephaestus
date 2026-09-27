import { keepPreviousData, skipToken, useInfiniteQuery, useQuery } from "@tanstack/react-query";

import {
	getActivitySummaryOptions,
	getActivityTimelineInfiniteOptions,
	getOpenWorkOptions,
	listMemberActivityOptions,
} from "@/api/@tanstack/react-query.gen";
import type { ActivitySummary, MemberActivity, OpenWork } from "@/api/types.gen";
import type { ActivityKind } from "@/components/activity/activity-kind-defs";
import type { ActivityTimelineState } from "@/components/activity/ActivityTimeline";
import { type PanelState, panelState } from "@/components/common/panel-state";
import { infiniteListState } from "@/runtime/tanstack-query/infinite-list";

/** Whose activity: one member by login, one team, or — with neither — everyone in the workspace. */
interface ActivityScopeRequest {
	workspaceSlug: string;
	login?: string;
	teamId?: number;
	/** The first instant of the range, which runs to now. */
	from: Date;
	/** Off while the scope is not known yet, or while the level that shows it is closed. */
	enabled?: boolean;
}

const ACTIVITY_TIMELINE_PAGE_SIZE = 20;

/** The part of a query key that says whose activity, and of which kinds, a read is. */
interface SubjectKey {
	queryKey: readonly [{ query?: { login?: string; kinds?: readonly string[] } }];
}

/**
 * While a range or team change loads, what is on screen stays rather than blanking the page; another
 * member's or another category's activity never stands in for the one asked for.
 */
function keepSameSubject(login: string | undefined, kinds: readonly string[] = []) {
	return <TData>(previous: TData | undefined, previousQuery: SubjectKey | undefined) => {
		const subject = previousQuery?.queryKey[0].query;
		return subject?.login === login && (subject?.kinds ?? []).join(",") === kinds.join(",")
			? previous
			: undefined;
	};
}

export function useActivitySummary({
	workspaceSlug,
	login,
	teamId,
	from,
	enabled = true,
}: ActivityScopeRequest): PanelState<{ summary: ActivitySummary }> {
	const query = useQuery({
		...getActivitySummaryOptions({ path: { workspaceSlug }, query: { login, teamId, from } }),
		placeholderData: keepSameSubject(login),
		enabled,
	});
	return panelState(query, (summary) => ({ status: "ready" as const, summary }));
}

export function useMemberActivity({
	workspaceSlug,
	teamId,
	from,
	enabled = true,
}: Omit<ActivityScopeRequest, "login">): PanelState<{ members: MemberActivity[] }> {
	const query = useQuery({
		...listMemberActivityOptions({ path: { workspaceSlug }, query: { teamId, from } }),
		placeholderData: keepPreviousData,
		enabled,
	});
	return panelState(query, (members) => ({ status: "ready" as const, members }));
}

/** Open work is now, not a range, so it reads nothing until it knows whose it is. */
export function useOpenWork({
	workspaceSlug,
	login,
}: {
	workspaceSlug: string;
	login: string | undefined;
}): PanelState<{ openWork: OpenWork }> {
	const options = getOpenWorkOptions({ path: { workspaceSlug, login: login ?? "" } });
	const query = useQuery({
		...options,
		queryFn: login === undefined ? skipToken : options.queryFn,
	});
	return panelState(query, (openWork) => ({ status: "ready" as const, openWork }));
}

/** Activity newest first, a page at a time. */
export function useActivityTimeline({
	workspaceSlug,
	login,
	teamId,
	from,
	kinds,
	enabled = true,
}: ActivityScopeRequest & { kinds?: readonly ActivityKind[] }): ActivityTimelineState {
	const query = useInfiniteQuery({
		...getActivityTimelineInfiniteOptions({
			path: { workspaceSlug },
			query: {
				login,
				teamId,
				from,
				kinds: kinds ? [...kinds] : undefined,
				size: ACTIVITY_TIMELINE_PAGE_SIZE,
			},
		}),
		// A string page parameter is sent as the cursor; the first page sends none, which the
		// generated options take only as an object of request parts.
		initialPageParam: { path: { workspaceSlug }, query: { cursor: undefined } },
		getNextPageParam: (last) => last.nextCursor ?? undefined,
		placeholderData: keepSameSubject(login, kinds),
		enabled,
	});
	return infiniteListState(query, (pages) => ({ items: pages.flatMap((page) => page.content) }));
}
