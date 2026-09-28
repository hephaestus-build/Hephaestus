import type { OpenWork, UserInfo } from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import type { PanelState } from "@/components/common/panel-state";
import type { LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import { LevelHeader } from "@/components/layout/detail-drawer/LevelHeader";
import { Section } from "@/components/layout/Section";
import { DrawerBody } from "@/components/ui/drawer";
import { getProviderTerms, type ProviderType } from "@/lib/provider/provider-terms";
import { hasText } from "@/lib/text";

import type { ActivityOverviewState } from "./activity-buckets";
import { ACTIVITY_RANGE_DEFS, type ActivityRange } from "./activity-range";
import { ActivityTiles } from "./ActivityTiles";
import { ActivityWorkLog, type ActivityWorkLogState } from "./ActivityWorkLog";
import { CopyMarkdownButton } from "./CopyMarkdownButton";
import { MemberAvatar } from "./MemberAvatar";
import { OpenWorkSections } from "./OpenWorkSections";

export interface MemberActivityLevelProps {
	nested?: boolean;
	path: LevelPath;
	login: string;
	/** The member as the member list carries them; the login stands in until it arrives. */
	user?: UserInfo;
	providerType: ProviderType;
	range: ActivityRange;
	openWork: PanelState<{ openWork: OpenWork }>;
	overview: ActivityOverviewState;
	workLog: ActivityWorkLogState;
}

/**
 * One member's activity over the workspace page, in the parts and order of your own Activity page:
 * what is open, the range's tiles, then the timeline. Their provider profile is one link away.
 */
export function MemberActivityLevel({
	nested,
	path,
	login,
	user,
	providerType,
	range,
	openWork,
	overview,
	workLog,
}: MemberActivityLevelProps) {
	const name = user?.name ?? login;
	return (
		<>
			<LevelHeader
				nested={nested}
				path={path}
				current={name}
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
				<OpenWorkSections
					state={openWork}
					providerType={providerType}
					perspective="member"
					login={login}
				/>
				<Section level={3} size="lg" title={ACTIVITY_RANGE_DEFS[range].label}>
					<ActivityTiles state={overview} providerType={providerType} />
				</Section>
				<Section
					level={3}
					size="lg"
					title="Timeline"
					actions={
						workLog.status === "ready" && workLog.items.length > 0 ? (
							<CopyMarkdownButton onCopy={workLog.onCopy} />
						) : undefined
					}
				>
					<ActivityWorkLog
						state={workLog}
						providerType={providerType}
						subject={{ people: "one", login }}
					/>
				</Section>
			</DrawerBody>
		</>
	);
}
