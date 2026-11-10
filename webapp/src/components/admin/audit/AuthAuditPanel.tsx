import { DownloadIcon } from "lucide-react";

import type { AuthEventView } from "@/api/types.gen";
import { AdminAuditTable } from "@/components/admin/audit/AdminAuditTable";
import { EVENT_TYPE_LABELS } from "@/components/admin/audit/audit-format";
import type { AuditListState } from "@/components/admin/audit/audit-list";
import {
	type AuditSearch,
	authAuditFilters,
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
import { nonEmpty } from "@/lib/search-params";

const EVENT_TYPE_OPTIONS = toFacetOptions(EVENT_TYPE_LABELS);

const OUTCOME_OPTIONS = [
	{ value: "SUCCESS", label: "Success" },
	{ value: "FAILURE", label: "Failure" },
];

export interface AuthAuditPanelProps {
	search: AuditSearch;
	onSearchChange: (patch: Partial<AuditSearch>) => void;
	resolveWorkspaceName?: (id: number) => string | undefined;
	list: AuditListState<AuthEventView>;
	/** How many events the filters match, once that read has answered. */
	total: number | undefined;
	/** The read's query key, hashed: the filters the list was read for. */
	read: string;
	/** The CSV export of the matching events is under way. */
	exporting: boolean;
	onExport: () => void;
}

export function AuthAuditPanel({
	search,
	onSearchChange,
	resolveWorkspaceName,
	list,
	total: count,
	read,
	exporting,
	onExport,
}: AuthAuditPanelProps) {
	const dateRange = toDateRange(search);
	const filters = authAuditFilters(search);
	// Names, the count and the export come only from a read that stands, never from one refused.
	const events = list.status === "ready" ? list.rows : [];
	const total = list.status === "ready" ? count : undefined;
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
						onClick={onExport}
						disabled={exporting || events.length === 0}
					>
						{exporting ? <Spinner className="size-3.5" /> : <DownloadIcon aria-hidden />}
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
				key={read}
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
