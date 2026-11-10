import { hashKey, useInfiniteQuery, useQuery } from "@tanstack/react-query";

import {
	adminCountConfigAuditEventsOptions,
	adminListConfigAuditEventsInfiniteOptions,
	countWorkspaceConfigAuditEventsOptions,
	listWorkspaceConfigAuditEventsInfiniteOptions,
} from "@/api/@tanstack/react-query.gen";
import { auditRows } from "@/components/admin/audit/audit-list";
import type { ConfigAuditSearch } from "@/components/admin/audit/audit-search";
import {
	configAuditFilters,
	configAuditQuery,
} from "@/components/admin/audit/config-audit-filters";
import type { ConfigAuditPanelProps } from "@/components/admin/audit/ConfigAuditPanel";
import { infiniteListState } from "@/runtime/tanstack-query/infinite-list";
import { nextCursorOf } from "@/runtime/tanstack-query/list-pages";
import { useQueryReadRefusal } from "@/runtime/tanstack-query/use-query-read-refusal";

const PAGE_SIZE = 50;

type ConfigAuditRead = Pick<ConfigAuditPanelProps, "list" | "total" | "read">;

/** Settings changes across the instance under the filters of `search`. */
export function useAdminConfigAuditRead(search: ConfigAuditSearch): ConfigAuditRead {
	const listOptions = adminListConfigAuditEventsInfiniteOptions({
		query: configAuditQuery(search, PAGE_SIZE),
	});
	const listQuery = useInfiniteQuery({
		...listOptions,
		initialPageParam: { query: { cursor: undefined } },
		getNextPageParam: nextCursorOf,
	});
	useQueryReadRefusal(listOptions.queryKey, listQuery.isError, listQuery.error);
	const countOptions = adminCountConfigAuditEventsOptions({ query: configAuditFilters(search) });
	const countQuery = useQuery(countOptions);
	useQueryReadRefusal(countOptions.queryKey, countQuery.isError, countQuery.error);
	return {
		list: infiniteListState(listQuery, auditRows),
		total: countQuery.data?.total,
		read: hashKey(listOptions.queryKey),
	};
}

/** One workspace's settings changes under the filters of `search`. */
export function useWorkspaceConfigAuditRead(
	workspaceSlug: string,
	search: ConfigAuditSearch,
): ConfigAuditRead {
	const listOptions = listWorkspaceConfigAuditEventsInfiniteOptions({
		path: { workspaceSlug },
		query: configAuditQuery(search, PAGE_SIZE),
	});
	const listQuery = useInfiniteQuery({
		...listOptions,
		initialPageParam: { path: { workspaceSlug }, query: { cursor: undefined } },
		getNextPageParam: nextCursorOf,
	});
	useQueryReadRefusal(listOptions.queryKey, listQuery.isError, listQuery.error);
	const countOptions = countWorkspaceConfigAuditEventsOptions({
		path: { workspaceSlug },
		query: configAuditFilters(search),
	});
	const countQuery = useQuery(countOptions);
	useQueryReadRefusal(countOptions.queryKey, countQuery.isError, countQuery.error);
	return {
		list: infiniteListState(listQuery, auditRows),
		total: countQuery.data?.total,
		read: hashKey(listOptions.queryKey),
	};
}
