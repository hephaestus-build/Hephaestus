// The palette this page shares with the practice profile is `webapp/DESIGN.md` § Practice surfaces palette.

import type { ReactNode } from "react";

import type { PracticesAcrossWorkspace, PracticesAcrossWorkspaceTiles } from "@/api/types.gen";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { RangeControls } from "@/components/common/RangeControls";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import { Section } from "@/components/layout/Section";
import {
	ALL_PRACTICE_GROUPS,
	NO_PRACTICE_GROUPS,
} from "@/components/practice-profile/practice-profile-search";
import { GROUP_COLORS_NOTE, getGroupVisual } from "@/components/practice-vocabulary/group-visuals";
import { GroupName } from "@/components/practice-vocabulary/GroupName";
import {
	EARLIER_REVIEWS_COUNTED,
	STANDING_BASIS_DEFS,
} from "@/components/practice-vocabulary/standing-basis-defs";

import { InfoGroups, InfoLines, InfoTip } from "@/components/common/InfoTip";
import {
	type AcrossWorkspaceWindow,
	FIGURES_LOAD_ERROR,
	FIGURES_PLAIN,
	GROUPS_LOAD_ERROR,
	PAGE_INTRO,
	PAGE_INTRO_SHORT,
	tileCohortText,
	WINDOW_OPTIONS,
	windowHeading,
} from "./across-workspace-copy";
import { type ComparisonRow, WorkspaceComparisonTable } from "./WorkspaceComparisonTable";
import { SplitLegend } from "./WorkspaceSplitBar";
import { WorkspaceTiles } from "./WorkspaceTiles";

export interface PracticesAcrossTheWorkspacePageProps {
	/** The splits and the open feedback, which read no window. */
	state: PanelState<{ overview: PracticesAcrossWorkspace }>;
	/** `stale` while the tiles shown are the previous window's and the chosen window's are on their way. */
	tiles: PanelState<{ tiles: PracticesAcrossWorkspaceTiles; stale?: boolean }>;
	window: AcrossWorkspaceWindow;
	onWindowChange: (window: AcrossWorkspaceWindow) => void;
	/** The group whose level is open over the page. */
	openGroupSlug?: string;
	onOpenGroup: (groupSlug: string) => void;
	/** The way to the same list of every group in the reader's Practice profile, under the table. */
	focusFooter?: ReactNode;
}

/**
 * A view of the workspace, not the reader's profile: the reader's figures beside the workspace's
 * typical range over the chosen window, then every practice group's split by current standing,
 * with the reader only as the You bracket. Only the tiles read the window.
 */
export function PracticesAcrossTheWorkspacePage({
	state,
	tiles,
	window,
	onWindowChange,
	openGroupSlug,
	onOpenGroup,
	focusFooter,
}: PracticesAcrossTheWorkspacePageProps) {
	const overview = state.status === "ready" ? state.overview : undefined;
	const windowTiles = tiles.status === "ready" ? tiles.tiles : undefined;
	const stale = tiles.status === "ready" && tiles.stale === true;
	const loading = state.status === "loading" || tiles.status === "loading";
	const earlierReviews =
		overview?.includesEarlierReviews === true || windowTiles?.includesEarlierReviews === true;
	return (
		<PageLayout className="space-y-8">
			{/* The loading line hangs in the gap under the header, so nothing moves when it goes. */}
			<div className="relative">
				<PageHeader
					title="Practices across the workspace"
					description={
						<>
							{PAGE_INTRO_SHORT}{" "}
							<InfoTip label="About this page">
								<InfoLines lines={PAGE_INTRO} />
							</InfoTip>
						</>
					}
				/>
			</div>

			{state.status === "error" ? (
				// Every region reads the overview, so no control on the page can fix this but Retry.
				<QueryErrorAlert error={state.error} title={GROUPS_LOAD_ERROR} onRetry={state.onRetry} />
			) : (
				<>
					{earlierReviews && <EarlierReviewsCounted />}
					<Section
						size="lg"
						title="Your figures"
						description={
							<>
								{windowHeading(window)}
								{windowTiles !== undefined && !stale && ` · ${tileCohortText(windowTiles)}`}{" "}
								<InfoTip label="About your figures">
									<InfoGroups groups={FIGURES_PLAIN} />
								</InfoTip>
							</>
						}
						aria-busy={loading || stale || undefined}
						actions={
							<RangeControls
								options={WINDOW_OPTIONS}
								range={window}
								onRangeChange={onWindowChange}
								updating={stale}
							/>
						}
					>
						{tiles.status === "error" ? (
							<QueryErrorAlert
								error={tiles.error}
								title={FIGURES_LOAD_ERROR}
								onRetry={tiles.onRetry}
							/>
						) : (
							<WorkspaceTiles
								tiles={windowTiles}
								openFeedback={overview?.openFeedback}
								stale={stale}
							/>
						)}
					</Section>

					<Section
						size="lg"
						title={ALL_PRACTICE_GROUPS}
						aria-busy={overview === undefined || undefined}
					>
						<SplitLegend scope="group" />
						<GroupsTable
							overview={overview}
							openGroupSlug={openGroupSlug}
							onOpenGroup={onOpenGroup}
						/>
						{/* Only where colored group names are on the page. */}
						{(overview?.groups.length ?? 0) > 0 && (
							<p data-group-colors-note="" className="text-xs text-muted-foreground">
								{GROUP_COLORS_NOTE}
							</p>
						)}
						{focusFooter}
					</Section>
				</>
			)}
		</PageLayout>
	);
}

const EarlierIcon = STANDING_BASIS_DEFS.EARLIER_REVIEWS.icon;

/** Once on the page while a counted standing rests on earlier reviews, in the words the profile's mark uses. */
function EarlierReviewsCounted() {
	return (
		<div className="flex max-w-prose items-start gap-2 text-sm text-muted-foreground">
			<EarlierIcon aria-hidden className="mt-0.5 size-4 shrink-0" />
			<p>
				<span className="font-medium text-foreground">{EARLIER_REVIEWS_COUNTED.title}.</span>{" "}
				{EARLIER_REVIEWS_COUNTED.description}
			</p>
		</div>
	);
}

function GroupsTable({
	overview,
	openGroupSlug,
	onOpenGroup,
}: {
	overview?: PracticesAcrossWorkspace;
	openGroupSlug?: string;
	onOpenGroup: (groupSlug: string) => void;
}) {
	const rows: ComparisonRow[] = (overview?.groups ?? []).map((group) => {
		const { Icon, pill } = getGroupVisual(group.groupIcon, group.groupColor);
		return {
			key: group.groupSlug,
			name: group.groupName,
			subject: <GroupName name={group.groupName} icon={Icon} pill={pill} />,
			yourStanding: group.yourStanding ?? undefined,
			yourBasis: group.yourBasis ?? undefined,
			split: group.split,
		};
	});
	return (
		<WorkspaceComparisonTable
			aria-label={ALL_PRACTICE_GROUPS}
			subjectHead="Practice group"
			state={overview === undefined ? { status: "loading" } : { status: "ready", rows }}
			openKey={openGroupSlug}
			empty={NO_PRACTICE_GROUPS}
			rowLink={(row) => ({
				text: "Open group",
				onOpen: () => onOpenGroup(row.key),
			})}
		/>
	);
}
