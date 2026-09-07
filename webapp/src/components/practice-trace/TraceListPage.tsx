import { Link } from "@tanstack/react-router";
import { RadarIcon } from "lucide-react";

import type { ListTracedArtifactsResponse } from "@/api/types.gen";
import { ReviewResultsSkeleton } from "@/components/admin/practice-reviews/ReviewResultsSkeleton";
import { FilterToolbar } from "@/components/common/FilterToolbar";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { ResultCount } from "@/components/common/ResultCount";
import { TablePagination } from "@/components/common/TablePagination";
import { PageHeader } from "@/components/core/PageHeader";
import {
	Empty,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import { ItemGroup } from "@/components/ui/item";
import { ARTIFACT_KIND_VALUES, artifactKindPluralLabel } from "@/lib/artifact-kinds";

import type { TraceSearch } from "./trace-search";
import { TracedArtifactRow } from "./TracedArtifactRow";
import { TraceKindFilter } from "./TraceKindFilter";

/**
 * Shared with the route that asks for the page, so the skeleton shows the number of rows that are
 * about to arrive rather than a count of its own.
 */
export const TRACE_PAGE_SIZE = 20;

export interface TraceListPageProps {
	workspaceSlug: string;
	search: TraceSearch;
	onSearchChange: (patch: Partial<TraceSearch>) => void;
	artifacts: ListTracedArtifactsResponse | undefined;
	isLoading: boolean;
	error: unknown;
	onRetry: () => void;
}

/** Every piece of work this workspace recorded anything about, including the unreviewed. */
export function TraceListPage({
	workspaceSlug,
	search,
	onSearchChange,
	artifacts,
	isLoading,
	error,
	onRetry,
}: TraceListPageProps) {
	const page = search.page ?? 0;
	const rows = artifacts?.content ?? [];
	// No endpoint enumerates the kinds, so the choices are the ones this build knows plus any the
	// page shows — and always the active filter, so a filter arriving by link can be seen and cleared.
	const kinds = [
		...new Set([
			...ARTIFACT_KIND_VALUES,
			...rows.map((artifact) => artifact.artifactKind),
			...(search.kind ? [search.kind] : []),
		]),
	];
	const hasFilter = Boolean(search.kind);

	return (
		<div className="min-w-0 space-y-6">
			<PageHeader
				icon={<RadarIcon />}
				title="Review activity"
				description="Every piece of work recorded in this workspace, and what each practice observed about it — including the practices that stayed quiet, and why."
			/>
			<section aria-label="Recorded work" className="space-y-4">
				<FilterToolbar
					hasFilter={hasFilter}
					onReset={() => onSearchChange({ kind: undefined, page: undefined })}
					actions={
						<ResultCount
							total={artifacts?.page?.totalElements}
							noun={["piece of work", "pieces of work"]}
							hasFilter={hasFilter}
						/>
					}
				>
					<TraceKindFilter
						kinds={kinds}
						value={search.kind}
						onChange={(kind) => onSearchChange({ kind, page: undefined })}
					/>
				</FilterToolbar>

				{error ? (
					<QueryErrorAlert error={error} title="Couldn't load review activity" onRetry={onRetry} />
				) : isLoading ? (
					<ReviewResultsSkeleton label="Loading review activity" rows={TRACE_PAGE_SIZE} />
				) : rows.length === 0 ? (
					<Empty className="border">
						<EmptyHeader>
							<EmptyMedia variant="icon">
								<RadarIcon />
							</EmptyMedia>
							<EmptyTitle>
								{hasFilter
									? `No ${artifactKindPluralLabel(search.kind).toLowerCase()} recorded yet`
									: "Nothing has been recorded here yet"}
							</EmptyTitle>
							<EmptyDescription>
								{hasFilter
									? "Switch back to all work to see everything this workspace has recorded."
									: "As soon as this workspace is connected to a repository or a chat, its pull requests, issues and threads appear here as they sync — including the ones no practice had anything to say about."}
							</EmptyDescription>
						</EmptyHeader>
					</Empty>
				) : (
					<ItemGroup>
						{rows.map((artifact) => (
							<TracedArtifactRow
								key={`${artifact.artifactKind}:${artifact.artifactId}`}
								workspaceSlug={workspaceSlug}
								artifact={artifact}
							/>
						))}
					</ItemGroup>
				)}

				<TablePagination
					page={artifacts?.page?.number ?? page}
					totalPages={artifacts?.page?.totalPages ?? 0}
					renderPageLink={(target, props) => (
						<Link
							{...props}
							to="/w/$workspaceSlug/reviews"
							params={{ workspaceSlug }}
							search={(previous) => ({ ...previous, page: target === 0 ? undefined : target })}
						/>
					)}
				/>
			</section>
		</div>
	);
}
