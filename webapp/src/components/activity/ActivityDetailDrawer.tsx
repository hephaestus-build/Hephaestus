import type { ReactNode } from "react";

import type { ActivitySummary, OpenWork, UserInfo } from "@/api/types.gen";
import type { PanelState } from "@/components/common/panel-state";
import { DetailDrawerStack } from "@/components/layout/detail-drawer/DetailDrawerStack";
import { levelPathAt } from "@/components/layout/detail-drawer/level-path";
import type { ProviderType } from "@/lib/provider/provider-terms";

import { ACTIVITY_CATEGORY_DEFS } from "./activity-kind-defs";
import { ACTIVITY_RANGE_DEFS, type ActivityRange } from "./activity-range";
import type { ActivityStackEntry } from "./activity-search";
import { ActivityCategoryLevel } from "./ActivityCategoryLevel";
import type { ActivityTimelineState } from "./ActivityTimeline";
import { MemberActivityLevel } from "./MemberActivityLevel";

export interface ActivityDetailDrawerProps {
	stack: ActivityStackEntry[];
	onClose: (depth: number) => void;
	/** The page under the stack, as the first crumb: "Activity" or "Workspace activity". */
	pageLabel: string;
	providerType: ProviderType;
	range: ActivityRange;
	/**
	 * Whose activity a category level of the page lists, as the words after its title: "by you",
	 * "in this workspace", "in Platform / Payments".
	 */
	scope: string;
	/** The page's category level: its timeline, filtered to the category. */
	categoryTimeline: ActivityTimelineState;
	/**
	 * The open member level's reads, on a page whose stack opens members — which is also what makes
	 * the page's timelines several people's.
	 */
	member?: {
		user?: UserInfo;
		openWork: PanelState<{ openWork: OpenWork }>;
		summary: PanelState<{ summary: ActivitySummary }>;
		timeline: ActivityTimelineState;
		/** The category level stacked over the member's. */
		categoryTimeline: ActivityTimelineState;
	};
}

/** The detail levels over an Activity page, addressed by the route's `detail` stack. */
export function ActivityDetailDrawer({
	stack,
	onClose,
	pageLabel,
	providerType,
	range,
	scope,
	categoryTimeline,
	member,
}: ActivityDetailDrawerProps) {
	const { inSentence } = ACTIVITY_RANGE_DEFS[range];
	const nameOf = (login: string): string =>
		member?.user?.login === login ? member.user.name : login;
	const labelOf = ({ target }: ActivityStackEntry): string =>
		target.kind === "member"
			? nameOf(target.login)
			: ACTIVITY_CATEGORY_DEFS[target.category].label(providerType);
	const pathAt = levelPathAt(stack, { pageLabel, labelOf, onClose });

	return (
		<DetailDrawerStack stack={stack} size="detailWide" onClose={onClose}>
			{(_entry, level): ReactNode => {
				const target = stack[level.depth]?.target;
				switch (target?.kind) {
					case "activity": {
						const title = ACTIVITY_CATEGORY_DEFS[target.category].label(providerType);
						const owner = target.member;
						const empty = {
							title: `Nothing in ${inSentence}`,
							description: "A longer range on the page looks further back.",
						};
						if (owner === undefined) {
							return (
								<ActivityCategoryLevel
									nested={level.nested}
									path={pathAt(level.depth)}
									title={title}
									description={`${title} ${scope} in ${inSentence}.`}
									state={categoryTimeline}
									providerType={providerType}
									people={member ? "several" : "one"}
									empty={empty}
								/>
							);
						}
						return (
							member && (
								<ActivityCategoryLevel
									nested={level.nested}
									path={pathAt(level.depth)}
									title={title}
									description={`${title} by ${nameOf(owner)} in ${inSentence}.`}
									state={member.categoryTimeline}
									providerType={providerType}
									people="one"
									empty={empty}
								/>
							)
						);
					}
					case "member": {
						return (
							member && (
								<MemberActivityLevel
									nested={level.nested}
									path={pathAt(level.depth)}
									login={target.login}
									user={member.user}
									providerType={providerType}
									range={range}
									openWork={member.openWork}
									summary={member.summary}
									timeline={member.timeline}
								/>
							)
						);
					}
					case undefined: {
						return null;
					}
				}
			}}
		</DetailDrawerStack>
	);
}
