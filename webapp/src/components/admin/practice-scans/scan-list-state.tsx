import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { Button } from "@/components/ui/button";

import type { RefreshFailure } from "@/runtime/tanstack-query/infinite-list";

export type ScanListState<TEntry> =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| {
			status: "ready";
			entries: readonly TEntry[];
			refreshFailure?: RefreshFailure;
			hasNext: boolean;
			loadingMore: boolean;
			/** Why the last "Show more" read failed. The rows already shown stay. */
			loadMoreError?: unknown;
			onLoadMore: () => void;
	  };

export function ScanListMore({
	hasNext,
	loadingMore,
	loadMoreError,
	onLoadMore,
	noun,
}: {
	hasNext: boolean;
	loadingMore: boolean;
	loadMoreError?: unknown;
	onLoadMore: () => void;
	noun: string;
}) {
	if (!hasNext) {
		return null;
	}
	return (
		<div className="flex flex-col items-center gap-3">
			{/* The button below repeats the read, so the alert offers no Retry of its own. */}
			{loadMoreError !== undefined && !loadingMore && (
				<QueryErrorAlert
					error={loadMoreError}
					title={`We could not load more ${noun}`}
					className="w-full"
				/>
			)}
			<Button pending={loadingMore} type="button" variant="outline" size="sm" onClick={onLoadMore}>
				{loadingMore ? "Loading…" : `Show more ${noun}`}
			</Button>
		</div>
	);
}
