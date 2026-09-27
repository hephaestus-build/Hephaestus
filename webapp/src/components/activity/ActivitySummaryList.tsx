import { cn } from "cn";
import type { ActivitySummary } from "@/api/types.gen";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { Item, ItemContent, ItemMedia } from "@/components/ui/item";
import { Skeleton } from "@/components/ui/skeleton";
import type { ProviderType } from "@/lib/provider/provider-terms";

import {
	ACTIVITY_CATEGORIES,
	ACTIVITY_CATEGORY_DEFS,
	categorySentence,
	categoryTotal,
} from "./activity-kind-defs";
import { ACTIVITY_RANGE_DEFS, type ActivityRange } from "./activity-range";
import { categoryLevel } from "./activity-search";
import { ActivityLevelRow } from "./ActivityLevelRow";

export interface ActivitySummaryListProps {
	state: PanelState<{ summary: ActivitySummary }>;
	providerType: ProviderType;
	range: ActivityRange;
}

/**
 * One row per kind of work with what happened in the range, each opening the activity behind it. The
 * level stacks over whatever holds the list, so in a member's level it lists that member's activity.
 */
export function ActivitySummaryList({ state, providerType, range }: ActivitySummaryListProps) {
	if (state.status === "error") {
		return (
			<QueryErrorAlert
				error={state.error}
				title="Couldn't load the summary"
				onRetry={state.onRetry}
			/>
		);
	}
	if (state.status === "loading") {
		return (
			<div className="overflow-hidden rounded-xl border bg-card" aria-busy="true">
				<span className="sr-only">Loading the summary</span>
				<ul aria-hidden>
					{ACTIVITY_CATEGORIES.map((category) => (
						<Item key={category} render={<li />} variant="row">
							<Skeleton className="size-4" />
							<ItemContent className="gap-2">
								<Skeleton className="h-4 w-28" />
								<Skeleton className="h-3.5 w-48" />
							</ItemContent>
							<Skeleton className="h-7 w-8" />
						</Item>
					))}
				</ul>
			</div>
		);
	}
	return (
		<ul className="overflow-hidden rounded-xl border bg-card">
			{ACTIVITY_CATEGORIES.map((category) => {
				const { label, icon: Icon } = ACTIVITY_CATEGORY_DEFS[category];
				const total = categoryTotal(category, state.summary);
				// A row with nothing behind it opens nothing: an empty list one press away is a dead end.
				const opens = total > 0;
				return (
					<ActivityLevelRow
						key={category}
						opens={opens ? categoryLevel(category) : undefined}
						media={
							<ItemMedia variant="icon" className="text-muted-foreground">
								<Icon aria-hidden />
							</ItemMedia>
						}
						title={label(providerType)}
						description={
							opens
								? categorySentence(category, state.summary)
								: `None in ${ACTIVITY_RANGE_DEFS[range].inSentence}`
						}
						figure={
							<span
								className={cn(
									"text-2xl font-semibold tabular-nums",
									!opens && "text-muted-foreground",
								)}
							>
								{total}
							</span>
						}
					/>
				);
			})}
		</ul>
	);
}
