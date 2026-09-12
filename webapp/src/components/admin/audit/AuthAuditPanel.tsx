import { useInfiniteQuery, useMutation } from "@tanstack/react-query";
import { DownloadIcon } from "lucide-react";
import { toast } from "sonner";

import { adminListAuthEventsInfiniteOptions } from "@/api/@tanstack/react-query.gen";
import { adminExportAuthEvents } from "@/api/sdk.gen";
import type { AuthEventView } from "@/api/types.gen";
import {
	type AuditSearch,
	dayAfterInstant,
	dayStartInstant,
	fromDateRange,
	toDateRange,
} from "@/components/admin/audit-shared/audit-search";
import { nameForRef } from "@/components/admin/audit-shared/name-for-ref";
import { AdminAuditTable } from "@/components/admin/audit/AdminAuditTable";
import { EVENT_TYPE_LABELS } from "@/components/admin/audit/audit-format";
import { DateRangeFacet } from "@/components/common/DateRangeFacet";
import { FacetMultiSelect, toFacetOptions } from "@/components/common/FacetMultiSelect";
import { FilterToolbar } from "@/components/common/FilterToolbar";
import { ReferenceFilterPill } from "@/components/common/ReferenceFilterPill";
import { ResultCount } from "@/components/common/ResultCount";
import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { loadedPages, springPageParams } from "@/integrations/tanstack-query/spring-page";
import { dedupeById } from "@/lib/dedupe-by-id";
import { saveTextFile } from "@/lib/download";
import { narrowToEnum, nonEmpty } from "@/lib/search-params";

const PAGE_SIZE = 50;

const EVENT_TYPE_OPTIONS = toFacetOptions(EVENT_TYPE_LABELS);
const EVENT_TYPES = EVENT_TYPE_OPTIONS.map((option) => option.value);

const OUTCOME_OPTIONS = [
	{ value: "SUCCESS", label: "Success" },
	{ value: "FAILURE", label: "Failure" },
];
const OUTCOMES: ("SUCCESS" | "FAILURE")[] = ["SUCCESS", "FAILURE"];

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

	const listQuery = useInfiniteQuery({
		...adminListAuthEventsInfiniteOptions({ query: { size: PAGE_SIZE, ...filters } }),
		...springPageParams,
	});

	const pages = loadedPages(listQuery.data);
	const events: AuthEventView[] = dedupeById(pages.flatMap((p) => p.content ?? []));
	const total = pages[0]?.totalElements;
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
		mutationFn: async () => {
			const { data, error } = await adminExportAuthEvents({ query: filters });
			if (error || typeof data !== "string") throw new Error("Export failed");
			const day = new Date().toISOString().slice(0, 10);
			saveTextFile(data, `audit-log-${day}.csv`, "text/csv;charset=utf-8;");
		},
		onError: () => toast.error("Could not export the audit log. Please try again."),
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
						label="Impersonated by"
						id={search.actorId}
						name={nameForRef(events, search.actorId)}
						onClear={() => onSearchChange({ actorId: undefined })}
					/>
				)}
			</FilterToolbar>

			<ResultCount total={total} noun={["event", "events"]} hasFilter={hasAppliedFilter} />

			<AdminAuditTable
				events={events}
				isLoading={listQuery.isLoading}
				isError={listQuery.isError}
				hasFilter={hasAppliedFilter}
				onResetFilters={reset}
				hasNextPage={listQuery.hasNextPage}
				isFetchingNextPage={listQuery.isFetchingNextPage}
				onLoadMore={() => void listQuery.fetchNextPage()}
				onRetry={() => void listQuery.refetch()}
				onFilterAccount={(accountId) => onSearchChange({ accountId })}
				onFilterActor={(actorId) => onSearchChange({ actorId })}
				resolveWorkspaceName={resolveWorkspaceName}
			/>
		</div>
	);
}
