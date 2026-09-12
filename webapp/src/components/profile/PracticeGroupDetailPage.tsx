import { PulseIcon } from "@primer/octicons-react";
import { cn } from "cn";
import { ArrowLeftIcon, ChevronDownIcon, CircleDashedIcon, InfoIcon } from "lucide-react";
import { useState } from "react";
import type {
	PracticeGroup,
	PracticeGroupReviewObservation,
	PracticeGroupReviewRun,
	PracticeGroupStanding,
	PracticeStanding,
	PracticeTrend,
} from "@/api/types.gen";
import { getGroupVisual } from "@/components/admin/practice-catalog/group-visuals";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { PRACTICE_GROUP_STANDING_DEFS } from "@/components/practice-vocabulary/practice-group-standing-defs";
import { type StatusDef, statusToneClass } from "@/components/practice-vocabulary/status-def";
import { StatusBadge } from "@/components/practice-vocabulary/StatusBadge";
import { Button } from "@/components/ui/button";
import {
	Empty,
	EmptyContent,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import { Skeleton } from "@/components/ui/skeleton";
import { PracticeNextStepCallout } from "./PracticeNextStepCallout";
import { PracticeTrendChip } from "./PracticeTrendChip";
import type { FeedbackResponse, ObservationDetailState } from "./review-runs";
import { ReviewRunTimeline } from "./ReviewRunTimeline";

type PracticeStandingKey = NonNullable<PracticeStanding["standing"]> | "UNMEASURED";
const UNMEASURED_NODE = {
	label: "Not measured yet",
	icon: CircleDashedIcon,
	badgeVariant: "outline",
	description: "No standing was reported for this practice.",
} satisfies StatusDef;

function standingNode(standing: PracticeStandingKey): StatusDef {
	return standing === "UNMEASURED" ? UNMEASURED_NODE : PRACTICE_GROUP_STANDING_DEFS[standing];
}
export type ReviewRunFeedState = PanelState<{
	runs: PracticeGroupReviewRun[];
	hasMore: boolean;
	isLoadingMore: boolean;
	onLoadMore: () => void;
}>;

const EMPTY_FEED: ReviewRunFeedState = {
	status: "ready",
	runs: [],
	hasMore: false,
	isLoadingMore: false,
	onLoadMore: () => undefined,
};
export interface ContributingPractice {
	slug: string;
	name: string;
	whyItMatters?: string;
	whatGoodLooksLike?: string;
	standing?: PracticeStanding["standing"];
	trend?: PracticeTrend;
	nextStep?: string;
}

export interface PracticeGroupDetailPageProps {
	group?: PracticeGroup;
	standing?: PracticeGroupStanding;
	practices?: ContributingPractice[];
	groupTrend?: PracticeTrend;
	selectedPracticeSlug?: string;
	onSelectPractice?: (practiceSlug: string | undefined) => void;
	feed?: ReviewRunFeedState;
	skeletonRows?: number;
	openObservationId?: string;
	observationDetail?: ObservationDetailState;
	onToggleObservation?: (observationId: string) => void;
	onRespond?: (observation: PracticeGroupReviewObservation, response: FeedbackResponse) => void;
	pendingFeedbackId?: string;
	isLoading: boolean;
	error?: unknown;
	onRetry?: () => void;
	onBack?: () => void;
}

interface DetailSectionIntroProps {
	id: string;
	title: string;
	description: string;
}
function DetailSectionIntro({ id, title, description }: DetailSectionIntroProps) {
	return (
		<div className="grid content-start gap-1">
			<h2 id={id} className="text-lg font-semibold leading-6">
				{title}
			</h2>
			<p className="text-sm leading-5 text-muted-foreground">{description}</p>
		</div>
	);
}

export function PracticeGroupDetailPage({
	group,
	standing,
	practices,
	groupTrend,
	selectedPracticeSlug,
	onSelectPractice,
	feed = EMPTY_FEED,
	skeletonRows = 3,
	openObservationId,
	observationDetail,
	onToggleObservation,
	onRespond,
	pendingFeedbackId,
	isLoading,
	error,
	onRetry,
	onBack,
}: PracticeGroupDetailPageProps) {
	const [isGroupDescriptionOpen, setIsGroupDescriptionOpen] = useState(false);
	const [openPracticeInfoSlug, setOpenPracticeInfoSlug] = useState<string>();

	if (isLoading) {
		return (
			<div className="flex flex-col gap-4">
				<Skeleton className="h-10 w-2/3" />
				<Skeleton className="h-48 w-full" />
				<Skeleton className="h-48 w-full" />
			</div>
		);
	}

	if (error) {
		return (
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
	}

	if (!group) {
		return (
			<div className="flex flex-col items-start gap-3">
				<p className="text-sm text-muted-foreground">
					This practice group does not exist or is not active in this workspace.
				</p>
				{onBack && (
					<Button type="button" size="sm" variant="outline" onClick={onBack}>
						<ArrowLeftIcon className="size-3.5" aria-hidden />
						Back to profile
					</Button>
				)}
			</div>
		);
	}

	const groupStanding = standing?.standing ?? "NOT_OBSERVED";
	const badge = PRACTICE_GROUP_STANDING_DEFS[groupStanding];
	const { Icon: GroupIcon, pill: groupPill } = getGroupVisual(group.icon, group.color);
	const selectedPractice = practices?.find((practice) => practice.slug === selectedPracticeSlug);
	const hasAnyFeedNarrowing = selectedPractice !== undefined;

	const nextStepFor = (practice: ContributingPractice, practiceStanding: PracticeStandingKey) => {
		const deliveredStep = practice.nextStep?.trim();
		if (deliveredStep) return deliveredStep;
		if (practiceStanding === "STRENGTH" && practice.whatGoodLooksLike) {
			return `Keep doing this: ${practice.whatGoodLooksLike}`;
		}
		if (practiceStanding === "NO_OPPORTUNITY") {
			return "Nothing to act on yet — the reviews ran and your work offered no occasion for this practice.";
		}
		if (practiceStanding === "NOT_OBSERVED" || practiceStanding === "UNMEASURED") {
			return "No focused next step yet. It will appear after this practice is observed in reviewed work.";
		}
		return practice.whatGoodLooksLike;
	};

	return (
		<div className="mx-auto grid w-full max-w-6xl gap-6 lg:grid-cols-[minmax(20rem,2fr)_minmax(0,3fr)] lg:grid-rows-[auto_auto_1fr] lg:items-stretch">
			{onBack && (
				<div className="lg:col-span-2">
					<Button type="button" size="sm" variant="ghost" className="-ml-2" onClick={onBack}>
						<ArrowLeftIcon className="size-3.5" aria-hidden />
						Back to profile
					</Button>
				</div>
			)}

			<header className="flex max-w-4xl flex-col gap-4 lg:col-span-2">
				<div className="flex flex-wrap items-center gap-3">
					<span
						className={cn(
							"flex size-10 shrink-0 items-center justify-center rounded-lg",
							groupPill,
						)}
					>
						<GroupIcon className="size-5" aria-hidden />
					</span>
					<h1 className="min-w-0 text-pretty text-2xl font-semibold">{group.name}</h1>
					<StatusBadge def={badge} />
					{groupTrend && (
						<PracticeTrendChip
							direction={groupTrend.direction}
							support={groupTrend.support}
							scope="group"
						/>
					)}
				</div>
				{standing?.guidance && (
					<PracticeNextStepCallout label="Suggested next step">
						{standing.guidance}
					</PracticeNextStepCallout>
				)}
				{group.description && (
					<div className="flex w-full flex-col items-start gap-2">
						<Button
							type="button"
							variant="ghost"
							size="sm"
							aria-expanded={isGroupDescriptionOpen}
							aria-controls="practice-group-description"
							className={cn(
								"text-muted-foreground hover:text-foreground",
								isGroupDescriptionOpen && "bg-muted text-foreground",
							)}
							onClick={() => setIsGroupDescriptionOpen((open) => !open)}
						>
							<InfoIcon className="size-3.5" aria-hidden />
							About this group
							<ChevronDownIcon
								className={cn(
									"size-3.5 transition-transform",
									isGroupDescriptionOpen && "rotate-180",
								)}
								aria-hidden
							/>
						</Button>
						{isGroupDescriptionOpen && (
							<p
								id="practice-group-description"
								className="w-full rounded-lg border bg-muted/20 p-3 text-pretty text-sm leading-5 text-muted-foreground"
							>
								{group.description}
							</p>
						)}
					</div>
				)}
			</header>

			{practices && practices.length > 0 && (
				<section
					className="flex min-w-0 flex-col gap-3 lg:row-span-2 lg:row-start-3 lg:grid lg:grid-rows-subgrid lg:content-start lg:gap-3"
					aria-labelledby="practices-heading"
				>
					<DetailSectionIntro
						id="practices-heading"
						title="Practices in this group"
						description="Select a practice to filter the review runs. Use its info button for more context."
					/>
					<ul className="flex flex-col gap-3">
						{practices.map((practice) => {
							const practiceStanding = practice.standing ?? "UNMEASURED";
							const node = standingNode(practiceStanding);
							const NodeIcon = node.icon;
							const nodeTone = statusToneClass(node.badgeVariant);
							const practiceTrend = practice.trend;
							const isSelected = practice.slug === selectedPracticeSlug;
							const isInfoOpen = practice.slug === openPracticeInfoSlug;
							const nextStep = nextStepFor(practice, practiceStanding);
							const infoId = `practice-info-${practice.slug}`;
							const nextStepId = `practice-next-step-${practice.slug}`;
							return (
								<li
									key={practice.slug}
									className={cn(
										"overflow-hidden rounded-xl border bg-card transition-colors",
										isSelected && "border-primary/30 shadow-sm",
									)}
								>
									<div className="relative">
										<button
											type="button"
											aria-label={`${isSelected ? "Clear review-run filter for" : "Show review runs for"} ${practice.name}`}
											aria-pressed={isSelected}
											aria-expanded={isSelected}
											aria-controls={nextStepId}
											onClick={() => onSelectPractice?.(isSelected ? undefined : practice.slug)}
											className="absolute inset-0 z-10 cursor-pointer rounded-xl outline-none hover:bg-muted/30 focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring"
										/>
										<div className="grid min-h-16 grid-cols-[auto_1fr_auto] items-start gap-3 p-4">
											<span
												className={cn(
													"mt-0.5 flex size-8 shrink-0 items-center justify-center rounded-full border border-current/40 bg-background",
													nodeTone,
												)}
											>
												<NodeIcon className="size-4" aria-hidden />
											</span>
											<div className="flex min-w-0 flex-col gap-1">
												<span className="text-pretty text-sm font-medium leading-5">
													{practice.name}
												</span>
												<div className="relative z-20 flex flex-wrap items-center gap-x-2 gap-y-1 text-sm leading-5">
													<span className={nodeTone}>{node.label}</span>
													{practiceTrend && (
														<PracticeTrendChip
															direction={practiceTrend.direction}
															support={practiceTrend.support}
															scope="practice"
															className="relative z-20"
														/>
													)}
												</div>
											</div>
											<Button
												type="button"
												variant="ghost"
												size="icon-sm"
												aria-label={`About ${practice.name}`}
												aria-expanded={isInfoOpen}
												aria-controls={infoId}
												className={cn("relative z-20", isInfoOpen && "bg-muted")}
												onClick={() =>
													setOpenPracticeInfoSlug(isInfoOpen ? undefined : practice.slug)
												}
											>
												<InfoIcon className="size-4" aria-hidden />
											</Button>
										</div>
									</div>
									{isSelected && (
										<PracticeNextStepCallout
											label="Your next step"
											className="rounded-none border-x-0 border-b-0 px-4"
										>
											<span id={nextStepId}>
												{nextStep ??
													"Review the filtered feedback to choose a concrete next action."}
											</span>
										</PracticeNextStepCallout>
									)}
									{isInfoOpen && (
										<div id={infoId} className="grid gap-4 border-t bg-background/70 p-4 text-sm">
											{practice.whyItMatters && (
												<div className="flex flex-col gap-1">
													<h3 className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
														Why it matters
													</h3>
													<p className="text-pretty leading-relaxed">{practice.whyItMatters}</p>
												</div>
											)}
											{practice.whatGoodLooksLike && (
												<div className="flex flex-col gap-1">
													<h3 className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
														What good looks like
													</h3>
													<p className="text-pretty leading-relaxed">
														{practice.whatGoodLooksLike}
													</p>
												</div>
											)}
											{!practice.whyItMatters && !practice.whatGoodLooksLike && (
												<p className="text-muted-foreground">
													No additional explanation is available for this practice yet.
												</p>
											)}
										</div>
									)}
								</li>
							);
						})}
					</ul>
				</section>
			)}

			<section
				aria-labelledby="review-runs-heading"
				className={cn(
					"flex min-w-0 flex-col gap-3",
					practices && practices.length > 0
						? "border-t pt-6 lg:row-span-2 lg:row-start-3 lg:grid lg:grid-rows-subgrid lg:content-start lg:gap-3 lg:self-stretch lg:border-l lg:border-t-0 lg:pl-6 lg:pt-0"
						: "lg:col-span-2 lg:row-start-3",
				)}
			>
				<DetailSectionIntro
					id="review-runs-heading"
					title="Review runs"
					description="Complete reviews of your work in this group, newest first."
				/>
				{feed.status === "error" ? (
					<QueryErrorAlert
						error={feed.error}
						title="Could not load review runs"
						onRetry={feed.onRetry}
					/>
				) : feed.status === "loading" ? (
					<div className="flex flex-col gap-3" role="status">
						<span className="sr-only">Loading review runs</span>
						{Array.from({ length: skeletonRows }, (_, i) => (
							<Skeleton key={i} className="h-16 w-full" />
						))}
					</div>
				) : feed.runs.length > 0 ? (
					<>
						<ReviewRunTimeline
							runs={feed.runs}
							openObservationId={openObservationId}
							observationDetail={observationDetail}
							onToggleObservation={onToggleObservation}
							onRespond={onRespond}
							pendingFeedbackId={pendingFeedbackId}
						/>
						{feed.hasMore && (
							<Button
								type="button"
								variant="link"
								className="w-fit px-0 text-primary"
								onClick={feed.onLoadMore}
								disabled={feed.isLoadingMore}
							>
								{feed.isLoadingMore ? "Loading…" : "View earlier reviews"}
							</Button>
						)}
					</>
				) : (
					<Empty>
						<EmptyHeader>
							<EmptyMedia variant="icon">
								<PulseIcon />
							</EmptyMedia>
							<EmptyTitle>No review runs</EmptyTitle>
							<EmptyDescription>
								{hasAnyFeedNarrowing
									? `No review runs mention ${selectedPractice.name}.`
									: "Review runs appear here once your work has been reviewed."}
							</EmptyDescription>
						</EmptyHeader>
						{hasAnyFeedNarrowing && onSelectPractice && (
							<EmptyContent>
								<Button
									type="button"
									variant="outline"
									size="sm"
									onClick={() => onSelectPractice(undefined)}
								>
									Show every review in this group
								</Button>
							</EmptyContent>
						)}
					</Empty>
				)}
			</section>
		</div>
	);
}
