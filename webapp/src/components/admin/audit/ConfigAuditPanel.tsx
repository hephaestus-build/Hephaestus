import type { ConfigAuditEntryView } from "@/api/types.gen";
import type { AuditListState } from "@/components/admin/audit/audit-list";
import {
	type ConfigAuditSearch,
	fromDateRange,
	toDateRange,
} from "@/components/admin/audit/audit-search";
import { configAuditFilters } from "@/components/admin/audit/config-audit-filters";
import { ACTION_LABELS, ENTITY_TYPE_LABELS } from "@/components/admin/audit/config-audit-format";
import { ConfigAuditTable } from "@/components/admin/audit/ConfigAuditTable";
import { nameForRef } from "@/components/admin/audit/name-for-ref";
import { DateRangeFacet } from "@/components/common/DateRangeFacet";
import { FacetMultiSelect, toFacetOptions } from "@/components/common/FacetMultiSelect";
import { FilterToolbar } from "@/components/common/FilterToolbar";
import { ReferenceFilterPill } from "@/components/common/ReferenceFilterPill";
import { ResultCount } from "@/components/common/ResultCount";
import { nonEmpty } from "@/lib/search-params";

const ENTITY_TYPE_OPTIONS = toFacetOptions(ENTITY_TYPE_LABELS);
const ACTION_OPTIONS = toFacetOptions(ACTION_LABELS);

export interface ConfigAuditPanelProps {
	search: ConfigAuditSearch;
	onSearchChange: (patch: Partial<ConfigAuditSearch>) => void;
	resolveWorkspaceName?: (id: number) => string | undefined;
	list: AuditListState<ConfigAuditEntryView>;
	/** How many changes the filters match, once that read has answered. */
	total: number | undefined;
	/** The read's query key, hashed: the workspace and filters the list was read for. */
	read: string;
	/** The instance-wide list names the workspace of each change. */
	showWorkspace?: boolean;
}

export function ConfigAuditPanel({
	search,
	onSearchChange,
	list,
	total: count,
	read,
	showWorkspace = false,
	resolveWorkspaceName,
}: ConfigAuditPanelProps) {
	const dateRange = toDateRange(search);
	// Names and the count come only from a read that stands, never from one refused.
	const entries = list.status === "ready" ? list.rows : [];
	const total = list.status === "ready" ? count : undefined;
	const query = configAuditFilters(search);
	const hasAppliedFilter =
		(query.entityType?.length ?? 0) > 0 ||
		(query.action?.length ?? 0) > 0 ||
		query.actorId !== undefined ||
		query.from !== undefined;

	const reset = () =>
		onSearchChange({
			entityType: undefined,
			action: undefined,
			actorId: undefined,
			from: undefined,
			to: undefined,
		});

	return (
		<div className="space-y-4">
			<FilterToolbar hasFilter={hasAppliedFilter} onReset={reset}>
				<FacetMultiSelect
					title="Setting"
					options={ENTITY_TYPE_OPTIONS}
					selected={search.entityType ?? []}
					onChange={(values) => onSearchChange({ entityType: nonEmpty(values) })}
				/>
				<FacetMultiSelect
					title="Action"
					options={ACTION_OPTIONS}
					selected={search.action ?? []}
					onChange={(values) => onSearchChange({ action: nonEmpty(values) })}
				/>
				<DateRangeFacet
					title="Changed"
					value={dateRange}
					onChange={(range) => onSearchChange(fromDateRange(range))}
				/>
				{search.actorId !== undefined && (
					<ReferenceFilterPill
						label="Actor"
						id={search.actorId}
						name={nameForRef(entries, search.actorId)}
						onClear={() => onSearchChange({ actorId: undefined })}
					/>
				)}
			</FilterToolbar>

			<ResultCount total={total} noun={["change", "changes"]} hasFilter={hasAppliedFilter} />

			<ConfigAuditTable
				// One table per read: another workspace's or filter's read starts with nothing selected,
				// while the same read failing to refresh keeps what is open.
				key={read}
				state={list}
				hasFilter={hasAppliedFilter}
				onResetFilters={reset}
				onFilterActor={(actorId) => onSearchChange({ actorId })}
				showWorkspace={showWorkspace}
				resolveWorkspaceName={resolveWorkspaceName}
			/>
		</div>
	);
}
