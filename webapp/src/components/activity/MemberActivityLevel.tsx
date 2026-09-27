import type { ActivitySummary, OpenWork, UserInfo } from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import type { PanelState } from "@/components/common/panel-state";
import { DetailDrawerHeader } from "@/components/layout/detail-drawer/DetailDrawerHeader";
import { DetailPath, type LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import { Section } from "@/components/layout/Section";
import { DrawerBody, DrawerDescription, DrawerTitle } from "@/components/ui/drawer";
import { ARTIFACT_KIND, artifactKindNoun } from "@/lib/artifact-kinds";
import { getProviderTerms, type ProviderType } from "@/lib/provider/provider-terms";
import { hasText } from "@/lib/text";

import { ACTIVITY_RANGE_DEFS, type ActivityRange } from "./activity-range";
import { ActivitySummaryList } from "./ActivitySummaryList";
import { ActivityTimeline, type ActivityTimelineState } from "./ActivityTimeline";
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
	summary: PanelState<{ summary: ActivitySummary }>;
	timeline: ActivityTimelineState;
}

/**
 * One member's activity over the workspace page, in the same order as your own: what is open, the summary,
 * then what happened. Their provider profile is one link away for everything else.
 */
export function MemberActivityLevel({
	nested,
	path,
	login,
	user,
	providerType,
	range,
	openWork,
	summary,
	timeline,
}: MemberActivityLevelProps) {
	const name = user?.name ?? login;
	const { label, inSentence } = ACTIVITY_RANGE_DEFS[range];
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
				<OpenWorkSections
					state={openWork}
					providerType={providerType}
					perspective="member"
					login={login}
				/>
				<Section level={3} size="md" title="Summary" description={label}>
					<ActivitySummaryList state={summary} providerType={providerType} range={range} />
				</Section>
				<Section level={3} size="md" title="Recent activity">
					<ActivityTimeline
						state={timeline}
						providerType={providerType}
						people="one"
						empty={{
							title: `Nothing in ${inSentence}`,
							description: `Their ${artifactKindNoun(ARTIFACT_KIND.pullRequest, 2, providerType)}, reviews, issues and comments show up here.`,
						}}
					/>
				</Section>
			</DrawerBody>
		</>
	);
}
