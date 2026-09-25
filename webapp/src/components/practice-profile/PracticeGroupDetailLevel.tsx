import { ClipboardCheckIcon } from "lucide-react";
import { type ReactNode, useState } from "react";

import type { PracticeGroup, PracticeGroupStanding, PracticeStanding } from "@/api/types.gen";
import {
	PracticeTabsList,
	PracticeTabsRail,
	PracticeTabsSkeleton,
	PracticeTabsTrigger,
} from "@/components/common/practice-tabs";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { DetailDrawerHeader } from "@/components/layout/detail-drawer/DetailDrawerHeader";
import { DetailPath, type LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import { Section } from "@/components/layout/Section";
import { count, type FeedbackTextSegment } from "@/components/practice-vocabulary/feedback-text";
import { FeedbackText } from "@/components/practice-vocabulary/FeedbackText";
import { GroupPill } from "@/components/practice-vocabulary/GroupPill";
import {
	HephFeedbackCard,
	type HephFeedbackCardProps,
	HephFeedbackCardSkeleton,
} from "@/components/practice-vocabulary/HephFeedbackCard";
import {
	DEFAULT_PRACTICE_GROUP_SORT,
	sortByStanding,
} from "@/components/practice-vocabulary/practice-group-list-order";
import { formatGroupStandingBasis } from "@/components/practice-vocabulary/practice-trend-presentation";
import { PracticePill } from "@/components/practice-vocabulary/PracticePill";
import {
	PracticeTable,
	PracticeTableRow,
	StandingCell,
	SubjectCell,
} from "@/components/practice-vocabulary/PracticeTable";
import { countPracticeStandings } from "@/components/practice-vocabulary/standing-counts";
import { StandingBadge, TrendNote } from "@/components/practice-vocabulary/StandingBadge";
import { StandingSummaryBox } from "@/components/practice-vocabulary/StandingSummaryBox";
import { WhereYouStand } from "@/components/practice-vocabulary/WhereYouStand";
import { DrawerBody, DrawerTitle } from "@/components/ui/drawer";
import { Separator } from "@/components/ui/separator";
import { Skeleton } from "@/components/ui/skeleton";
import { TableCell } from "@/components/ui/table";
import { Tabs, TabsContent } from "@/components/ui/tabs";
import { hasText } from "@/lib/text";

import { LabelledBlock, NoDescription } from "./practice-profile-blocks";

export interface PracticeGroupDetailLevelProps extends Partial<
	Pick<HephFeedbackCardProps, "holdingUp" | "holdingUpNote" | "reviewedWork">
> {
	nested?: boolean;
	/** Where the level sits, from the drawer. */
	path: LevelPath;
	group?: PracticeGroup;
	standing?: PracticeGroupStanding;
	/** The group's practices, as the standings carry them; left out while they load. */
	practices?: PracticeStanding[];
	/** The group's "Next step", composed from its newest open feedback; left out without one. */
	nextStep?: FeedbackTextSegment[];
	/**
	 * One sentence per practice under its pill, keyed by slug; a practice without one shows only
	 * the pill.
	 */
	practiceSentences?: Record<string, FeedbackTextSegment[] | undefined>;
	/** Opens the practice's own level over this one. */
	onOpenPractice?: (practiceSlug: string) => void;
	/** The practice whose level is open over this one; its row stays marked. */
	openPracticeSlug?: string;
	isLoading: boolean;
	error?: unknown;
	onRetry?: () => void;
}

const practiceCountLabel = (n: number) => `${count(n, "practice", "practices")} in this group`;

/**
 * The group level's tabs: the practices it reviews and the catalog's words on the group. A tab is
 * a view of the level, not a place, so the choice is the level's own state and not the URL's —
 * the practice level's `practiceTab` is the URL's because a feedback card links into one.
 */
const GROUP_TABS = ["practices", "about"] as const;

type GroupTab = (typeof GROUP_TABS)[number];

const TAB_LABELS: Record<GroupTab, string> = {
	practices: "Practices",
	about: "About this group",
};

/**
 * A practice row's shape while the practices load: the badge and chip, the pill and its sentence,
 * the link.
 */
const loadingRow = (
	<>
		<TableCell>
			<div className="flex flex-col items-start gap-1.5">
				<Skeleton className="h-5 w-32 rounded-full" />
				<Skeleton className="h-5 w-40" />
			</div>
		</TableCell>
		<TableCell>
			<div className="flex flex-col items-start gap-2">
				<Skeleton className="h-5 w-56 rounded-full" />
				<Skeleton className="h-5 w-72 max-w-full" />
			</div>
		</TableCell>
		<TableCell>
			<Skeleton className="ml-auto h-5 w-24" />
		</TableCell>
	</>
);

const NO_HELD: NonNullable<PracticeGroupDetailLevelProps["holdingUp"]> = [];
const NO_WORK: NonNullable<PracticeGroupDetailLevelProps["reviewedWork"]> = [];
const NO_SENTENCES: NonNullable<PracticeGroupDetailLevelProps["practiceSentences"]> = {};

/**
 * One practice group as the profile's first detail level: where the reader stands in it, Heph's
 * word on what holds and the next step, and in two tabs the practices it reviews and what the
 * group is about. What the reviews found stays one level deeper, on the practice.
 */
export function PracticeGroupDetailLevel({
	nested,
	path,
	group,
	standing,
	practices,
	holdingUp = NO_HELD,
	holdingUpNote,
	reviewedWork = NO_WORK,
	nextStep,
	practiceSentences = NO_SENTENCES,
	onOpenPractice,
	openPracticeSlug,
	isLoading,
	error,
	onRetry,
}: PracticeGroupDetailLevelProps) {
	const practiceCount = practices?.length ?? 0;
	const counts = countPracticeStandings(practices ?? []);
	// The table's sort and the tab are this level's alone: neither is a place a reader returns to
	// by URL.
	const [sort, setSort] = useState(DEFAULT_PRACTICE_GROUP_SORT);
	const [tab, setTab] = useState<GroupTab>("practices");
	// One table for both states: while loading it draws its rows' shape, so the level does not
	// jump when they land.
	const practicesTable = (
		<PracticeTable
			aria-label="Practices in this group"
			sort={sort}
			onSortChange={setSort}
			subjectHead="Practice"
			rows={sortByStanding(
				practices ?? [],
				sort,
				(practice) => practice.standing,
				(left, right) => left.name.localeCompare(right.name),
			)}
			rowKey={(practice) => practice.slug}
			renderRow={(practice) => (
				<PracticeTableRow
					open={practice.slug === openPracticeSlug}
					link={
						onOpenPractice && {
							text: "Open practice",
							name: practice.name,
							onOpen: () => onOpenPractice(practice.slug),
						}
					}
				>
					<StandingCell
						standing={practice.standing}
						direction={practice.direction}
						support={practice.trendSupport}
						scope="practice"
					/>
					<SubjectCell
						badge={<PracticePill name={practice.name} />}
						sentence={practiceSentences[practice.slug]}
					/>
				</PracticeTableRow>
			)}
			empty={{
				icon: <ClipboardCheckIcon />,
				title: "No practices here yet.",
				description: "Practices appear here once an admin adds them to this group.",
			}}
			isLoading={isLoading}
			loadingRow={loadingRow}
		/>
	);

	let body: ReactNode;
	if (isLoading) {
		body = (
			// Heph's card, the tabs and the practices table, as they will be laid out.
			<>
				<HephFeedbackCardSkeleton />
				<PracticeTabsSkeleton tabs={2} />
				{practicesTable}
			</>
		);
	} else if (error != null) {
		body = (
			<QueryErrorAlert
				error={error}
				title={
					group
						? `Could not load your standing for ${group.name}`
						: "Could not load this practice group"
				}
				onRetry={onRetry}
			/>
		);
	} else if (group) {
		body = (
			<>
				<HephFeedbackCard
					holdingUp={holdingUp}
					holdingUpNote={holdingUpNote}
					reviewedWork={reviewedWork}
					onOpenPractice={onOpenPractice}
					blocks={
						nextStep
							? [
									{
										label: "Next step",
										content: (
											<FeedbackText
												as="p"
												segments={nextStep}
												onOpenPractice={onOpenPractice}
												className="max-w-2xl text-sm"
											/>
										),
									},
								]
							: []
					}
				/>
				<Tabs
					value={tab}
					onValueChange={(next) => {
						const chosen = GROUP_TABS.find((candidate) => candidate === next);
						if (chosen) {
							setTab(chosen);
						}
					}}
					className="gap-4"
				>
					<PracticeTabsRail>
						<PracticeTabsList aria-label="Practice group">
							{GROUP_TABS.map((candidate) => (
								<PracticeTabsTrigger
									key={candidate}
									value={candidate}
									count={candidate === "practices" ? practiceCount : undefined}
								>
									{TAB_LABELS[candidate]}
								</PracticeTabsTrigger>
							))}
						</PracticeTabsList>
					</PracticeTabsRail>
					<TabsContent value="practices" className="min-w-0">
						<Section
							size="lg"
							title="Practices in this group"
							description="Each practice with its standing and trend. Open one for the work behind it."
						>
							{practicesTable}
						</Section>
					</TabsContent>
					<TabsContent value="about" className="min-w-0">
						<div className="flex flex-col gap-6">
							<WhereYouStand
								standing={standing?.standing ?? "NOT_OBSERVED"}
								basis={formatGroupStandingBasis(counts)}
								direction={standing?.direction}
								support={standing?.trendSupport}
								scope="group"
							/>
							<LabelledBlock label="About this group" className="flex flex-col gap-1.5">
								{hasText(group.description) ? (
									<p className="max-w-2xl text-sm">{group.description}</p>
								) : (
									<NoDescription />
								)}
							</LabelledBlock>
						</div>
					</TabsContent>
				</Tabs>
			</>
		);
	} else {
		body = (
			<p className="text-sm text-muted-foreground">
				This practice group does not exist or is not active in this workspace.
			</p>
		);
	}

	return (
		<>
			<DetailDrawerHeader nested={nested}>
				<div className="flex min-w-0 flex-1 basis-56 flex-col gap-2">
					<DetailPath {...path} current="Group" />
					<div className="flex items-center gap-3">
						<GroupPill
							size="lg"
							slug={group?.slug}
							name={group?.name}
							icon={group?.icon}
							color={group?.color}
						/>
						<DrawerTitle className="text-2xl font-semibold tracking-tight break-words">
							{group?.name ?? "Practice group"}
						</DrawerTitle>
					</div>
					{group && (
						<div className="flex flex-wrap items-center gap-3">
							<StandingBadge standing={standing?.standing ?? "NOT_OBSERVED"} scope="group" />
							<TrendNote
								direction={standing?.direction}
								support={standing?.trendSupport}
								scope="group"
							/>
						</div>
					)}
				</div>
				{/* The one second column a header allows beside its title (`webapp/AGENTS.md` § Panel
				    regions): full width under the title below `sm`, beside it from there on. */}
				{group && (isLoading || practiceCount > 0) && (
					<StandingSummaryBox
						label={practiceCountLabel(practiceCount)}
						counts={counts}
						isLoading={isLoading}
						className="w-full sm:w-auto"
					/>
				)}
			</DetailDrawerHeader>
			<DrawerBody className="flex flex-col gap-4 pt-2">
				{/* The line under the header, with room on both sides: the header, the line and Heph's
				    card read as three things. */}
				<Separator className="mb-2" />
				{body}
			</DrawerBody>
		</>
	);
}
