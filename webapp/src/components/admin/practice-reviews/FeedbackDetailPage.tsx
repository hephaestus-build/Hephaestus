import { Link } from "@tanstack/react-router";
import { EyeOffIcon, ScanSearchIcon, Undo2Icon } from "lucide-react";
import type {
	FeedbackApproval,
	FeedbackWithdrawal,
	GetPracticeReviewFeedbackResponse,
	Practice,
	ReviewPlacement,
} from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { RelativeTime } from "@/components/common/RelativeTime";
import { StatusBadge } from "@/components/common/StatusBadge";
import { DeliveryPolicyTrace } from "@/components/practice-trace/DeliveryPolicyTrace";
import { ClaimCurrentnessBadge } from "@/components/practice-vocabulary/ClaimCurrentness";
import { DeliveryTrace } from "@/components/practice-vocabulary/DeliveryTrace";
import { codeCitationLocator } from "@/components/practice-vocabulary/evidence-source-defs";
import { observationResult } from "@/components/practice-vocabulary/observation-result";
import { PLACEMENT_DEFS } from "@/components/practice-vocabulary/placement-defs";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import {
	Empty,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import { Spinner } from "@/components/ui/spinner";
import { hasText } from "@/lib/text";
import { APPROVAL_DECISION_DEFS } from "./approval-decision-defs";
import { CorrectionEntry, CorrectionReasonPopover } from "./CorrectionReason";
import { FeedbackBody } from "./FeedbackBody";
import { proposalRejectionReasonLabel } from "./proposal-rejection-vocabulary";
import { subjectLabel } from "./review-format";
import { type FeedbackSearch, reviewScopeSearch } from "./review-search";
import { ReviewArtifactLink, reviewArtifactTypeSlug } from "./ReviewArtifact";
import { ObservationResultBadge } from "./ReviewBadges";
import { ReviewBreadcrumbs } from "./ReviewBreadcrumbs";
import {
	ReviewDetailHeader,
	ReviewFact,
	ReviewFactGrid,
	ReviewProvenanceLine,
} from "./ReviewDetailHeader";
import { ReviewPackage } from "./ReviewPackage";
import { ReviewPerson } from "./ReviewPerson";
import { ReviewPracticeLink } from "./ReviewPracticeLink";
import { ReviewRow, ReviewRowList, ReviewRowMeta } from "./ReviewRow";

export interface FeedbackDetailPageProps {
	workspaceSlug: string;
	search: FeedbackSearch;
	state:
		| { status: "loading" }
		| { status: "error"; error: unknown; onRetry: () => void }
		| { status: "ready"; feedback: GetPracticeReviewFeedbackResponse };
	practices: Practice[] | undefined;
	/**
	 * Withdraws (`true`) or restores (`false`) a card on the developer's practice page, with the admin's reason.
	 * Settles when the server has answered; the form keeps the reason until it succeeded.
	 */
	onChangeWithdrawal: (withdrawn: boolean, reason: string) => Promise<unknown>;
	isChangingWithdrawal: boolean;
}

export function FeedbackDetailPage({
	workspaceSlug,
	search,
	state,
	practices,
	onChangeWithdrawal,
	isChangingWithdrawal,
}: FeedbackDetailPageProps) {
	const breadcrumbs = (
		<ReviewBreadcrumbs
			workspaceSlug={workspaceSlug}
			section={{
				label: "Delivery",
				link: (
					<Link
						to="/w/$workspaceSlug/admin/practices/reviews/delivery"
						params={{ workspaceSlug }}
						search={(previous) => previous}
					/>
				),
			}}
		/>
	);

	if (state.status === "loading") {
		return (
			<article className="max-w-4xl min-w-0 space-y-8">
				{breadcrumbs}
				<div className="flex min-h-64 items-center justify-center">
					<Spinner className="size-7" />
				</div>
			</article>
		);
	}
	if (state.status === "error") {
		return (
			<article className="max-w-4xl min-w-0 space-y-8">
				{breadcrumbs}
				<QueryErrorAlert
					error={state.error}
					title="Couldn't load this feedback"
					onRetry={state.onRetry}
				/>
			</article>
		);
	}
	const { feedback } = state;
	const subjectDiffers = feedback.subject && feedback.subject.id !== feedback.recipient?.id;
	const artifactSlug = feedback.reviewedWork
		? reviewArtifactTypeSlug(feedback.reviewedWork.kind)
		: undefined;
	const anchoredPlacements = feedback.placements.filter((placement) =>
		hasText(placement.anchorPath),
	);
	const packageSize = feedback.proposedPlacements.length;
	const deliveredPlacements = feedback.placements.filter((placement) =>
		hasText(placement.postedCommentRef),
	).length;
	const deliveryInProgress =
		feedback.deliveryState === "PREPARED" ||
		(feedback.deliveryState === "PARTIALLY_DELIVERED" && !feedback.suppressionReason);
	const { approval } = feedback;
	const latestWithdrawal = feedback.withdrawals.at(0);
	const withdrawal =
		latestWithdrawal !== undefined && latestWithdrawal.restoredAt === undefined
			? latestWithdrawal
			: undefined;
	// Only a practice-page card that is waiting or shown can be taken back; a withdrawn one can always be restored.
	const canWithdraw =
		feedback.channel === "IN_APP" &&
		(withdrawal !== undefined ||
			feedback.deliveryState === "PREPARED" ||
			feedback.deliveryState === "DELIVERED");

	return (
		<article className="max-w-4xl min-w-0 space-y-8">
			{breadcrumbs}
			<ReviewDetailHeader
				title={`Feedback for ${subjectLabel(feedback.recipient)}`}
				actions={
					canWithdraw ? (
						<CorrectionReasonPopover
							copy={
								withdrawal
									? {
											trigger: "Restore feedback",
											title: "Restore this feedback",
											description:
												"It returns to the developer's practice page if its observations can still be shown there. Nothing is sent again.",
											placeholder: "Why the feedback was right after all…",
										}
									: {
											trigger: "Withdraw feedback",
											title: "Withdraw this feedback",
											description:
												"It comes off the developer's practice page and out of the feedback given to later reviews and Heph. The observations behind it are unchanged. A developer who already saw it sees that it was withdrawn, not your reason.",
											placeholder: "What the feedback got wrong…",
										}
							}
							icon={withdrawal ? Undo2Icon : EyeOffIcon}
							destructive={withdrawal === undefined}
							name="feedback-withdrawal-reason"
							disabled={isChangingWithdrawal}
							onSubmit={async (reason) => onChangeWithdrawal(withdrawal === undefined, reason)}
						/>
					) : undefined
				}
				provenance={
					<ReviewProvenanceLine
						workspaceSlug={workspaceSlug}
						agentJobId={feedback.agentJobId}
						verb="Composed"
						at={feedback.createdAt}
					/>
				}
			/>

			{withdrawal && <WithdrawalAlert withdrawal={withdrawal} />}

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
						{feedback.reviewedWork && artifactSlug && (
							<Link
								className="font-medium underline underline-offset-4"
								to="/w/$workspaceSlug/admin/practices/reviews/targets/$artifactKind/$artifactId"
								params={{
									workspaceSlug,
									artifactKind: artifactSlug,
									artifactId: feedback.reviewedWork.id,
								}}
							>
								See everything reviewed on this work
							</Link>
						)}
					</div>
				</ReviewFact>
			</ReviewFactGrid>

			<section aria-labelledby="feedback-body-heading" className="space-y-3">
				<h3 id="feedback-body-heading" className="text-lg font-semibold">
					What it says
				</h3>
				{feedback.proposedPlacements.length > 0 ? (
					<ReviewPackage feedback={feedback} />
				) : (
					<FeedbackBody feedback={feedback} />
				)}
			</section>

			<section aria-labelledby="delivery-heading" className="space-y-3">
				<h3 id="delivery-heading" className="text-lg font-semibold">
					What became of it
				</h3>
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
								Delivery is still in progress. This page updates as the remaining comments are
								retried.
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
						<Link
							to="/w/$workspaceSlug/admin/practices/reviews/delivery/$feedbackId"
							params={{ workspaceSlug, feedbackId: feedback.replacesId }}
							search={reviewScopeSearch(search)}
							className="font-medium text-foreground underline underline-offset-4"
						>
							See the feedback this replaced
						</Link>
					</p>
				)}
			</section>

			<section aria-labelledby="source-observations-heading" className="space-y-3">
				<h3 id="source-observations-heading" className="text-lg font-semibold">
					What it was based on
				</h3>
				{feedback.observations.length === 0 ? (
					<Empty variant="outlined">
						<EmptyHeader>
							<EmptyMedia variant="icon">
								<ScanSearchIcon />
							</EmptyMedia>
							<EmptyTitle>No observations are linked to this feedback</EmptyTitle>
							<EmptyDescription>
								The observations behind it were not recorded, so there is nothing to check it
								against.
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
									<Link
										to="/w/$workspaceSlug/admin/practices/reviews/observations/$observationId"
										params={{ workspaceSlug, observationId: observation.observationId }}
										search={reviewScopeSearch(search)}
									>
										{observation.summary}
									</Link>
								}
								meta={
									<ReviewRowMeta
										items={[
											<ReviewPracticeLink
												key="practice"
												workspaceSlug={workspaceSlug}
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
										key: "result",
										width: "lg:w-64",
										node: <ObservationResultBadge observation={observation} />,
									},
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
			</section>

			{feedback.withdrawals.length > 0 && (
				<section aria-labelledby="withdrawals-heading" className="space-y-3">
					<h3 id="withdrawals-heading" className="text-lg font-semibold">
						Withdrawals
					</h3>
					<ol aria-labelledby="withdrawals-heading" className="space-y-3 text-sm">
						{feedback.withdrawals.flatMap((entry) => [
							entry.restoredAt !== undefined && (
								<CorrectionEntry
									key={`${entry.id}-restored`}
									action="Restored"
									actor={entry.restoredBy}
									at={entry.restoredAt}
									reason={entry.restorationReason}
								/>
							),
							<CorrectionEntry
								key={entry.id}
								action="Withdrawn"
								actor={entry.withdrawnBy}
								at={entry.withdrawnAt}
								reason={entry.reason}
							/>,
						])}
					</ol>
				</section>
			)}
		</article>
	);
}

function WithdrawalAlert({ withdrawal }: { withdrawal: FeedbackWithdrawal }) {
	return (
		<Alert variant="destructive">
			<EyeOffIcon />
			<AlertTitle>Withdrawn from the practice page</AlertTitle>
			<AlertDescription>
				<p>
					{withdrawal.withdrawnBy ?? "A workspace admin"} withdrew this feedback{" "}
					<RelativeTime value={withdrawal.withdrawnAt} />: “{withdrawal.reason}”. It is off the
					developer’s practice page and out of the feedback given to later reviews and Heph. If the
					developer had already seen it, their page shows for a while that it was withdrawn. The
					observations behind it are unchanged.
				</p>
			</AlertDescription>
		</Alert>
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
