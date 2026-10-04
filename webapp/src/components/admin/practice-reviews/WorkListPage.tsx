import { Link } from "@tanstack/react-router";
import { RadarIcon } from "lucide-react";
import type { ReactNode } from "react";

import type { ListTracedArtifactsResponse } from "@/api/types.gen";
import { FilterToolbar } from "@/components/common/FilterToolbar";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { ResultCount } from "@/components/common/ResultCount";
import { TablePagination } from "@/components/common/TablePagination";
import { TraceKindFilter } from "@/components/practice-trace/TraceKindFilter";
import { Button } from "@/components/ui/button";
import {
	Empty,
	EmptyContent,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import { artifactKindNoun } from "@/lib/artifact-kinds";
import { hasText } from "@/lib/text";

import { REVIEW_PAGE_SIZE, type WorkSearch } from "./review-search";
import { ReviewResultsSkeleton } from "./ReviewResultsSkeleton";
import { ReviewRowList } from "./ReviewRow";
import { WorkRow } from "./WorkRow";

export interface WorkListPageProps {
	workspaceSlug: string;
	search: WorkSearch;
	onSearchChange: (patch: Partial<WorkSearch>) => void;
	/** The page of work the current search asked for. Absent until the first answer arrives. */
	work: ListTracedArtifactsResponse | undefined;
	isLoading: boolean;
	error: unknown;
	onRetry: () => void;
}

/**
 * Every piece of work this workspace recorded anything about, including the work no review ever
 * started on — the list to open when a pull request was expected to be reviewed and was not.
 */
export function WorkListPage({
	workspaceSlug,
	search,
	onSearchChange,
	work,
	isLoading,
	error,
	onRetry,
}: WorkListPageProps) {
	const rows = work?.content ?? [];
	const hasFilter = hasText(search.kind);
	// The toolbar's Reset and the empty state's button are one action, not two copies of it.
	const reset = () => onSearchChange({ kind: undefined });
	let results: ReactNode;
	if (error != null) {
		results = <QueryErrorAlert error={error} title="Could not load the work" onRetry={onRetry} />;
	} else if (isLoading) {
		results = <ReviewResultsSkeleton label="Loading work" rows={REVIEW_PAGE_SIZE} />;
	} else if (rows.length === 0) {
		results = (
			<Empty variant="outlined">
				<EmptyHeader>
					<EmptyMedia variant="icon">
						<RadarIcon />
					</EmptyMedia>
					<EmptyTitle>
						{hasFilter
							? `No ${artifactKindNoun(search.kind, 2)} recorded yet`
							: "Nothing has been recorded yet"}
					</EmptyTitle>
					<EmptyDescription>
						{hasFilter
							? "Other kinds of work may be recorded. Show all work to see them."
							: "Pull requests, issues, conversations and documents appear here after they sync from a connected integration. That includes work that no practice had anything to say about."}
					</EmptyDescription>
				</EmptyHeader>
				{hasFilter && (
					<EmptyContent>
						<Button variant="outline" size="sm" onClick={reset}>
							Show all work
						</Button>
					</EmptyContent>
				)}
			</Empty>
		);
	} else {
		results = (
			<ReviewRowList label="Work, most recent first">
				{rows.map((row) => (
					<WorkRow key={`${row.artifactKind}:${row.artifactId}`} work={row} />
				))}
			</ReviewRowList>
		);
	}

	return (
		<section aria-label="Work" className="space-y-4">
			<FilterToolbar
				hasFilter={hasFilter}
				onReset={reset}
				actions={
					<ResultCount
						total={work?.page?.totalElements}
						noun={["piece of work", "pieces of work"]}
						hasFilter={hasFilter}
					/>
				}
			>
				<TraceKindFilter
					seen={rows.map((row) => row.artifactKind)}
					value={search.kind}
					onChange={(kind) => onSearchChange({ kind })}
				/>
			</FilterToolbar>
			{results}
			<TablePagination
				page={work?.page?.number ?? search.page ?? 0}
				totalPages={work?.page?.totalPages ?? 0}
				renderPageLink={(page, props) => (
					<Link
						{...props}
						to="/w/$workspaceSlug/admin/practices/reviews/work"
						params={{ workspaceSlug }}
						// Spread, as the review list does: page 2 of one kind stays that kind.
						search={{ ...search, page: page === 0 ? undefined : page }}
					/>
				)}
			/>
		</section>
	);
}
