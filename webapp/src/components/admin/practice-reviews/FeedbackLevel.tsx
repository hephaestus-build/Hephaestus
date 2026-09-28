import { ScanSearchIcon } from "lucide-react";

import type {
	FeedbackApproval,
	GetPracticeReviewFeedbackResponse,
	Practice,
	ReviewPlacement,
} from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { RelativeTime } from "@/components/common/RelativeTime";
import { StatusBadge } from "@/components/common/StatusBadge";
import type { LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { Section } from "@/components/layout/Section";
import { DeliveryPolicyTrace } from "@/components/practice-trace/DeliveryPolicyTrace";
import { ClaimCurrentnessBadge } from "@/components/practice-vocabulary/ClaimCurrentness";
import {
	deliveryOutcome,
	isDeliveryInProgress,
} from "@/components/practice-vocabulary/delivery-outcome-defs";
import { DELIVERY_PLACE_DEFS } from "@/components/practice-vocabulary/delivery-place-defs";
import { DeliveryTrace } from "@/components/practice-vocabulary/DeliveryTrace";
import { codeCitationLocator } from "@/components/practice-vocabulary/evidence-source-defs";
import { observationResult } from "@/components/practice-vocabulary/observation-result";
import { PLACEMENT_DEFS } from "@/components/practice-vocabulary/placement-defs";
import { DrawerBody, DrawerFooter } from "@/components/ui/drawer";
import {
	Empty,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import { hasText } from "@/lib/text";

import { APPROVAL_DECISION_DEFS } from "./approval-decision-defs";
import { FeedbackBody } from "./FeedbackBody";
import { LevelBodySkeleton } from "./LevelBodySkeleton";
import {
	type ProposalRejectionReason,
	proposalRejectionReasonLabel,
} from "./proposal-rejection-vocabulary";
import {
	type ApprovalQueue,
	ProposalDecision,
	ProposalFacts,
	ProposalPackage,
} from "./ProposalDecision";
import { subjectLabel } from "./review-format";
import { feedbackLevel, observationLevel, workLevel } from "./review-levels";
import { ReviewArtifactLink } from "./ReviewArtifact";
import { ReviewFact, ReviewFactGrid } from "./ReviewFactGrid";
import { ReviewLevelHeader } from "./ReviewLevelHeader";
import { ReviewPackage } from "./ReviewPackage";
import { ReviewPerson } from "./ReviewPerson";
import { ReviewPracticeLink } from "./ReviewPracticeLink";
import { ReviewProvenanceLine } from "./ReviewProvenanceLine";
import { ReviewRow, ReviewRowLink, ReviewRowList, ReviewRowMeta } from "./ReviewRow";

export interface FeedbackLevelProps {
	nested?: boolean;
	path: LevelPath;
	feedback: PanelState<{ feedback: GetPracticeReviewFeedbackResponse }>;
	practices: Practice[] | undefined;
	isDeciding: boolean;
	onApprove: () => void;
	onReject: (reason: ProposalRejectionReason, note?: string) => void;
	/** Where this feedback sits among all awaiting approval; absent when it awaits none. */
	queue?: ApprovalQueue;
}

/**
 * One piece of feedback: who it is for, what it says, what became of it and what it was based on.
 * Feedback awaiting approval is the same level with the decision as its footer and the package
 * expanded, so approving it happens where it is read, and the level turns into the record the moment
 * the decision lands.
 */
export function FeedbackLevel({
	nested,
	path,
	feedback: state,
	practices,
	isDeciding,
	onApprove,
	onReject,
	queue,
}: FeedbackLevelProps) {
	const feedback = state.status === "ready" ? state.feedback : undefined;
	const awaitingApproval = feedback?.deliveryState === "AWAITING_APPROVAL";
	const header = (
		<ReviewLevelHeader
			nested={nested}
			path={path}
			kind="feedback"
			loading={state.status === "loading"}
			chips={
				feedback && (
					<>
						<StatusBadge def={deliveryOutcome(feedback)} />
						<StatusBadge def={DELIVERY_PLACE_DEFS[feedback.channel]} />
					</>
				)
			}
			title={feedback && `Feedback for ${subjectLabel(feedback.recipient)}`}
			description={
				feedback &&
				(awaitingApproval ? (
					<p>
						Approval authorizes the summary and every line comment below. Delivery checks still run
						before anything is posted.
					</p>
				) : (
					<ReviewProvenanceLine
						agentJobId={feedback.agentJobId}
						verb="Composed"
						at={feedback.createdAt}
					/>
				))
			}
		/>
	);

	if (state.status !== "ready") {
		return (
			<>
				{header}
				<DrawerBody className="pt-2">
					{state.status === "error" ? (
						<QueryErrorAlert
							error={state.error}
							title="Couldn't load this feedback"
							onRetry={state.onRetry}
						/>
					) : (
						<LevelBodySkeleton />
					)}
				</DrawerBody>
			</>
		);
	}

	const { feedback: record } = state;
	return (
		<>
			{header}
			<DrawerBody className="flex flex-col gap-8 pt-2">
				<FeedbackFacts feedback={record} proposal={awaitingApproval} />
				{awaitingApproval ? (
					<ProposalPackage feedback={record} />
				) : (
					<>
						<Section level={3} title="What it says">
							{record.proposedPlacements.length > 0 ? (
								<ReviewPackage feedback={record} />
							) : (
								<FeedbackBody feedback={record} />
							)}
						</Section>
						<WhatBecameOfIt feedback={record} />
					</>
				)}
				<SourceObservations
					feedback={record}
					practices={practices}
					description={
						awaitingApproval
							? "Open an observation to check its rationale, citations and source passages."
							: undefined
					}
				/>
			</DrawerBody>
			{awaitingApproval && (
				<DrawerFooter>
					<ProposalDecision
						canApprove={record.proposedPlacements.length > 0}
						isDeciding={isDeciding}
						onApprove={onApprove}
						onReject={onReject}
						queue={queue}
					/>
				</DrawerFooter>
			)}
		</>
	);
}

function FeedbackFacts({
	feedback,
	proposal,
}: {
	feedback: GetPracticeReviewFeedbackResponse;
	proposal: boolean;
}) {
	const subjectDiffers = feedback.subject && feedback.subject.id !== feedback.recipient?.id;
	const work = feedback.reviewedWork
		? workLevel(feedback.reviewedWork.kind, feedback.reviewedWork.id)
		: undefined;
	return (
		<ReviewFactGrid>
			<ReviewFact label={subjectDiffers === true ? "Addressed to" : "Developer"}>
				<div className="space-y-1">
					<ReviewPerson person={feedback.recipient} />
					{subjectDiffers === true && <ReviewPerson person={feedback.subject} prefix="About" />}
				</div>
			</ReviewFact>
			<ReviewFact label="Reviewed work">
				<div className="space-y-1">
					<ReviewArtifactLink reviewedWork={feedback.reviewedWork} />
					{hasText(feedback.reviewedWork?.title) && (
						<p className="break-words text-muted-foreground">{feedback.reviewedWork.title}</p>
					)}
					{work && (
						<InlineLink className="font-medium" render={<DetailStackLink entry={work} />}>
							See everything reviewed on this work
						</InlineLink>
					)}
				</div>
			</ReviewFact>
			{proposal && <ProposalFacts feedback={feedback} />}
		</ReviewFactGrid>
	);
}

function WhatBecameOfIt({ feedback }: { feedback: GetPracticeReviewFeedbackResponse }) {
	const anchoredPlacements = feedback.placements.filter((placement) =>
		hasText(placement.anchorPath),
	);
	const packageSize = feedback.proposedPlacements.length;
	const deliveredPlacements = feedback.placements.filter((placement) =>
		hasText(placement.postedCommentRef),
	).length;
	const deliveryInProgress = isDeliveryInProgress(feedback);
	const { approval } = feedback;
	return (
		<Section level={3} title="What became of it">
			<DeliveryTrace feedback={feedback} />
			{approval?.decision ? <ApprovalAudit approval={approval} /> : null}
			<DeliveryPolicyTrace evaluations={feedback.deliveryPolicy} />
			{packageSize > 0 && approval?.decision === "APPROVED" && (
				<div className="rounded-lg border p-3">
					<p className="text-sm font-medium">
						{Math.min(deliveredPlacements, packageSize)} of {packageSize} comments confirmed
						delivered
					</p>
					{deliveryInProgress ? (
						<p className="mt-1 text-sm text-muted-foreground">
							Delivery is still in progress. This updates as the remaining comments are retried.
						</p>
					) : null}
				</div>
			)}
			{anchoredPlacements.length > 0 && (
				<div className="space-y-1 rounded-lg border p-3">
					<p className="text-sm font-medium">Where it was anchored</p>
					<ul className="space-y-1">
						{anchoredPlacements.map((placement) => (
							<li key={placement.id} className="text-sm text-muted-foreground">
								<span>{PLACEMENT_DEFS[placement.placementType].label}: </span>
								<code className="break-all">{anchorLabel(placement)}</code>
							</li>
						))}
					</ul>
				</div>
			)}
			{hasText(feedback.replacesId) && (
				<p className="text-sm text-muted-foreground">
					<InlineLink
						className="font-medium"
						render={<DetailStackLink entry={feedbackLevel(feedback.replacesId)} />}
					>
						See the feedback this replaced
					</InlineLink>
				</p>
			)}
		</Section>
	);
}

function SourceObservations({
	feedback,
	practices,
	description,
}: {
	feedback: GetPracticeReviewFeedbackResponse;
	practices: Practice[] | undefined;
	description?: string;
}) {
	return (
		<Section level={3} title="What it was based on" description={description}>
			{feedback.observations.length === 0 ? (
				<Empty variant="outlined">
					<EmptyHeader>
						<EmptyMedia variant="icon">
							<ScanSearchIcon />
						</EmptyMedia>
						<EmptyTitle>No observations are linked to this feedback</EmptyTitle>
						<EmptyDescription>
							The observations behind it were not recorded, so there is nothing to check it against.
						</EmptyDescription>
					</EmptyHeader>
				</Empty>
			) : (
				<ReviewRowList label="Observations behind this feedback">
					{feedback.observations.map((observation) => (
						<ReviewRow
							key={observation.observationId}
							status={observationResult(observation)}
							title={
								<ReviewRowLink entry={observationLevel(observation.observationId)}>
									{observation.summary}
								</ReviewRowLink>
							}
							meta={
								<ReviewRowMeta
									items={[
										<ReviewPracticeLink
											key="practice"
											practiceSlug={observation.practiceSlug}
											practiceName={observation.practiceName}
											group={observation.group}
											practice={practices?.find(
												(practice) => practice.slug === observation.practiceSlug,
											)}
										/>,
										observation.role === "PRIMARY"
											? "What this feedback is about"
											: "Supporting this feedback",
									]}
								/>
							}
							chips={[
								{
									key: "currentness",
									width: "lg:w-44",
									node: <ClaimCurrentnessBadge currentness={observation.claimCurrentness} />,
								},
							]}
						/>
					))}
				</ReviewRowList>
			)}
		</Section>
	);
}

function ApprovalAudit({ approval }: { approval: FeedbackApproval }) {
	if (!approval.decision) {
		return null;
	}
	const rejected = approval.decision === "REJECTED";
	return (
		<div className="rounded-lg border p-3 text-sm">
			<p className="font-medium">Human decision</p>
			<dl className="mt-2 grid grid-cols-[max-content_minmax(0,1fr)] gap-x-3 gap-y-1 text-muted-foreground">
				<dt className="font-medium text-foreground">Decision</dt>
				<dd>
					<StatusBadge def={APPROVAL_DECISION_DEFS[approval.decision]} />
				</dd>
				{approval.decidedAt ? (
					<>
						<dt className="font-medium text-foreground">Decided</dt>
						<dd>
							<RelativeTime value={approval.decidedAt} />
						</dd>
					</>
				) : null}
				{approval.actorAccountId == null ? null : (
					<>
						<dt className="font-medium text-foreground">Reviewer</dt>
						<dd>Account {approval.actorAccountId}</dd>
					</>
				)}
				{rejected && approval.rejectionReason ? (
					<>
						<dt className="font-medium text-foreground">Reason</dt>
						<dd>{proposalRejectionReasonLabel(approval.rejectionReason)}</dd>
					</>
				) : null}
				{rejected && hasText(approval.rejectionNote) ? (
					<>
						<dt className="font-medium text-foreground">Note</dt>
						<dd className="min-w-0 break-words whitespace-pre-wrap">{approval.rejectionNote}</dd>
					</>
				) : null}
			</dl>
		</div>
	);
}

function anchorLabel(placement: ReviewPlacement): string {
	const { anchorPath, anchorStartLine, anchorEndLine } = placement;
	if (!hasText(anchorPath) || anchorStartLine === undefined) {
		return anchorPath ?? "";
	}
	return codeCitationLocator({
		path: anchorPath,
		startLine: anchorStartLine,
		endLine: anchorEndLine,
	});
}
