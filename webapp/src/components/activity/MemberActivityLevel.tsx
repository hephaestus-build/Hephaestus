import type { UserInfo } from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import { DetailDrawerHeader } from "@/components/layout/detail-drawer/DetailDrawerHeader";
import { DetailPath, type LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import { Section } from "@/components/layout/Section";
import { DrawerBody, DrawerDescription, DrawerTitle } from "@/components/ui/drawer";
import { getProviderTerms, type ProviderType } from "@/lib/provider/provider-terms";
import { hasText } from "@/lib/text";

import type { ActivityOverviewState } from "./activity-buckets";
import { ACTIVITY_RANGE_DEFS, type ActivityRange } from "./activity-range";
import { ActivityTiles } from "./ActivityTiles";
import { ActivityWorkLog, type ActivityWorkLogState } from "./ActivityWorkLog";
import { CopyMarkdownButton } from "./CopyMarkdownButton";
import { MemberAvatar } from "./MemberAvatar";
import { OpenWorkSections, type OpenWorkState } from "./OpenWorkSections";

export interface MemberActivityLevelProps {
	nested?: boolean;
	path: LevelPath;
	login: string;
	/** The member as the member list carries them; the login stands in until it arrives. */
	user?: UserInfo;
	providerType: ProviderType;
	range: ActivityRange;
	openWork: OpenWorkState;
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
			<DetailDrawerHeader nested={nested}>
				<div className="flex min-w-0 flex-1 flex-col gap-2">
					<DetailPath {...path} current={name} />
					<div className="flex items-center gap-3">
						{user && <MemberAvatar user={user} size="lg" />}
						<div className="min-w-0">
							<DrawerTitle className="text-2xl font-semibold tracking-tight break-words">
								{name}
							</DrawerTitle>
							<DrawerDescription>
								{user !== undefined && hasText(user.htmlUrl) ? (
									<InlineLink href={user.htmlUrl} external>
										{login} on {getProviderTerms(providerType).displayName}
									</InlineLink>
								) : (
									login
								)}
							</DrawerDescription>
						</div>
					</div>
				</div>
			</DetailDrawerHeader>
			<DrawerBody className="flex flex-col gap-8 pt-2">
				<OpenWorkSections state={openWork} providerType={providerType} perspective="member" />
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
