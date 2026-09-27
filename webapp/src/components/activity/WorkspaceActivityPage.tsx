import { Building2 } from "lucide-react";

import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import { Section } from "@/components/layout/Section";
import {
	Select,
	SelectContent,
	SelectItem,
	SelectTrigger,
	SelectValue,
} from "@/components/ui/select";
import type { ProviderType } from "@/lib/provider/provider-terms";

import type { ActivityOverviewState } from "./activity-buckets";
import { ACTIVITY_RANGE_DEFS, type ActivityRange } from "./activity-range";
import { ActivityTiles } from "./ActivityTiles";
import { ActivityWorkLog, type ActivityWorkLogState } from "./ActivityWorkLog";
import { CopyMarkdownButton } from "./CopyMarkdownButton";
import { type MemberActivityState, MemberActivityTable } from "./MemberActivityTable";
import { RangeControls } from "./RangeControls";

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
	overview: ActivityOverviewState;
	members: MemberActivityState;
	timeline: ActivityWorkLogState;
}

const EVERYONE = "everyone";

/**
 * Everyone's activity, or one team's, in the parts of your own Activity page at a wider scope: what
 * the range adds up to, who is carrying what by name, and the timeline of the work.
 */
export function WorkspaceActivityPage({
	providerType,
	range,
	onRangeChange,
	teams,
	teamId,
	onTeamChange,
	overview,
	members,
	timeline,
}: WorkspaceActivityPageProps) {
	const offered = teams.status === "ready" ? teams.teams : [];
	return (
		<PageLayout className="space-y-8">
			<PageHeader
				icon={<Building2 />}
				title="Workspace activity"
				actions={
					offered.length > 0 ? (
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
							<SelectTrigger className="w-full sm:w-56" aria-label="Team">
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
					) : undefined
				}
			/>
			{teams.status === "error" && (
				<QueryErrorAlert error={teams.error} title="Couldn't load teams" onRetry={teams.onRetry} />
			)}
			<Section
				size="lg"
				title={ACTIVITY_RANGE_DEFS[range].label}
				actions={
					<RangeControls
						range={range}
						onRangeChange={onRangeChange}
						updating={
							(overview.status === "ready" && overview.stale) ||
							(members.status === "ready" && members.stale) ||
							(timeline.status === "ready" && timeline.stale)
						}
					/>
				}
			>
				<ActivityTiles state={overview} providerType={providerType} />
			</Section>
			<Section size="lg" title="Members">
				<MemberActivityTable state={members} providerType={providerType} />
			</Section>
			<Section
				size="lg"
				title="Timeline"
				actions={
					timeline.status === "ready" && timeline.items.length > 0 ? (
						<CopyMarkdownButton onCopy={timeline.onCopy} />
					) : undefined
				}
			>
				<ActivityWorkLog
					state={timeline}
					providerType={providerType}
					subject={{ people: "several" }}
				/>
			</Section>
		</PageLayout>
	);
}
