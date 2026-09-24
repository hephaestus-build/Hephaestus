import { Link } from "@tanstack/react-router";
import { MessageSquareTextIcon } from "lucide-react";

import type { GetPracticeReviewObservationResponse, Practice } from "@/api/types.gen";
import { MissingRecordEmpty } from "@/components/common/MissingRecordEmpty";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { StatusBadge } from "@/components/common/StatusBadge";
import {
	ClaimCurrentnessAlert,
	ClaimCurrentnessBadge,
} from "@/components/practice-vocabulary/ClaimCurrentness";
import { deliveryOutcome } from "@/components/practice-vocabulary/delivery-outcome-defs";
import { DELIVERY_PLACE_DEFS } from "@/components/practice-vocabulary/delivery-place-defs";
import { withholdingReasonSentence } from "@/components/practice-vocabulary/withholding-defs";
import {
	Empty,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import { Spinner } from "@/components/ui/spinner";
import { hasText } from "@/lib/text";

import { ObservationEvidence } from "./ObservationEvidence";
import { type ObservationsSearch, reviewScopeSearch } from "./review-search";
import { ReviewArtifactLink, reviewArtifactTypeSlug } from "./ReviewArtifact";
import { ObservationResultBadge } from "./ReviewBadges";
import { ReviewBreadcrumbs } from "./ReviewBreadcrumbs";
import {
	ReviewDetailHeader,
	ReviewFact,
	ReviewFactGrid,
	ReviewProvenanceLine,
} from "./ReviewDetailHeader";
import { ReviewPerson } from "./ReviewPerson";
import { ReviewPracticeLink } from "./ReviewPracticeLink";
import { ReviewRow, ReviewRowList, ReviewRowMeta } from "./ReviewRow";

export interface ObservationDetailPageProps {
	workspaceSlug: string;
	search: ObservationsSearch;
	observation: GetPracticeReviewObservationResponse | undefined;
	isLoading: boolean;
	error: unknown;
	onRetry?: () => void;
	practices: Practice[] | undefined;
}

export function ObservationDetailPage({
	workspaceSlug,
	search,
	observation,
	isLoading,
	error,
	onRetry,
	practices,
}: ObservationDetailPageProps) {
	const breadcrumbs = (
		<ReviewBreadcrumbs
			workspaceSlug={workspaceSlug}
			section={{
				label: "Observations",
				link: (
					<Link
						to="/w/$workspaceSlug/admin/practices/reviews/observations"
						params={{ workspaceSlug }}
						search={(previous) => previous}
					/>
				),
			}}
		/>
	);

	if (isLoading) {
		return (
			<article className="max-w-4xl min-w-0 space-y-8">
				{breadcrumbs}
				<div className="flex min-h-64 items-center justify-center">
					<Spinner className="size-7" />
				</div>
			</article>
		);
	}
	if (error != null) {
		return (
			<article className="max-w-4xl min-w-0 space-y-8">
				{breadcrumbs}
				<QueryErrorAlert error={error} title="Couldn't load this observation" onRetry={onRetry} />
			</article>
		);
	}
	if (!observation) {
		return (
			<article className="max-w-4xl min-w-0 space-y-8">
				{breadcrumbs}
				<MissingRecordEmpty title="This observation hasn't loaded" onRetry={onRetry} />
			</article>
		);
	}
	const artifactSlug = reviewArtifactTypeSlug(observation.artifact.kind);

	return (
		<article className="max-w-4xl min-w-0 space-y-8">
			{breadcrumbs}
			<ReviewDetailHeader
				chips={
					<>
						<ObservationResultBadge observation={observation} />
						<ClaimCurrentnessBadge currentness={observation.claimCurrentness} />
					</>
				}
				title={observation.summary}
				provenance={
					<ReviewProvenanceLine
						workspaceSlug={workspaceSlug}
						agentJobId={observation.agentJobId}
						verb="Observed"
						at={observation.observedAt}
					/>
				}
			/>
			<ClaimCurrentnessAlert currentness={observation.claimCurrentness} />

			<ReviewFactGrid>
				<ReviewFact label="Practice">
					<ReviewPracticeLink
						workspaceSlug={workspaceSlug}
						practiceSlug={observation.practiceSlug}
						practiceName={observation.practiceName}
						group={observation.group}
						practice={practices?.find((practice) => practice.slug === observation.practiceSlug)}
					/>
				</ReviewFact>
				<ReviewFact label="Developer">
					<ReviewPerson person={observation.subject} />
				</ReviewFact>
				<ReviewFact label="Reviewed work">
					<div className="space-y-1">
						<ReviewArtifactLink artifact={observation.artifact} />
						{hasText(observation.artifact.title) && (
							<p className="break-words text-muted-foreground">{observation.artifact.title}</p>
						)}
						{artifactSlug && (
							<Link
								className="font-medium underline underline-offset-4"
								to="/w/$workspaceSlug/admin/practices/reviews/targets/$artifactKind/$artifactId"
								params={{
									workspaceSlug,
									artifactKind: artifactSlug,
									artifactId: observation.artifact.id,
								}}
							>
								See everything reviewed on this work
							</Link>
						)}
					</div>
				</ReviewFact>
			</ReviewFactGrid>

			{hasText(observation.evidenceRationale) && (
				<section aria-labelledby="reasoning-heading" className="space-y-2">
					<h3 id="reasoning-heading" className="text-lg font-semibold">
						Why this was raised
					</h3>
					<p className="text-sm leading-relaxed whitespace-pre-wrap">
						{observation.evidenceRationale}
					</p>
				</section>
			)}

			<section aria-labelledby="evidence-heading" className="space-y-3">
				<h3 id="evidence-heading" className="text-lg font-semibold">
					Evidence
				</h3>
				<ObservationEvidence
					evidence={observation.evidence}
					detector={observation.evidence?.detector}
				/>
			</section>

			<section aria-labelledby="linked-feedback-heading" className="space-y-3">
				<h3 id="linked-feedback-heading" className="text-lg font-semibold">
					Feedback from this observation
				</h3>
				{observation.feedback.length === 0 ? (
					<Empty variant="outlined">
						<EmptyHeader>
							<EmptyMedia variant="icon">
								<MessageSquareTextIcon />
							</EmptyMedia>
							<EmptyTitle>Nothing was said to anybody about this</EmptyTitle>
							<EmptyDescription>
								The observation was recorded and no feedback was composed from it.
							</EmptyDescription>
						</EmptyHeader>
					</Empty>
				) : (
					<ReviewRowList label="Feedback from this observation">
						{observation.feedback.map((feedback) => (
							<ReviewRow
								key={feedback.feedbackId}
								status={deliveryOutcome(feedback)}
								title={
									<Link
										to="/w/$workspaceSlug/admin/practices/reviews/delivery/$feedbackId"
										params={{ workspaceSlug, feedbackId: feedback.feedbackId }}
										search={reviewScopeSearch(search)}
									>
										{/* Named by what it is to *this* observation. Titling it with the delivery
										    place would say nothing about the thing the link opens, and repeat
										    the fact the meta line beside it already carries. */}
										{feedback.role === "PRIMARY"
											? "Feedback about this observation"
											: "Feedback this observation supports"}
									</Link>
								}
								meta={
									<>
										<ReviewRowMeta items={[DELIVERY_PLACE_DEFS[feedback.channel].label]} />
										{feedback.suppressionReason && (
											<p>{withholdingReasonSentence(feedback.suppressionReason)}</p>
										)}
									</>
								}
								chips={[
									{
										key: "outcome",
										width: "lg:w-48",
										node: <StatusBadge def={deliveryOutcome(feedback)} />,
									},
								]}
							/>
						))}
					</ReviewRowList>
				)}
			</section>
		</article>
	);
}
