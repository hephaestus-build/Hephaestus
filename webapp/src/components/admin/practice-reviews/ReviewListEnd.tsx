import { InfiniteListEnd } from "@/components/profile/InfiniteListEnd";
import type { MorePages } from "@/runtime/tanstack-query/infinite-list";

import { ReviewMoreRowsSkeleton } from "./ReviewResultsSkeleton";

export interface ReviewListEndProps extends MorePages {
	/** What the list holds, in the plural: "reviews", "feedback". */
	noun: string;
}

/** The end of an admin review list: the next page loads as it scrolls into view. */
export function ReviewListEnd({ noun, ...more }: ReviewListEndProps) {
	return (
		<InfiniteListEnd
			{...more}
			moreLabel={`Show more ${noun}`}
			failedLabel={`We could not load more ${noun}.`}
			loadingRow={<ReviewMoreRowsSkeleton />}
		/>
	);
}
