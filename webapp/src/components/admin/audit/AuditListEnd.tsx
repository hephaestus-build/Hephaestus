import { Button } from "@/components/ui/button";
import type { MorePages } from "@/runtime/tanstack-query/infinite-list";

/**
 * The end of an audit table: the next page on request, and why the last request failed. The rows
 * already read stay above it, and the same press asks again.
 */
export function AuditListEnd({
	list,
	noun,
}: {
	list: Pick<MorePages, "hasMore" | "isLoadingMore" | "loadMoreError" | "onLoadMore">;
	/** What the rows are, in the plural: "events". */
	noun: string;
}) {
	const failed = list.loadMoreError != null;
	if (!list.hasMore && !failed) {
		return null;
	}
	return (
		<div className="flex flex-col items-center gap-2">
			{failed && (
				<p role="alert" className="text-sm text-muted-foreground">
					We could not load more {noun}.
				</p>
			)}
			<Button pending={list.isLoadingMore} variant="outline" onClick={list.onLoadMore}>
				Load more
			</Button>
		</div>
	);
}
