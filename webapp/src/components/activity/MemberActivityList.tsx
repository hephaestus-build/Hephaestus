import { UsersIcon } from "lucide-react";

import type { MemberActivity } from "@/api/types.gen";
import { MetaRow } from "@/components/common/MetaRow";
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
	hasActivity,
} from "./activity-kind-defs";
import { ACTIVITY_RANGE_DEFS, type ActivityRange } from "./activity-range";
import { memberLevel } from "./activity-search";
import { ActivityEmpty } from "./ActivityEmpty";
import { ActivityLevelRow } from "./ActivityLevelRow";
import { MemberAvatar } from "./MemberAvatar";

export interface MemberActivityListProps {
	state: PanelState<{ members: MemberActivity[] }>;
	providerType: ProviderType;
	range: ActivityRange;
}

const SKELETON_ROWS = 6;

/** Everyone's activity by name, in the order the server lists them. */
export function MemberActivityList({ state, providerType, range }: MemberActivityListProps) {
	if (state.status === "error") {
		return (
			<QueryErrorAlert error={state.error} title="Couldn't load members" onRetry={state.onRetry} />
		);
	}
	if (state.status === "loading") {
		return (
			<div className="overflow-hidden rounded-xl border bg-card" aria-busy="true">
				<span className="sr-only">Loading members</span>
				<ul aria-hidden>
					{Array.from({ length: SKELETON_ROWS }, (_, index) => (
						<Item key={index} render={<li />} variant="row">
							<Skeleton className="size-8 rounded-full" />
							<ItemContent className="gap-2">
								<Skeleton className="h-4 w-32" />
								<Skeleton className="h-3.5 w-56" />
							</ItemContent>
						</Item>
					))}
				</ul>
			</div>
		);
	}
	if (state.members.length === 0) {
		return (
			<ActivityEmpty
				icon={<UsersIcon />}
				title="No members here"
				description="Members show up here with what they worked on in the range."
			/>
		);
	}
	return (
		<ul className="overflow-hidden rounded-xl border bg-card">
			{state.members.map(({ user, summary }) => (
				<ActivityLevelRow
					key={user.id}
					opens={memberLevel(user.login)}
					media={
						<ItemMedia>
							<MemberAvatar user={user} />
						</ItemMedia>
					}
					title={user.name}
					titleAside={
						<span className="truncate font-normal text-muted-foreground">{user.login}</span>
					}
					description={
						hasActivity(summary) ? (
							<MetaRow
								captions={ACTIVITY_CATEGORIES.filter(
									(category) => categoryTotal(category, summary) > 0,
								).map(
									(category) =>
										`${ACTIVITY_CATEGORY_DEFS[category].label(providerType)}: ${categorySentence(category, summary)}`,
								)}
							/>
						) : (
							`No activity in ${ACTIVITY_RANGE_DEFS[range].inSentence}`
						)
					}
				/>
			))}
		</ul>
	);
}
