import { hashKey, useInfiniteQuery, useQuery } from "@tanstack/react-query";

import {
	adminCountConfigAuditEventsOptions,
	adminListConfigAuditEventsInfiniteOptions,
	countWorkspaceConfigAuditEventsOptions,
	listWorkspaceConfigAuditEventsInfiniteOptions,
} from "@/api/@tanstack/react-query.gen";
import type { ConfigAuditEntryView } from "@/api/types.gen";
import { type AuditListState, auditRows } from "@/components/admin/audit/audit-list";
import {
	type ConfigAuditSearch,
	dayAfterInstant,
	dayStartInstant,
	fromDateRange,
	toDateRange,
} from "@/components/admin/audit/audit-search";
import { ACTION_LABELS, ENTITY_TYPE_LABELS } from "@/components/admin/audit/config-audit-format";
import { ConfigAuditTable } from "@/components/admin/audit/ConfigAuditTable";
import { nameForRef } from "@/components/admin/audit/name-for-ref";
import { DateRangeFacet } from "@/components/common/DateRangeFacet";
import { FacetMultiSelect, toFacetOptions } from "@/components/common/FacetMultiSelect";
import { FilterToolbar } from "@/components/common/FilterToolbar";
import { ReferenceFilterPill } from "@/components/common/ReferenceFilterPill";
import { ResultCount } from "@/components/common/ResultCount";
import { narrowToEnum, nonEmpty } from "@/lib/search-params";
import { infiniteListState } from "@/runtime/tanstack-query/infinite-list";
import { nextCursorOf } from "@/runtime/tanstack-query/list-pages";
import { useQueryReadRefusal } from "@/runtime/tanstack-query/use-query-read-refusal";

const PAGE_SIZE = 50;

const ENTITY_TYPE_OPTIONS = toFacetOptions(ENTITY_TYPE_LABELS);
const ACTION_OPTIONS = toFacetOptions(ACTION_LABELS);
const ENTITY_TYPES = ENTITY_TYPE_OPTIONS.map((option) => option.value);
const ACTIONS = ACTION_OPTIONS.map((option) => option.value);

function toFilters(search: ConfigAuditSearch) {
	const dateRange = toDateRange(search);
	return {
		entityType: narrowToEnum(search.entityType, ENTITY_TYPES),
		action: narrowToEnum(search.action, ACTIONS),
		actorId: search.actorId,
		from: dateRange?.from ? dayStartInstant(dateRange.from) : undefined,
		to: dateRange?.to ? dayAfterInstant(dateRange.to) : undefined,
	};
}

function toQuery(search: ConfigAuditSearch) {
	return { size: PAGE_SIZE, ...toFilters(search) };
}

export interface ConfigAuditPanelProps {
	search: ConfigAuditSearch;
	onSearchChange: (patch: Partial<ConfigAuditSearch>) => void;
	resolveWorkspaceName?: (id: number) => string | undefined;
}

export function AdminConfigAuditPanel({
	search,
	onSearchChange,
	resolveWorkspaceName,
}: ConfigAuditPanelProps) {
	const listOptions = adminListConfigAuditEventsInfiniteOptions({ query: toQuery(search) });
	const listQuery = useInfiniteQuery({
		...listOptions,
		initialPageParam: { query: { cursor: undefined } },
		getNextPageParam: nextCursorOf,
	});
	useQueryReadRefusal(listOptions.queryKey, listQuery.isError, listQuery.error);
	const list = infiniteListState(listQuery, auditRows);
	const countOptions = adminCountConfigAuditEventsOptions({ query: toFilters(search) });
	const countQuery = useQuery(countOptions);
	useQueryReadRefusal(countOptions.queryKey, countQuery.isError, countQuery.error);

	return (
		<ConfigAuditView
			search={search}
			onSearchChange={onSearchChange}
			list={list}
			total={countQuery.data?.total}
			read={hashKey(listOptions.queryKey)}
			showWorkspace
			resolveWorkspaceName={resolveWorkspaceName}
		/>
	);
}

export function WorkspaceConfigAuditPanel({
	workspaceSlug,
	search,
	onSearchChange,
}: Omit<ConfigAuditPanelProps, "resolveWorkspaceName"> & { workspaceSlug: string }) {
	const listOptions = listWorkspaceConfigAuditEventsInfiniteOptions({
		path: { workspaceSlug },
		query: toQuery(search),
	});
	const listQuery = useInfiniteQuery({
		...listOptions,
		initialPageParam: { path: { workspaceSlug }, query: { cursor: undefined } },
		getNextPageParam: nextCursorOf,
	});
	useQueryReadRefusal(listOptions.queryKey, listQuery.isError, listQuery.error);
	const list = infiniteListState(listQuery, auditRows);
	const countOptions = countWorkspaceConfigAuditEventsOptions({
		path: { workspaceSlug },
		query: toFilters(search),
	});
	const countQuery = useQuery(countOptions);
	useQueryReadRefusal(countOptions.queryKey, countQuery.isError, countQuery.error);

	return (
		<ConfigAuditView
			search={search}
			onSearchChange={onSearchChange}
			list={list}
			total={countQuery.data?.total}
			read={hashKey(listOptions.queryKey)}
		/>
	);
}

function ConfigAuditView({
	search,
	onSearchChange,
	list,
	total: count,
	read,
	showWorkspace = false,
	resolveWorkspaceName,
}: ConfigAuditPanelProps & {
	list: AuditListState<ConfigAuditEntryView>;
	/** How many changes the filters match, once that read has answered. */
	total: number | undefined;
	/** The read's query key, hashed: the workspace and filters the list was read for. */
	read: string;
	showWorkspace?: boolean;
}) {
	const dateRange = toDateRange(search);
	// Names and the count come only from a read that stands, never from one refused.
	const entries = list.status === "ready" ? list.rows : [];
	const total = list.status === "ready" ? count : undefined;
	const query = toQuery(search);
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
