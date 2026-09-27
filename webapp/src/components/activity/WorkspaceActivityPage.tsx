import { Building2 } from "lucide-react";

import type { ActivitySummary, MemberActivity } from "@/api/types.gen";
import { FilterToggle } from "@/components/common/FilterToggle";
import { MetaRow } from "@/components/common/MetaRow";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { PageHeader } from "@/components/layout/PageHeader";
import { Section } from "@/components/layout/Section";
import { count, spell } from "@/components/practice-vocabulary/feedback-text";
import {
	Select,
	SelectContent,
	SelectItem,
	SelectTrigger,
	SelectValue,
} from "@/components/ui/select";
import { ARTIFACT_KIND, artifactKindNoun } from "@/lib/artifact-kinds";
import type { ProviderType } from "@/lib/provider/provider-terms";

import { hasActivity } from "./activity-kind-defs";
import { ACTIVITY_RANGE_DEFS, ACTIVITY_RANGE_OPTIONS, type ActivityRange } from "./activity-range";
import { ActivityPageLayout } from "./ActivityPageLayout";
import { ActivitySummaryList } from "./ActivitySummaryList";
import { ActivityTimeline, type ActivityTimelineState } from "./ActivityTimeline";
import { MemberActivityList } from "./MemberActivityList";

export interface WorkspaceActivityTeam {
	id: number;
	/** The team's path through the teams a member can see, parent first: "Platform / Payments". */
	name: string;
}

export interface WorkspaceActivityPageProps {
	providerType: ProviderType;
	range: ActivityRange;
	onRangeChange: (range: ActivityRange) => void;
	/** The teams shown in workspace activity; the page offers them as a filter when there are any. */
	teams: PanelState<{ teams: WorkspaceActivityTeam[] }>;
	/** The team whose activity is shown; undefined is everyone. */
	teamId?: number;
	onTeamChange: (teamId: number | undefined) => void;
	summary: PanelState<{ summary: ActivitySummary }>;
	members: PanelState<{ members: MemberActivity[] }>;
	timeline: ActivityTimelineState;
}

const EVERYONE = "everyone";

/** Everyone's activity, or one team's: what the range adds up to, who is working on what, and what happened. */
export function WorkspaceActivityPage({
	providerType,
	range,
	onRangeChange,
	teams,
	teamId,
	onTeamChange,
	summary,
	members,
	timeline,
}: WorkspaceActivityPageProps) {
	const { label, inSentence } = ACTIVITY_RANGE_DEFS[range];
	const offered = teams.status === "ready" ? teams.teams : [];
	const team = offered.find((candidate) => candidate.id === teamId);
	return (
		<ActivityPageLayout>
			<PageHeader
				icon={<Building2 />}
				title="Workspace activity"
				description="What everyone in this workspace, or one team, is working on and has done lately, member by member."
			/>
			<div className="flex flex-wrap items-center gap-3">
				{offered.length > 0 && (
					<Select
						items={[
							{ value: EVERYONE, label: "Everyone" },
							...offered.map((candidate) => ({
								value: String(candidate.id),
								label: candidate.name,
							})),
						]}
						value={teamId === undefined ? EVERYONE : String(teamId)}
						onValueChange={(next) =>
							onTeamChange(next === null || next === EVERYONE ? undefined : Number(next))
						}
					>
						<SelectTrigger className="w-full sm:w-64" aria-label="Team">
							<SelectValue />
						</SelectTrigger>
						<SelectContent aria-label="Team">
							<SelectItem value={EVERYONE}>Everyone</SelectItem>
							{offered.map((candidate) => (
								<SelectItem key={candidate.id} value={String(candidate.id)}>
									{candidate.name}
								</SelectItem>
							))}
						</SelectContent>
					</Select>
				)}
				<FilterToggle
					label="Time range"
					options={ACTIVITY_RANGE_OPTIONS}
					value={range}
					onChange={onRangeChange}
				/>
			</div>
			{teams.status === "error" && (
				<QueryErrorAlert error={teams.error} title="Couldn't load teams" onRetry={teams.onRetry} />
			)}
			<Section
				size="lg"
				title="Summary"
				description={
					<MetaRow
						captions={[label, members.status === "ready" && activeMembers(members.members)]}
					/>
				}
			>
				<ActivitySummaryList state={summary} providerType={providerType} range={range} />
			</Section>
			<Section
				size="lg"
				title={team ? `Members of ${team.name}` : "Members"}
				description="Open a member for what they have open and what they did."
			>
				<MemberActivityList state={members} providerType={providerType} range={range} />
			</Section>
			<Section size="lg" title="Recent activity" description={label}>
				<ActivityTimeline
					state={timeline}
					providerType={providerType}
					people="several"
					empty={{
						title: `Nothing in ${inSentence}`,
						description: `Everyone's ${artifactKindNoun(ARTIFACT_KIND.pullRequest, 2, providerType)}, reviews, issues and comments show up here.`,
					}}
				/>
			</Section>
		</ActivityPageLayout>
	);
}

/** "two of three members active", both counts under one number rule. */
function activeMembers(members: MemberActivity[]): string {
	const active = members.filter((member) => hasActivity(member.summary)).length;
	const digits = members.length >= 10;
	return `${spell(active, digits)} of ${count(members.length, "member", "members", digits)} active`;
}
