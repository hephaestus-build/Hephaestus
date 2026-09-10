import { ChevronDownIcon, ChevronUpIcon, ExternalLinkIcon } from "lucide-react";
import { useState } from "react";
import type { PracticeGroupReviewObservation, PracticeGroupReviewRun } from "@/api/types.gen";
import { GithubIcon, GitlabIcon, OutlineIcon, SlackIcon } from "@/components/icons/brand";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { ARTIFACT_KIND, artifactKindIcon, artifactKindLabel } from "@/lib/artifact-kinds";
import { asDate } from "@/lib/dates";
import { getProviderLabel } from "@/lib/provider";
import type { FeedbackResponse, ObservationDetailState } from "./review-runs";
import { ReviewObservationRow } from "./ReviewObservationRow";

const DAY = new Intl.DateTimeFormat("en-GB", { day: "numeric", month: "short" });
const TIME = new Intl.DateTimeFormat("en-GB", { hour: "2-digit", minute: "2-digit" });
const PROVIDER_ICONS = {
	KEYCLOAK_DIRECTORY: undefined,
	GITHUB_ACCESS: undefined,
	GITHUB: GithubIcon,
	SLACK: SlackIcon,
	GITLAB: GitlabIcon,
	OUTLINE: OutlineIcon,
} satisfies Record<
	NonNullable<PracticeGroupReviewRun["reviewedWork"]["provider"]>,
	typeof GithubIcon | undefined
>;
function providerMeta(run: PracticeGroupReviewRun) {
	const provider = run.reviewedWork.provider;
	return provider && PROVIDER_ICONS[provider]
		? {
				label: getProviderLabel(provider),
				Icon: PROVIDER_ICONS[provider] ?? artifactKindIcon(run.reviewedWork.type),
			}
		: undefined;
}
function workIdentity(run: PracticeGroupReviewRun, providerLabel?: string) {
	const work = run.reviewedWork;
	if (work.type === ARTIFACT_KIND.conversationThread && work.channelName) {
		return `#${work.channelName}`;
	}
	const numbered = [work.number !== undefined && `#${work.number}`, work.title]
		.filter(Boolean)
		.join(" · ");
	if (numbered) return numbered;
	const kind = artifactKindLabel(work.type);
	return providerLabel ? `${kind} on ${providerLabel}` : kind;
}

export interface ReviewRunCardProps {
	run: PracticeGroupReviewRun;
	initialObservationCount?: number;
	openObservationId?: string;
	observationDetail?: ObservationDetailState;
	onToggleObservation?: (observationId: string) => void;
	onRespond?: (observation: PracticeGroupReviewObservation, response: FeedbackResponse) => void;
	pendingFeedbackId?: string;
}

export function ReviewRunCard({
	run,
	initialObservationCount = 3,
	openObservationId,
	observationDetail,
	onToggleObservation,
	onRespond,
	pendingFeedbackId,
}: ReviewRunCardProps) {
	const [showAllObservations, setShowAllObservations] = useState(false);
	const provider = providerMeta(run);
	const KindIcon = artifactKindIcon(run.reviewedWork.type);
	const reviewedAt = asDate(run.reviewedAt);
	const identity = workIdentity(run, provider?.label);
	const collapsedCount = Math.max(1, initialObservationCount);
	const hiddenCount = Math.max(0, run.observations.length - collapsedCount);
	const visibleObservations = showAllObservations
		? run.observations
		: run.observations.slice(0, collapsedCount);

	return (
		<li className="group grid min-w-0 grid-cols-[1rem_minmax(0,1fr)] gap-x-3 sm:grid-cols-[4.5rem_1rem_minmax(0,1fr)]">
			{reviewedAt && (
				<time
					dateTime={reviewedAt.toISOString()}
					className="col-start-2 mb-1 flex w-fit gap-1 text-xs text-muted-foreground sm:col-start-1 sm:row-start-1 sm:mt-3 sm:flex-col sm:items-end"
				>
					<span className="font-medium text-foreground">{DAY.format(reviewedAt)}</span>
					<span>{TIME.format(reviewedAt)}</span>
				</time>
			)}
			<div className="relative col-start-1 row-start-1 row-end-3 sm:col-start-2">
				<span
					className="absolute left-1/2 top-3 z-10 size-2.5 -translate-x-1/2 rounded-full border-2 border-background bg-muted-foreground"
					aria-hidden
				/>
				<span
					className="absolute bottom-0 left-1/2 top-5 w-px -translate-x-1/2 bg-border group-last:hidden"
					aria-hidden
				/>
			</div>
			<Card className="col-start-2 mb-3 min-w-0 gap-0 overflow-hidden py-0 shadow-none sm:col-start-3 sm:row-start-1">
				<CardContent className="min-w-0 p-0">
					<div className="flex min-w-0 items-start gap-2 border-b bg-muted/50 px-4 py-3">
						{provider && (
							<provider.Icon className="mt-0.5 size-4 shrink-0 text-muted-foreground" aria-hidden />
						)}
						<KindIcon className="mt-0.5 size-3.5 shrink-0 text-muted-foreground" aria-hidden />
						<div className="min-w-0">
							{run.reviewedWork.url ? (
								<Tooltip>
									<TooltipTrigger
										render={
											<a
												href={run.reviewedWork.url}
												target="_blank"
												rel="noopener noreferrer"
												className="flex min-w-0 items-center gap-1 text-sm font-medium underline decoration-dotted underline-offset-2 hover:text-foreground"
											/>
										}
									>
										<span className="truncate">{identity}</span>
										<ExternalLinkIcon className="size-3 shrink-0" aria-hidden />
										<span className="sr-only"> (opens in a new tab)</span>
									</TooltipTrigger>
									<TooltipContent className="max-w-80 text-pretty">{identity}</TooltipContent>
								</Tooltip>
							) : (
								<p className="truncate text-sm font-medium">{identity}</p>
							)}
							{run.reviewedWork.repositoryName && (
								<p className="truncate text-xs text-muted-foreground">
									{run.reviewedWork.repositoryName}
								</p>
							)}
						</div>
					</div>
					<ul className="divide-y">
						{visibleObservations.map((observation) => (
							<ReviewObservationRow
								key={observation.observationId}
								observation={observation}
								isOpen={openObservationId === observation.observationId}
								detailState={
									openObservationId === observation.observationId ? observationDetail : undefined
								}
								onToggle={onToggleObservation}
								onRespond={onRespond}
								isFeedbackResponsePending={pendingFeedbackId === observation.feedbackId}
							/>
						))}
					</ul>
					{hiddenCount > 0 && (
						<div className="border-t px-4 py-2">
							<Button
								type="button"
								variant="ghost"
								size="sm"
								className="h-8 px-2 text-muted-foreground hover:text-foreground"
								onClick={() => setShowAllObservations((current) => !current)}
							>
								{showAllObservations ? "Show less" : `Show more (${hiddenCount})`}
								{showAllObservations ? (
									<ChevronUpIcon data-icon="inline-end" />
								) : (
									<ChevronDownIcon data-icon="inline-end" />
								)}
							</Button>
						</div>
					)}
				</CardContent>
			</Card>
		</li>
	);
}
