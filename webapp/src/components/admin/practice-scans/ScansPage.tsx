import { Link } from "@tanstack/react-router";
import { FileSearchIcon } from "lucide-react";
import type { ReactNode } from "react";

import type { ScanSummary } from "@/api/types.gen";
import { EmptyState } from "@/components/common/EmptyState";
import { type FilterOption, FilterToggle } from "@/components/common/FilterToggle";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { RemovableToken } from "@/components/common/RemovableToken";
import { statusValues } from "@/components/common/status-def";
import { TablePagination } from "@/components/common/TablePagination";
import { TableRowsSkeleton } from "@/components/common/TableRowsSkeleton";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import {
	REVIEW_STATUS_DEFS,
	type KnownReviewStatus,
} from "@/components/practice-vocabulary/review-status-defs";
import { Button, buttonVariants } from "@/components/ui/button";
import { Table, TableHeader, TableRow } from "@/components/ui/table";

import type { RefreshFailure } from "@/runtime/tanstack-query/infinite-list";

import { repositoryName } from "./scan-display";
import { ScanColumnHeads, SCAN_SKELETON_COLUMNS, ScanRows } from "./ScanRows";
import type { ScanListFilters } from "./scans-search";

export type ScansListState =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| {
			status: "ready";
			scans: readonly ScanSummary[];
			refreshFailure?: RefreshFailure;
			page: number;
			totalPages: number;
			onPageChange: (page: number) => void;
	  };

export interface ScansPageProps {
	workspaceSlug: string;
	scans: ScansListState;
	filters: ScanListFilters;
	onFiltersChange: (next: ScanListFilters) => void;
}

type StatusFilter = KnownReviewStatus | "ALL";

const STATUS_OPTIONS: readonly FilterOption<StatusFilter>[] = [
	{ value: "ALL", label: "All" },
	...statusValues(REVIEW_STATUS_DEFS).map((status) => ({
		value: status,
		label: REVIEW_STATUS_DEFS[status].label,
	})),
];

const NO_FILTERS: ScanListFilters = { status: undefined, repositoryItemId: undefined };

const LIST_LABEL = "Scans, newest first";

// A failed read supplies no counts; show the error rather than zero-valued scan rows.
export function ScansPage({ workspaceSlug, scans, filters, onFiltersChange }: ScansPageProps) {
	const filtered = filters.status !== undefined || filters.repositoryItemId !== undefined;
	let body: ReactNode;
	if (scans.status === "error") {
		body = (
			<QueryErrorAlert
				error={scans.error}
				title="We could not load scans"
				onRetry={scans.onRetry}
			/>
		);
	} else if (scans.status === "ready" && scans.scans.length === 0 && filtered) {
		body = (
			<EmptyState
				icon={<FileSearchIcon />}
				title="No scans match these filters"
				headingLevel={2}
				action={
					<Button variant="outline" onClick={() => onFiltersChange(NO_FILTERS)}>
						Clear filters
					</Button>
				}
			/>
		);
	} else if (scans.status === "ready" && scans.scans.length === 0) {
		body = (
			<EmptyState
				icon={<FileSearchIcon />}
				title="No scans yet"
				headingLevel={2}
				description="Scans appear here after a repository schedule starts one. Set up a repository schedule in review settings."
				action={
					<Link
						to="/w/$workspaceSlug/admin/practices/review"
						params={{ workspaceSlug }}
						search={{ section: "automated-review" }}
						hash="schedule-heading"
						className={buttonVariants({ variant: "outline" })}
					>
						Set up a repository schedule
					</Link>
				}
			/>
		);
	} else {
		body = (
			<div className="space-y-4">
				<Table bordered aria-label={LIST_LABEL} aria-busy={scans.status === "loading"}>
					<TableHeader>
						<TableRow>
							<ScanColumnHeads />
						</TableRow>
					</TableHeader>
					{scans.status === "loading" ? (
						<TableRowsSkeleton columns={SCAN_SKELETON_COLUMNS} rows={5} />
					) : (
						<ScanRows workspaceSlug={workspaceSlug} scans={scans.scans} filters={filters} />
					)}
				</Table>
				{scans.status === "ready" && (
					<TablePagination
						page={scans.page}
						totalPages={scans.totalPages}
						onPageChange={scans.onPageChange}
					/>
				)}
			</div>
		);
	}
	return (
		<PageLayout>
			<PageHeader
				icon={<FileSearchIcon />}
				title="Scans"
				description="A scan reviews every file of a repository’s default branch against the practices you include in scans. It sends no feedback to anyone."
			/>
			{scans.status === "ready" && scans.refreshFailure !== undefined && (
				<QueryErrorAlert title="Scans may be out of date" {...scans.refreshFailure} />
			)}
			{/* A refused or failed read lists nothing, so it offers nothing to filter. */}
			{scans.status !== "error" && (
				<div className="flex flex-wrap items-center gap-3">
					<FilterToggle
						label="Status"
						options={STATUS_OPTIONS}
						value={filters.status ?? "ALL"}
						onChange={(next) =>
							onFiltersChange({ ...filters, status: next === "ALL" ? undefined : next })
						}
					/>
					{filters.repositoryItemId !== undefined && (
						<RemovableToken
							label={`Repository: ${filteredRepositoryName(scans, filters.repositoryItemId)}`}
							removeLabel="Show scans of every repository"
							onRemove={() => onFiltersChange({ ...filters, repositoryItemId: undefined })}
						/>
					)}
				</div>
			)}
			{body}
		</PageLayout>
	);
}

/** The filtered repository by the name its own scans carry; by its item ID when none is listed. */
function filteredRepositoryName(scans: ScansListState, repositoryItemId: string): string {
	const listed =
		scans.status === "ready"
			? scans.scans.find((scan) => scan.repository.id === repositoryItemId)
			: undefined;
	return listed === undefined ? `item ${repositoryItemId}` : repositoryName(listed.repository);
}
