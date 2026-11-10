import { hashKey, useInfiniteQuery, useMutation, useQuery } from "@tanstack/react-query";
import { toast } from "@/lib/toast";

import {
	adminCountAuthEventsOptions,
	adminCountConfigAuditEventsOptions,
	adminListAuthEventsInfiniteOptions,
	adminListConfigAuditEventsInfiniteOptions,
	countWorkspaceConfigAuditEventsOptions,
	listWorkspaceConfigAuditEventsInfiniteOptions,
} from "@/api/@tanstack/react-query.gen";
import { adminExportAuthEvents } from "@/api/sdk.gen";
import type { AdminExportAuthEventsData } from "@/api/types.gen";
import { auditRows } from "@/components/admin/audit/audit-list";
import type { AuditSearch, ConfigAuditSearch } from "@/components/admin/audit/audit-search";
import {
	type AuthAuditPanelProps,
	authAuditFilters,
} from "@/components/admin/audit/AuthAuditPanel";
import {
	type ConfigAuditPanelProps,
	configAuditFilters,
	configAuditQuery,
} from "@/components/admin/audit/ConfigAuditPanel";
import { saveTextFile } from "@/lib/download";
import { formatNumber } from "@/lib/numbers";
import { infiniteListState } from "@/runtime/tanstack-query/infinite-list";
import { nextCursorOf } from "@/runtime/tanstack-query/list-pages";
import { useQueryReadRefusal } from "@/runtime/tanstack-query/use-query-read-refusal";

const PAGE_SIZE = 50;

type ConfigAuditRead = Pick<ConfigAuditPanelProps, "list" | "total" | "read">;

/**
 * Reads the auth events under `query` as CSV and hands them to the browser as a file named for the
 * day the reader saved it.
 */
async function downloadAuditLog(query: AdminExportAuthEventsData["query"]): Promise<void> {
	const { data, error, response } = await adminExportAuthEvents({ query });
	if (error !== undefined || typeof data !== "string") {
		throw new Error("Export failed");
	}
	const day = new Date().toISOString().slice(0, 10);
	saveTextFile(data, `audit-log-${day}.csv`, "text/csv;charset=utf-8;");
	const leftOut = Number(response?.headers.get("X-Left-Out-Count"));
	if (leftOut > 0) {
		toast.warning("The file does not hold every matching event", {
			description: `It leaves out ${formatNumber(leftOut)} matching ${leftOut === 1 ? "event" : "events"}. Narrow the filters to export them.`,
		});
	}
}

/** The instance's sign-in and permission events under the filters of `search`, and their CSV export. */
export function useAuthAuditRead(
	search: AuditSearch,
): Pick<AuthAuditPanelProps, "list" | "total" | "read" | "exporting" | "onExport"> {
	const filters = authAuditFilters(search);
	const listOptions = adminListAuthEventsInfiniteOptions({
		query: { size: PAGE_SIZE, ...filters },
	});
	const listQuery = useInfiniteQuery({
		...listOptions,
		initialPageParam: { query: { cursor: undefined } },
		getNextPageParam: nextCursorOf,
	});
	useQueryReadRefusal(listOptions.queryKey, listQuery.isError, listQuery.error);
	const countOptions = adminCountAuthEventsOptions({ query: filters });
	const countQuery = useQuery(countOptions);
	useQueryReadRefusal(countOptions.queryKey, countQuery.isError, countQuery.error);
	const exportCsv = useMutation({
		mutationFn: async () => downloadAuditLog(filters),
		onError: () => toast.error("We could not export the audit log. Try again."),
	});
	return {
		list: infiniteListState(listQuery, auditRows),
		total: countQuery.data?.total,
		read: hashKey(listOptions.queryKey),
		exporting: exportCsv.isPending,
		onExport: () => exportCsv.mutate(),
	};
}

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
