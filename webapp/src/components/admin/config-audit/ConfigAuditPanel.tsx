import {
	type InfiniteData,
	type UseInfiniteQueryResult,
	useInfiniteQuery,
} from "@tanstack/react-query";

import {
	adminListConfigAuditEventsInfiniteOptions,
	listWorkspaceConfigAuditEventsInfiniteOptions,
} from "@/api/@tanstack/react-query.gen";
import type { ConfigAuditEntryView, PageResponseDtoConfigAuditEntryView } from "@/api/types.gen";
import {
	type ConfigAuditSearch,
	dayAfterInstant,
	dayStartInstant,
	fromDateRange,
	toDateRange,
} from "@/components/admin/audit-shared/audit-search";
import { nameForRef } from "@/components/admin/audit-shared/name-for-ref";
import {
	ACTION_LABELS,
	ENTITY_TYPE_LABELS,
} from "@/components/admin/config-audit/config-audit-format";
import { ConfigAuditTable } from "@/components/admin/config-audit/ConfigAuditTable";
import { DateRangeFacet } from "@/components/common/DateRangeFacet";
import { FacetMultiSelect, toFacetOptions } from "@/components/common/FacetMultiSelect";
import { FilterToolbar } from "@/components/common/FilterToolbar";
import { ReferenceFilterPill } from "@/components/common/ReferenceFilterPill";
import { ResultCount } from "@/components/common/ResultCount";
import { springPageParams } from "@/integrations/tanstack-query/spring-page";
import { dedupeById } from "@/lib/dedupe-by-id";
import { narrowToEnum, nonEmpty } from "@/lib/search-params";

const PAGE_SIZE = 50;

const ENTITY_TYPE_OPTIONS = toFacetOptions(ENTITY_TYPE_LABELS);
const ACTION_OPTIONS = toFacetOptions(ACTION_LABELS);
const ENTITY_TYPES = ENTITY_TYPE_OPTIONS.map((option) => option.value);
const ACTIONS = ACTION_OPTIONS.map((option) => option.value);

function toQuery(search: ConfigAuditSearch) {
	const dateRange = toDateRange(search);
	return {
		size: PAGE_SIZE,
		entityType: narrowToEnum(search.entityType, ENTITY_TYPES),
		action: narrowToEnum(search.action, ACTIONS),
		actorId: search.actorId,
		from: dateRange?.from ? dayStartInstant(dateRange.from) : undefined,
		to: dateRange?.to ? dayAfterInstant(dateRange.to) : undefined,
	};
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
	const listQuery = useInfiniteQuery({
		...adminListConfigAuditEventsInfiniteOptions({ query: toQuery(search) }),
		...springPageParams,
	});

	return (
		<ConfigAuditView
			search={search}
			onSearchChange={onSearchChange}
			listQuery={listQuery}
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
	const listQuery = useInfiniteQuery({
		...listWorkspaceConfigAuditEventsInfiniteOptions({
			path: { workspaceSlug },
			query: toQuery(search),
		}),
		...springPageParams,
	});

	return <ConfigAuditView search={search} onSearchChange={onSearchChange} listQuery={listQuery} />;
}

type ConfigAuditListQuery = UseInfiniteQueryResult<
	InfiniteData<PageResponseDtoConfigAuditEntryView>
>;

function ConfigAuditView({
	search,
	onSearchChange,
	listQuery,
	showWorkspace = false,
	resolveWorkspaceName,
}: ConfigAuditPanelProps & {
	listQuery: ConfigAuditListQuery;
	showWorkspace?: boolean;
}) {
	const dateRange = toDateRange(search);
	const entries: ConfigAuditEntryView[] = dedupeById(
		listQuery.data?.pages.flatMap((p) => p.content ?? []) ?? [],
	);
	const total = listQuery.data?.pages[0]?.totalElements;
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
				entries={entries}
				isLoading={listQuery.isLoading}
				isError={listQuery.isError}
				hasFilter={hasAppliedFilter}
				onResetFilters={reset}
				hasNextPage={listQuery.hasNextPage}
				isFetchingNextPage={listQuery.isFetchingNextPage}
				onLoadMore={() => void listQuery.fetchNextPage()}
				onRetry={() => void listQuery.refetch()}
				onFilterActor={(actorId) => onSearchChange({ actorId })}
				showWorkspace={showWorkspace}
				resolveWorkspaceName={resolveWorkspaceName}
			/>
		</div>
	);
}
