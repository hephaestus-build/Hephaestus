import { hashKey, useInfiniteQuery, useMutation } from "@tanstack/react-query";
import { DownloadIcon } from "lucide-react";
import { toast } from "@/lib/toast";

import { adminListAuthEventsInfiniteOptions } from "@/api/@tanstack/react-query.gen";
import { adminExportAuthEvents } from "@/api/sdk.gen";
import type { AdminExportAuthEventsData } from "@/api/types.gen";
import { AdminAuditTable } from "@/components/admin/audit/AdminAuditTable";
import { EVENT_TYPE_LABELS } from "@/components/admin/audit/audit-format";
import { auditRows } from "@/components/admin/audit/audit-list";
import {
	type AuditSearch,
	dayAfterInstant,
	dayStartInstant,
	fromDateRange,
	toDateRange,
} from "@/components/admin/audit/audit-search";
import { nameForRef } from "@/components/admin/audit/name-for-ref";
import { DateRangeFacet } from "@/components/common/DateRangeFacet";
import { FacetMultiSelect, toFacetOptions } from "@/components/common/FacetMultiSelect";
import { FilterToolbar } from "@/components/common/FilterToolbar";
import { ReferenceFilterPill } from "@/components/common/ReferenceFilterPill";
import { ResultCount } from "@/components/common/ResultCount";
import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { saveTextFile } from "@/lib/download";
import { narrowToEnum, nonEmpty } from "@/lib/search-params";
import { infiniteListState } from "@/runtime/tanstack-query/infinite-list";
import { springPageParams } from "@/runtime/tanstack-query/spring-page";
import { useQueryReadRefusal } from "@/runtime/tanstack-query/use-query-read-refusal";

const PAGE_SIZE = 50;

const EVENT_TYPE_OPTIONS = toFacetOptions(EVENT_TYPE_LABELS);
const EVENT_TYPES = EVENT_TYPE_OPTIONS.map((option) => option.value);

const OUTCOME_OPTIONS = [
	{ value: "SUCCESS", label: "Success" },
	{ value: "FAILURE", label: "Failure" },
];
const OUTCOMES: ("SUCCESS" | "FAILURE")[] = ["SUCCESS", "FAILURE"];

/**
 * Reads the auth events under `query` as CSV and hands them to the browser as a file named for the
 * day the reader saved it.
 */
async function downloadAuditLog(query: AdminExportAuthEventsData["query"]): Promise<void> {
	const { data, error } = await adminExportAuthEvents({ query });
	if (error !== undefined || typeof data !== "string") {
		throw new Error("Export failed");
	}
	const day = new Date().toISOString().slice(0, 10);
	saveTextFile(data, `audit-log-${day}.csv`, "text/csv;charset=utf-8;");
}

export interface AuthAuditPanelProps {
	search: AuditSearch;
	onSearchChange: (patch: Partial<AuditSearch>) => void;
	resolveWorkspaceName?: (id: number) => string | undefined;
}

export function AuthAuditPanel({
	search,
	onSearchChange,
	resolveWorkspaceName,
}: AuthAuditPanelProps) {
	const dateRange = toDateRange(search);
	const filters = {
		eventType: narrowToEnum(search.eventType, EVENT_TYPES),
		result: narrowToEnum(search.outcome, OUTCOMES),
		accountId: search.accountId,
		actingAccountId: search.actorId,
		from: dateRange?.from ? dayStartInstant(dateRange.from) : undefined,
		to: dateRange?.to ? dayAfterInstant(dateRange.to) : undefined,
	} as const;

	const listOptions = adminListAuthEventsInfiniteOptions({
		query: { size: PAGE_SIZE, ...filters },
	});
	const listQuery = useInfiniteQuery({ ...listOptions, ...springPageParams });
	useQueryReadRefusal(listOptions.queryKey, listQuery.isError, listQuery.error);
	const list = infiniteListState(listQuery, auditRows);
	// Names, the count and the export come only from a read that stands, never from one refused.
	const events = list.status === "ready" ? list.rows : [];
	const total = list.status === "ready" ? list.total : undefined;
	const hasAppliedFilter =
		(filters.eventType?.length ?? 0) > 0 ||
		(filters.result?.length ?? 0) > 0 ||
		filters.accountId !== undefined ||
		filters.actingAccountId !== undefined ||
		filters.from !== undefined;

	const reset = () =>
		onSearchChange({
			eventType: undefined,
			outcome: undefined,
			accountId: undefined,
			actorId: undefined,
			from: undefined,
			to: undefined,
		});

	const exportCsv = useMutation({
		mutationFn: async () => downloadAuditLog(filters),
		onError: () => toast.error("We could not export the audit log. Try again."),
	});

	return (
		<div className="space-y-4">
			<FilterToolbar
				hasFilter={hasAppliedFilter}
				onReset={reset}
				actions={
					<Button
						variant="outline"
						size="sm"
						className="h-8"
						onClick={() => exportCsv.mutate()}
						disabled={exportCsv.isPending || events.length === 0}
					>
						{exportCsv.isPending ? <Spinner className="size-3.5" /> : <DownloadIcon aria-hidden />}
						Export CSV
					</Button>
				}
			>
				<FacetMultiSelect
					title="Event"
					options={EVENT_TYPE_OPTIONS}
					selected={search.eventType ?? []}
					onChange={(values) => onSearchChange({ eventType: nonEmpty(values) })}
				/>
				<FacetMultiSelect
					title="Result"
					options={OUTCOME_OPTIONS}
					selected={search.outcome ?? []}
					onChange={(values) => onSearchChange({ outcome: nonEmpty(values) })}
				/>
				<DateRangeFacet
					title="Occurred"
					value={dateRange}
					onChange={(range) => onSearchChange(fromDateRange(range))}
				/>
				{search.accountId !== undefined && (
					<ReferenceFilterPill
						label="Account"
						id={search.accountId}
						name={nameForRef(events, search.accountId)}
						onClear={() => onSearchChange({ accountId: undefined })}
					/>
				)}
				{search.actorId !== undefined && (
					<ReferenceFilterPill
						label="Acting account"
						id={search.actorId}
						name={nameForRef(events, search.actorId)}
						onClear={() => onSearchChange({ actorId: undefined })}
					/>
				)}
			</FilterToolbar>

			<ResultCount total={total} noun={["event", "events"]} hasFilter={hasAppliedFilter} />

			<AdminAuditTable
				// One table per read: another filter's read starts with nothing selected, while the same
				// read failing to refresh keeps what is open.
				key={hashKey(listOptions.queryKey)}
				state={list}
				hasFilter={hasAppliedFilter}
				onResetFilters={reset}
				onFilterAccount={(accountId) => onSearchChange({ accountId })}
				onFilterActor={(actorId) => onSearchChange({ actorId })}
				resolveWorkspaceName={resolveWorkspaceName}
			/>
		</div>
	);
}
