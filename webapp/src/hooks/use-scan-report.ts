import {
	type InfiniteData,
	type UseInfiniteQueryResult,
	useInfiniteQuery,
	useMutation,
	useQuery,
	useQueryClient,
} from "@tanstack/react-query";

import {
	getScanOptions,
	getScanQueryKey,
	getScanReportOptions,
	getScanReportQueryKey,
	listScanReportItemsInfiniteOptions,
	listScanReportItemsInfiniteQueryKey,
	listScanReportProblemsInfiniteOptions,
	listScanReportProblemsInfiniteQueryKey,
	listScansQueryKey,
	updateScanStatusMutation,
} from "@/api/@tanstack/react-query.gen";
import { exportScanSarif } from "@/api/sdk.gen";
import type { ScanReportItem, ScanReportProblem } from "@/api/types.gen";
import { isActiveScan, SCAN_POLL_MS } from "@/components/admin/practice-scans/scan-display";
import type { ScanListState } from "@/components/admin/practice-scans/scan-list-state";
import type { ScanAction, ScanReportState } from "@/components/admin/practice-scans/ScanReportPage";
import type { ProblemBaselineState } from "@/components/practice-vocabulary/scan-baseline-defs";
import type { ScanEntryStatus } from "@/components/practice-vocabulary/scan-entry-defs";

import { queryErrorIsRetryable } from "@/components/common/QueryErrorAlert";
import { saveBlobFile } from "@/lib/download";
import { infiniteListState } from "@/runtime/tanstack-query/infinite-list";
import { nextCursorOf } from "@/runtime/tanstack-query/list-pages";
import { useQueryReadRefusal } from "@/runtime/tanstack-query/use-query-read-refusal";

import { useInvalidateOnSettle } from "./use-invalidate-on-settle";

export const SCAN_REPORT_PAGE_SIZE = 50;

export interface ScanReportFilters {
	baseline?: ProblemBaselineState;
	fileStatus?: ScanEntryStatus;
}

export interface ScanReportData {
	state: ScanReportState;
	problems: ScanListState<ScanReportProblem>;
	files: ScanListState<ScanReportItem>;
	cancel: ScanAction;
	exportSarif: ScanAction;
}

function listState<TPage extends { content: readonly TEntry[] }, TEntry>(
	query: UseInfiniteQueryResult<InfiniteData<TPage>>,
): ScanListState<TEntry> {
	const state = infiniteListState(query, (pages) => ({
		entries: pages.flatMap((page) => page.content),
	}));
	if (state.status !== "ready") {
		return state;
	}
	return {
		status: "ready",
		entries: state.entries,
		hasNext: state.hasMore,
		loadingMore: state.isLoadingMore,
		loadMoreError: state.loadMoreError,
		refreshFailure: state.refreshFailure,
		onLoadMore: state.onLoadMore,
	};
}

export function sarifFilename(disposition: string | null): string | undefined {
	const match = /filename="?(?<name>[^";]+)"?/iu.exec(disposition ?? "");
	return match?.groups?.name;
}

/**
 * Retryable refresh failures retain readable data; refused reads withdraw their cached evidence.
 * Active scan and report queries poll independently until each reaches a terminal state.
 */
export function useScanReport(
	workspaceSlug: string,
	scanId: string,
	filters: ScanReportFilters,
): ScanReportData {
	const queryClient = useQueryClient();
	const path = { workspaceSlug, scanId };
	const scanOptions = getScanOptions({ path });
	const scanQuery = useQuery({
		...scanOptions,
		refetchInterval: (query) =>
			query.state.data !== undefined && isActiveScan(query.state.data.status)
				? SCAN_POLL_MS
				: false,
	});
	useQueryReadRefusal(scanOptions.queryKey, scanQuery.isError, scanQuery.error);
	const reportOptions = getScanReportOptions({ path });
	const reportQuery = useQuery({
		...reportOptions,
		refetchInterval: (query) =>
			query.state.data !== undefined && isActiveScan(query.state.data.scan.status)
				? SCAN_POLL_MS
				: false,
	});
	useQueryReadRefusal(reportOptions.queryKey, reportQuery.isError, reportQuery.error);
	const reportStatus = reportQuery.data?.scan.status;
	const listsPoll = reportStatus !== undefined && isActiveScan(reportStatus) ? SCAN_POLL_MS : false;

	const problemsOptions = listScanReportProblemsInfiniteOptions({
		path,
		query: { size: SCAN_REPORT_PAGE_SIZE, baselineState: filters.baseline },
	});
	const problemsQuery = useInfiniteQuery({
		...problemsOptions,
		initialPageParam: { path, query: { cursor: undefined } },
		getNextPageParam: nextCursorOf,
		refetchInterval: listsPoll,
	});
	useQueryReadRefusal(problemsOptions.queryKey, problemsQuery.isError, problemsQuery.error);
	const itemsOptions = listScanReportItemsInfiniteOptions({
		path,
		query: { size: SCAN_REPORT_PAGE_SIZE, status: filters.fileStatus },
	});
	const itemsQuery = useInfiniteQuery({
		...itemsOptions,
		initialPageParam: { path, query: { cursor: undefined } },
		getNextPageParam: nextCursorOf,
		refetchInterval: listsPoll,
	});

	useQueryReadRefusal(itemsOptions.queryKey, itemsQuery.isError, itemsQuery.error);
	// Read lists once after the report becomes terminal; polling alone can stop before their final rows arrive.
	useInvalidateOnSettle(scanId, listsPoll !== false, [
		listScanReportItemsInfiniteQueryKey({ path }),
		listScanReportProblemsInfiniteQueryKey({ path }),
	]);

	const cancel = useMutation({
		...updateScanStatusMutation(),
		onSuccess: (_detail, variables) => {
			const submitted = variables.path;
			void queryClient.invalidateQueries({
				queryKey: getScanQueryKey({ path: submitted }),
				exact: true,
			});
			void queryClient.invalidateQueries({
				queryKey: getScanReportQueryKey({ path: submitted }),
				exact: true,
			});
			void queryClient.invalidateQueries({
				queryKey: listScanReportItemsInfiniteQueryKey({ path: submitted }),
			});
			void queryClient.invalidateQueries({
				queryKey: listScanReportProblemsInfiniteQueryKey({ path: submitted }),
			});
			void queryClient.invalidateQueries({
				queryKey: listScansQueryKey({ path: { workspaceSlug: submitted.workspaceSlug } }),
			});
		},
	});
	const cancelOwnsScan =
		cancel.variables?.path.workspaceSlug === workspaceSlug &&
		cancel.variables.path.scanId === scanId;

	const download = useMutation({
		mutationFn: async () => {
			const { data, response } = await exportScanSarif({
				path,
				// Automatic parsing treats SARIF's +json media type as JSON; downloads require a Blob.
				parseAs: "blob",
				throwOnError: true,
			});
			const filename =
				sarifFilename(response.headers.get("Content-Disposition")) ??
				`hephaestus-scan-${scanId}.sarif`;
			saveBlobFile(data, filename);
		},
	});

	const failed = [scanQuery, reportQuery].filter((query) => query.isError);
	const retry = () => {
		for (const query of failed) {
			void query.refetch();
		}
	};
	const blocking = failed.find(
		(query) => !query.isRefetchError || !queryErrorIsRetryable(query.error),
	);
	let state: ScanReportState;
	if (blocking !== undefined) {
		state = { status: "error", error: blocking.error, onRetry: retry };
	} else if (scanQuery.data !== undefined && reportQuery.data !== undefined) {
		state = {
			status: "ready",
			scan: scanQuery.data,
			report: reportQuery.data,
			refreshFailure:
				failed.length > 0
					? {
							error: failed[0]?.error,
							pending: failed.some((query) => query.isFetching),
							onRetry: retry,
						}
					: undefined,
		};
	} else {
		state = { status: "loading" };
	}

	return {
		state,
		problems: listState(problemsQuery),
		files: listState(itemsQuery),
		cancel: {
			pending: cancelOwnsScan && cancel.isPending,
			error: cancelOwnsScan ? (cancel.error ?? undefined) : undefined,
			onRun: () => cancel.mutate({ path, body: { status: "CANCELLED" } }),
		},
		exportSarif: {
			pending: download.isPending,
			error: download.error ?? undefined,
			onRun: () => download.mutate(),
		},
	};
}
