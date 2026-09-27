import type { ReactNode } from "react";

import type { OpenWork, UserInfo } from "@/api/types.gen";
import type { PanelState } from "@/components/common/panel-state";
import { DetailDrawerStack } from "@/components/layout/detail-drawer/DetailDrawerStack";
import { levelPathAt } from "@/components/layout/detail-drawer/level-path";
import type { ProviderType } from "@/lib/provider/provider-terms";

import type { ActivityOverviewState } from "./activity-buckets";
import { ACTIVITY_CATEGORY_DEFS } from "./activity-kind-defs";
import { ACTIVITY_RANGE_DEFS, type ActivityRange } from "./activity-range";
import type { ActivityStackEntry } from "./activity-search";
import { ActivityCategoryLevel } from "./ActivityCategoryLevel";
import type { ActivityWorkLogState, WorkLogSubject } from "./ActivityWorkLog";
import { MemberActivityLevel } from "./MemberActivityLevel";

/** One owner's reads for its levels: the range's overview, and its timeline of the open category. */
export interface ActivityLevelReads {
	overview: ActivityOverviewState;
	categoryWorkLog: ActivityWorkLogState;
}

export interface ActivityDetailDrawerProps {
	stack: ActivityStackEntry[];
	onClose: (depth: number) => void;
	/** The page under the stack, as the first crumb: "Activity" or "Workspace activity". */
	pageLabel: string;
	providerType: ProviderType;
	range: ActivityRange;
	/** Whose the page is, after the range in a category level's description: "Platform". */
	scope?: string;
	/** Whose the page's timelines are, which a category level of the page lists the same way. */
	subject: WorkLogSubject;
	/** The page's own reads, for a category level opened from the page. */
	page: ActivityLevelReads;
	/**
	 * The open member level's reads, on a page whose stack opens members, and the category level
	 * stacked over it.
	 */
	member?: ActivityLevelReads & {
		user?: UserInfo;
		openWork: PanelState<{ openWork: OpenWork }>;
		workLog: ActivityWorkLogState;
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
	subject,
	page,
	member,
}: ActivityDetailDrawerProps) {
	const rangeLabel = ACTIVITY_RANGE_DEFS[range].label;
	const nameOf = (login: string): string =>
		member?.user?.login === login ? member.user.name : login;
	const labelOf = ({ target }: ActivityStackEntry): string =>
		target.kind === "member"
			? nameOf(target.login)
			: ACTIVITY_CATEGORY_DEFS[target.category].label(providerType);
	const pathAt = levelPathAt(stack, { pageLabel, labelOf, onClose });
	const described = (owner: string | undefined) =>
		owner === undefined ? rangeLabel : `${rangeLabel} · ${owner}`;

	return (
		<DetailDrawerStack stack={stack} size="detailWide" onClose={onClose}>
			{(_entry, level): ReactNode => {
				const target = stack[level.depth]?.target;
				switch (target?.kind) {
					case "activity": {
						const owner = target.member;
						if (owner === undefined) {
							return (
								<ActivityCategoryLevel
									nested={level.nested}
									path={pathAt(level.depth)}
									category={target.category}
									range={range}
									description={described(scope)}
									providerType={providerType}
									overview={page.overview}
									workLog={page.categoryWorkLog}
									subject={subject}
								/>
							);
						}
						return (
							member && (
								<ActivityCategoryLevel
									nested={level.nested}
									path={pathAt(level.depth)}
									category={target.category}
									range={range}
									description={described(nameOf(owner))}
									providerType={providerType}
									overview={member.overview}
									workLog={member.categoryWorkLog}
									subject={{ people: "one", login: owner }}
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
									overview={member.overview}
									workLog={member.workLog}
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
