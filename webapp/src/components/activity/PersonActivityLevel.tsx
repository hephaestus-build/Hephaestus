import { HistoryIcon } from "@primer/octicons-react";
import type { ReactElement } from "react";

import type { UserInfo } from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import type { LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import { LevelHeader } from "@/components/layout/detail-drawer/LevelHeader";
import { DrawerBody, DrawerFooter } from "@/components/ui/drawer";
import { getProviderTerms, type ProviderType } from "@/lib/provider/provider-terms";
import { hasText } from "@/lib/text";

import type { ActivityOverviewState } from "./activity-buckets";
import type { ActivityPeriod } from "./activity-period";
import { ActivityEmpty } from "./ActivityEmpty";
import type { ActivityWorkLogState } from "./ActivityWorkLog";
import { MemberAvatar } from "./MemberAvatar";
import { PersonActivitySections } from "./PersonActivitySections";

export interface PersonActivityLevelProps {
	nested?: boolean;
	path: LevelPath;
	login: string;
	/** The person as the people list carries them; the login stands in until it arrives. */
	user?: UserInfo;
	/** Nobody by this login contributed in the period and scope, so there is nothing to count. */
	absent?: boolean;
	providerType: ProviderType;
	period: ActivityPeriod;
	overview: ActivityOverviewState;
	workLog: ActivityWorkLogState;
	/** An admin's way to count the account as automation or as a person, where they may. */
	automationAction?: ReactElement;
}

/**
 * One person's activity over workspace activity: their tiles, repositories and timeline in the
 * page's period and scope. Their provider profile is one link away.
 */
export function PersonActivityLevel({
	nested,
	path,
	login,
	user,
	absent = false,
	providerType,
	period,
	overview,
	workLog,
	automationAction,
}: PersonActivityLevelProps) {
	return (
		<>
			<LevelHeader
				nested={nested}
				path={path}
				current={user?.name ?? login}
				mark={user && <MemberAvatar user={user} size="lg" />}
				description={
					user !== undefined && hasText(user.htmlUrl) ? (
						<p>
							<InlineLink href={user.htmlUrl} external>
								{login} on {getProviderTerms(providerType).displayName}
							</InlineLink>
						</p>
					) : (
						login
					)
				}
			/>
			<DrawerBody className="flex flex-col gap-8 pt-2">
				{absent ? (
					<ActivityEmpty icon={<HistoryIcon />} title="No activity in this range" />
				) : (
					<PersonActivitySections
						level={3}
						period={period}
						overview={overview}
						workLog={workLog}
						providerType={providerType}
						subject={{ people: "one", login }}
					/>
				)}
			</DrawerBody>
			{automationAction && <DrawerFooter>{automationAction}</DrawerFooter>}
		</>
	);
}
