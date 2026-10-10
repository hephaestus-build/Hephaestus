import { skipToken, useInfiniteQuery, useQuery, useQueryClient } from "@tanstack/react-query";
import { subDays } from "date-fns";

import {
	getActivityPeopleOptions,
	getActivityPersonOptions,
	getActivityPersonWorkInfiniteOptions,
	getActivityPersonWorkOptions,
	getAllTeamsOptions,
	getActivityWorkInfiniteOptions,
	getActivityWorkOptions,
	getOpenWorkOptions,
} from "@/api/@tanstack/react-query.gen";
import type { ActivityWork } from "@/api/types.gen";
import type { ActivityOverviewState } from "@/components/activity/activity-buckets";
import type { ActivityKind } from "@/components/activity/activity-kind-defs";
import { ACTIVITY_RANGE_DEFS, type ActivityRange } from "@/components/activity/activity-range";
import {
	overviewFromPeople,
	overviewFromPerson,
	summaryFromCounts,
} from "@/components/activity/activity-view";
import type { ActivityWorkLogState } from "@/components/activity/ActivityWorkLog";
import type { MemberActivityState } from "@/components/activity/MemberActivityTable";
import type { OpenWorkState } from "@/components/activity/OpenWorkSections";
import { workLogMarkdown } from "@/components/activity/work-log-markdown";
import { panelState, queryLoadState } from "@/components/common/panel-state";
import { copyRichText } from "@/lib/clipboard";
import { formatDayRange } from "@/lib/dates";
import type { ProviderType } from "@/lib/provider/provider-terms";
import { infiniteListState } from "@/runtime/tanstack-query/infinite-list";
import { keepSameSubject } from "@/runtime/tanstack-query/keep-same-subject";
import { loadedPages } from "@/runtime/tanstack-query/spring-page";

/** Whose activity: one member by login, one team, or — with neither — everyone in the workspace. */
interface ActivityScopeRequest {
	workspaceSlug: string;
	login?: string;
	userId?: number;
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

/**
 * What the range adds up to, in total and per UTC week — and
 * the period of the same length before it, for the figures set against it. The earlier period is
 * a second read that never holds up the first: while it loads or if it fails, the figures simply
 * go without a comparison.
 */
export function useActivityOverview({
	workspaceSlug,
	userId,
	teamId,
	from,
	range,
	enabled = true,
}: ActivityScopeRequest & { range: ActivityRange }): ActivityOverviewState {
	const metadata = useQuery({
		...getAllTeamsOptions({ path: { workspaceSlug } }),
		enabled: enabled && teamId !== undefined,
	});
	const team = metadata.data?.find((candidate) => candidate.id === teamId)?.slug;
	const scopedEnabled = enabled && (teamId === undefined || team !== undefined);
	const person = useQuery({
		...getActivityPersonOptions({
			path: { workspaceSlug, userId: userId ?? 0 },
			query: { from, team },
		}),
		enabled: scopedEnabled && userId !== undefined,
	});
	const people = useQuery({
		...getActivityPeopleOptions({ path: { workspaceSlug }, query: { from, team } }),
		enabled: scopedEnabled && userId === undefined,
	});
	const { days, previous: periodName } = ACTIVITY_RANGE_DEFS[range];
	const earlierPerson = useQuery({
		...getActivityPersonOptions({
			path: { workspaceSlug, userId: userId ?? 0 },
			query: { team, from: subDays(from, days), to: from },
		}),
		enabled: scopedEnabled && userId !== undefined,
	});
	const earlierPeople = useQuery({
		...getActivityPeopleOptions({
			path: { workspaceSlug },
			query: { team, from: subDays(from, days), to: from },
		}),
		enabled: scopedEnabled && userId === undefined,
	});
	const previousData =
		userId === undefined
			? earlierPeople.data && overviewFromPeople(earlierPeople.data)
			: earlierPerson.data && overviewFromPerson(earlierPerson.data);
	const previous =
		previousData === undefined ? undefined : { summary: previousData.summary, name: periodName };
	const metadataState = queryLoadState(metadata);
	if (teamId !== undefined && team === undefined && metadataState.status === "error") {
		return metadataState;
	}
	if (userId !== undefined) {
		return panelState(person, (data) => ({
			status: "ready" as const,
			overview: overviewFromPerson(data),
			stale: false as const,
			span: { from: data.from, to: data.to },
			previous,
		}));
	}
	return panelState(people, (data) => ({
		status: "ready" as const,
		overview: overviewFromPeople(data),
		stale: false as const,
		span: { from: data.from, to: data.to },
		previous,
	}));
}

export function useMemberActivity({
	workspaceSlug,
	teamId,
	from,
	enabled = true,
}: Omit<ActivityScopeRequest, "login">): MemberActivityState {
	const metadata = useQuery({
		...getAllTeamsOptions({ path: { workspaceSlug } }),
		enabled: enabled && teamId !== undefined,
	});
	const resolvedTeam = metadata.data?.find((team) => team.id === teamId)?.slug;
	const query = useQuery({
		...getActivityPeopleOptions({
			path: { workspaceSlug },
			query: { team: resolvedTeam, from },
		}),
		placeholderData: keepSameSubject({ workspaceSlug, team: resolvedTeam }),
		enabled: enabled && (teamId === undefined || resolvedTeam !== undefined),
	});
	const metadataState = queryLoadState(metadata);
	if (teamId !== undefined && resolvedTeam === undefined && metadataState.status === "error") {
		return metadataState;
	}
	return panelState(query, (data) => ({
		status: "ready" as const,
		members: data.people.map((person) => ({
			user: person.person,
			summary: summaryFromCounts(person.counts),
		})),
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
	userId,
	teamId,
	from,
	kinds,
	copy,
	enabled = true,
}: ActivityWorkRequest): ActivityWorkLogState {
	const queryClient = useQueryClient();
	const metadata = useQuery({
		...getAllTeamsOptions({ path: { workspaceSlug } }),
		enabled: enabled && teamId !== undefined,
	});
	const resolvedTeam = metadata.data?.find((team) => team.id === teamId)?.slug;
	const scope = { team: resolvedTeam, from, kinds: kinds ? [...kinds] : undefined };
	const scopedEnabled = enabled && (teamId === undefined || resolvedTeam !== undefined);
	const workspaceQuery = useInfiniteQuery({
		...getActivityWorkInfiniteOptions({
			path: { workspaceSlug },
			query: { login, ...scope, size: WORK_PAGE_SIZE },
		}),
		initialPageParam: { path: { workspaceSlug }, query: { cursor: undefined } },
		getNextPageParam: (last) => last.nextCursor ?? undefined,
		placeholderData: keepSameSubject({ workspaceSlug, login, team: resolvedTeam, kinds }),
		enabled: scopedEnabled && userId === undefined,
	});
	const personQuery = useInfiniteQuery({
		...getActivityPersonWorkInfiniteOptions({
			path: { workspaceSlug, userId: userId ?? 0 },
			query: { ...scope, size: WORK_PAGE_SIZE },
		}),
		initialPageParam: {
			path: { workspaceSlug, userId: userId ?? 0 },
			query: { cursor: undefined },
		},
		getNextPageParam: (last) => last.nextCursor ?? undefined,
		enabled: scopedEnabled && userId !== undefined,
	});
	const query = userId === undefined ? workspaceQuery : personQuery;

	const everything = async (): Promise<ActivityWork[]> => {
		// A placeholder is the previous range's timeline, so a copy of this range starts over.
		const pages = query.isPlaceholderData ? [] : loadedPages(query.data);
		const items = pages.flatMap((page) => page.content);
		// `null` asks for the first page; `undefined` is past the last one.
		let cursor: string | null | undefined = pages.length === 0 ? null : pages.at(-1)?.nextCursor;
		while (cursor !== undefined) {
			const request = { ...scope, cursor: cursor ?? undefined, size: COPY_PAGE_SIZE };
			const page =
				userId === undefined
					? await queryClient.query(
							getActivityWorkOptions({ path: { workspaceSlug }, query: { login, ...request } }),
						)
					: await queryClient.query(
							getActivityPersonWorkOptions({ path: { workspaceSlug, userId }, query: request }),
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

	const metadataState = queryLoadState(metadata);
	if (teamId !== undefined && resolvedTeam === undefined && metadataState.status === "error") {
		return metadataState;
	}
	return infiniteListState(query, (pages) => ({
		items: pages.flatMap((page) => page.content),
		stale: query.isPlaceholderData,
		onCopy,
	}));
}
