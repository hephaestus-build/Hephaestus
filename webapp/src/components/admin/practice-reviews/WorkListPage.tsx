import { RadarIcon } from "lucide-react";
import type { ReactNode } from "react";

import type { TracedArtifact } from "@/api/types.gen";
import { FilterToolbar } from "@/components/common/FilterToolbar";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { ResultCount } from "@/components/common/ResultCount";
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
import type { PagedListState } from "@/runtime/tanstack-query/infinite-list";

import { REVIEW_PAGE_SIZE, type WorkSearch } from "./review-search";
import { ReviewListEnd } from "./ReviewListEnd";
import { ReviewResultsSkeleton } from "./ReviewResultsSkeleton";
import { ReviewRowList } from "./ReviewRow";
import { WorkRow } from "./WorkRow";

export interface WorkListPageProps {
	search: WorkSearch;
	onSearchChange: (patch: Partial<WorkSearch>) => void;
	/** The work the current search selects, as far as it is loaded. */
	work: PagedListState<TracedArtifact>;
}

/**
 * Every piece of work this workspace recorded anything about, including the work no review ever
 * started on — the list to open when a pull request was expected to be reviewed and was not.
 */
export function WorkListPage({ search, onSearchChange, work }: WorkListPageProps) {
	const rows = work.status === "ready" ? work.rows : [];
	const hasFilter = hasText(search.kind);
	// The toolbar's Reset and the empty state's button are one action, not two copies of it.
	const reset = () => onSearchChange({ kind: undefined });
	let results: ReactNode;
	if (work.status === "error") {
		results = (
			<QueryErrorAlert
				error={work.error}
				title="We could not load the work"
				onRetry={work.onRetry}
			/>
		);
	} else if (work.status === "loading") {
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
			<>
				<ReviewRowList label="Work, most recent first">
					{rows.map((row) => (
						<WorkRow key={`${row.artifactKind}:${row.artifactId}`} work={row} />
					))}
				</ReviewRowList>
				<ReviewListEnd {...work} noun="work" />
			</>
		);
	}

	return (
		<section aria-label="Work" className="space-y-4">
			<FilterToolbar
				hasFilter={hasFilter}
				onReset={reset}
				actions={
					<ResultCount
						total={work.status === "ready" ? work.total : undefined}
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
		</section>
	);
}
