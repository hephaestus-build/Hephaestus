// The palette this page shares with the practice profile is `webapp/AGENTS.md` § Practice surfaces palette.

import type { ReactNode } from "react";

import type { PracticesAcrossWorkspace, PracticesAcrossWorkspaceTiles } from "@/api/types.gen";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import { Section } from "@/components/layout/Section";
import { NO_PRACTICE_GROUPS } from "@/components/practice-profile/practice-profile-search";
import { GROUP_COLORS_NOTE, getGroupVisual } from "@/components/practice-vocabulary/group-visuals";
import { GroupName } from "@/components/practice-vocabulary/GroupName";
import {
	EARLIER_REVIEWS_COUNTED,
	STANDING_BASIS_DEFS,
} from "@/components/practice-vocabulary/standing-basis-defs";

import { InfoLines, InfoTip } from "@/components/common/InfoTip";
import {
	FIGURES_LOAD_ERROR,
	GROUPS_LOAD_ERROR,
	PAGE_INTRO,
	cohortLine,
	PAGE_INTRO_LEAD,
	WHERE_DEVELOPERS_STAND,
	FIGURES_REGION,
} from "./across-workspace-copy";
import { SHARE_LEAD, SHARE_ONLY, SHARE_PLAIN } from "./across-workspace-share";
import { type ComparisonRow, WorkspaceComparisonTable } from "./WorkspaceComparisonTable";
import { SplitLegend } from "./WorkspaceSplitBar";
import { WorkspaceTiles } from "./WorkspaceTiles";

export interface PracticesAcrossTheWorkspacePageProps {
	/** The splits and the open feedback, which read no window. */
	state: PanelState<{ overview: PracticesAcrossWorkspace }>;
	/** The tiles of the one window the page reads (`FIGURES_WINDOW`). */
	tiles: PanelState<{ tiles: PracticesAcrossWorkspaceTiles }>;
	/** The group whose level is open over the page. */
	openGroupSlug?: string;
	onOpenGroup: (groupSlug: string) => void;
	/** The way to the same list of every group in the reader's Practice profile, under the table. */
	focusFooter?: ReactNode;
}

/**
 * A view of the workspace, not the reader's profile: the reader's figures beside the workspace's
 * typical range, each tile naming its basis, then every practice group's split by current standing,
 * with the reader only as the You bracket. Only the tiles read the window.
 */
export function PracticesAcrossTheWorkspacePage({
	state,
	tiles,
	openGroupSlug,
	onOpenGroup,
	focusFooter,
}: PracticesAcrossTheWorkspacePageProps) {
	const overview = state.status === "ready" ? state.overview : undefined;
	const windowTiles = tiles.status === "ready" ? tiles.tiles : undefined;
	const loading = state.status === "loading" || tiles.status === "loading";
	const earlierReviews =
		overview?.includesEarlierReviews === true || windowTiles?.includesEarlierReviews === true;
	return (
		<PageLayout className="space-y-8">
			{/* The loading line hangs in the gap under the header, so nothing moves when it goes. */}
			<div className="relative">
				{/* The intro sits behind the (i) beside the title, so the page starts with its content. */}
				<PageHeader
					title="Practices across the workspace"
					// One developer count for the whole page: every bar counts the same cohort.
					description={cohortSentence(overview)}
					info={
						<InfoTip label="About this page" lead={PAGE_INTRO_LEAD}>
							<InfoLines lines={PAGE_INTRO} />
						</InfoTip>
					}
				/>
			</div>

			{state.status === "error" ? (
				// Every region reads the overview, so no control on the page can fix this but Retry.
				<QueryErrorAlert error={state.error} title={GROUPS_LOAD_ERROR} onRetry={state.onRetry} />
			) : (
				<>
					{earlierReviews && <EarlierReviewsCounted />}
					{/* No heading and no period control (demo, 29.10.2026): each tile names its window or
					    "Open now" and the developers it counts at its foot; the (i) follows the tiles. */}
					<section
						aria-label={FIGURES_REGION}
						data-across-figures=""
						aria-busy={loading || undefined}
						className="flex flex-col gap-2"
					>
						{tiles.status === "error" ? (
							<QueryErrorAlert
								error={tiles.error}
								title={FIGURES_LOAD_ERROR}
								onRetry={tiles.onRetry}
							/>
						) : (
							<WorkspaceTiles tiles={windowTiles} openFeedback={overview?.openFeedback} />
						)}
						<p className="flex items-center gap-1 text-sm text-muted-foreground">
							{/* The button carries the same name, so the words are for the eye only. */}
							<span aria-hidden>{FIGURES_INFO}</span>
							<InfoTip label={FIGURES_INFO} lead={SHARE_LEAD}>
								<p>{SHARE_ONLY}</p>
								<InfoLines lines={SHARE_PLAIN} />
							</InfoTip>
						</p>
					</section>

					<Section
						size="lg"
						title={WHERE_DEVELOPERS_STAND}
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

/** "Each bar counts the same 14 developers with a standing in this workspace, you included." */
function cohortSentence(overview?: PracticesAcrossWorkspace): string | undefined {
	const first = overview?.groups[0];
	if (first === undefined) {
		return undefined;
	}
	return cohortLine(first.split.developers, first.yourStanding != null);
}

/** The figures' (i), under the tiles: its button's name and the words beside it. */
const FIGURES_INFO = "How is this worked out?";

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
			aria-label={WHERE_DEVELOPERS_STAND}
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
