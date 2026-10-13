import { EyeOffIcon, Undo2Icon } from "lucide-react";
import type { ReactNode } from "react";

import type {
	GetPracticeReviewFeedbackResponse,
	ReviewFeedbackDispatch,
	ReviewPlacement,
} from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { StatusBadge } from "@/components/common/StatusBadge";
import type { LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import { Section } from "@/components/layout/Section";
import { DeliveryPolicyTrace } from "@/components/practice-trace/DeliveryPolicyTrace";
import {
	deliveryOutcome,
	isDeliveryInProgress,
} from "@/components/practice-vocabulary/delivery-outcome-defs";
import { OPERATOR_WITHHELD_TEXT } from "@/components/practice-vocabulary/delivery-place-defs";
import { codeCitationLocator } from "@/components/practice-vocabulary/evidence-source-defs";
import {
	FEEDBACK_DISPATCH_DESTINATION_LABELS,
	FEEDBACK_DISPATCH_STATE_DEFS,
} from "@/components/practice-vocabulary/feedback-dispatch-defs";
import { FEEDBACK_WITHDRAWN_DEF } from "@/components/practice-vocabulary/feedback-withdrawal-defs";
import { DEVELOPER_RESPONSE_DEFS } from "@/components/practice-vocabulary/observation-dispute-defs";
import {
	deliveredPlacementType,
	PLACEMENT_DEFS,
	placementLabel,
} from "@/components/practice-vocabulary/placement-defs";
import { WITHHOLDING_REASON_DEFS } from "@/components/practice-vocabulary/withholding-defs";
import { DrawerBody, DrawerFooter } from "@/components/ui/drawer";
import { hasText } from "@/lib/text";

import { APPROVAL_DECISION_DEFS } from "./approval-decision-defs";
import { CorrectionReasonPopover } from "./CorrectionReason";
import { feedbackOpeningLine } from "./feedback-preview";
import { FeedbackBody } from "./FeedbackBody";
import { FeedbackLocationLabel } from "./FeedbackLocationLabel";
import { LevelBodySkeleton } from "./LevelBodySkeleton";
import {
	type ProposalRejectionReason,
	proposalRejectionReasonLabel,
} from "./proposal-rejection-vocabulary";
import { type ApprovalQueue, ProposalDecision, ProposalPackage } from "./ProposalDecision";
import {
	ERASED_ACCOUNT,
	type SheetEvent,
	SheetHistory,
	SheetSection,
	SheetStatusLine,
	SheetTechnical,
	type TechnicalFact,
} from "./RecordSheet";
import { revisionLabel } from "./review-format";
import { ReviewArtifactLink } from "./ReviewArtifact";
import { ReviewStatusMark } from "./ReviewBadges";
import { ReviewDayTime } from "./ReviewDayTime";
import { ReviewFact, ReviewFactGrid } from "./ReviewFactGrid";
import { ReviewLevelHeader } from "./ReviewLevelHeader";
import { ReviewPackage } from "./ReviewPackage";
import { ReviewPerson } from "./ReviewPerson";

type FeedbackRecord = GetPracticeReviewFeedbackResponse;

export interface FeedbackLevelProps {
	nested?: boolean;
	path: LevelPath;
	feedback: PanelState<{ feedback: FeedbackRecord }>;
	isDeciding: boolean;
	onApprove: () => void;
	onReject: (reason: ProposalRejectionReason, note?: string) => void;
	/** Where this feedback sits among all awaiting approval; absent when it awaits none. */
	queue?: ApprovalQueue;
	/**
	 * Withdraws (`true`) or restores (`false`) a card on the developer's practice pages, with the
	 * admin's reason. Settles when the server has answered; the form keeps the reason until it
	 * succeeded.
	 */
	onChangeWithdrawal: (withdrawn: boolean, reason: string) => Promise<unknown>;
	isChangingWithdrawal: boolean;
}

/**
 * The title a piece of feedback is known by: its first line, whole. The header wraps it to three
 * lines at most. Text on a private place is the developer's, so its place names it instead.
 */
export function feedbackTitle(feedback: FeedbackRecord): string {
	const summary = feedback.proposedPlacements.find((placement) => placement.type === "SUMMARY");
	const body = feedback.body ?? summary?.body ?? feedback.proposedPlacements.at(0)?.body;
	return (
		OPERATOR_WITHHELD_TEXT[feedback.channel]?.title ??
		feedbackOpeningLine(body) ??
		(hasText(body) ? "Opens with a quote from the work…" : "No feedback text was composed")
	);
}

/** The withdrawal in force, if any: the newest one, until it is restored. */
function activeWithdrawal(feedback: FeedbackRecord) {
	const latest = feedback.withdrawals.at(0);
	return latest !== undefined && latest.restoredAt === undefined ? latest : undefined;
}

/** "1 of 2 delivered" for a package some of whose comments are confirmed, else nothing. */
function deliveredFigure(feedback: FeedbackRecord): string | undefined {
	const size = feedback.proposedPlacements.length;
	if (size <= 1 || feedback.approval?.decision !== "APPROVED") {
		return undefined;
	}
	const delivered = feedback.placements.filter((placement) =>
		hasText(placement.postedCommentRef),
	).length;
	return `${Math.min(delivered, size)} of ${size} delivered`;
}

/**
 * One piece of feedback's sheet, and only the feedback, in the order every sheet has: the status
 * line (what became of it, withdrawn or disputed, how much of it arrived, when), what it is (who it
 * is for, where it goes, the work and revision), its history — composed, decided, withheld with the
 * precise reason, each comment delivered with its permalink, withdrawn and restored, disputed in the
 * developer's words — what it says and its own delivery checks, and the technical facts.
 *
 * Its actions are the footer: approve or reject while it awaits approval, withdraw or restore a card
 * on the developer's practice pages otherwise. The observations it was written from, the review that
 * wrote it and when it was read are its trace.
 */
export function FeedbackLevel({
	nested,
	path,
	feedback: state,
	isDeciding,
	onApprove,
	onReject,
	queue,
	onChangeWithdrawal,
	isChangingWithdrawal,
}: FeedbackLevelProps) {
	const feedback = state.status === "ready" ? state.feedback : undefined;
	const awaitingApproval = feedback?.deliveryState === "AWAITING_APPROVAL";
	const withdrawal = feedback && activeWithdrawal(feedback);
	let flag: ReactNode;
	if (withdrawal !== undefined) {
		flag = <StatusBadge def={FEEDBACK_WITHDRAWN_DEF} />;
	} else if (feedback?.dispute !== undefined) {
		flag = <StatusBadge def={DEVELOPER_RESPONSE_DEFS.DISPUTED} />;
	}
	const header = (
		<ReviewLevelHeader
			nested={nested}
			path={path}
			kind="feedback"
			loading={state.status === "loading"}
			chips={
				feedback && (
					<SheetStatusLine
						status={<ReviewStatusMark def={deliveryOutcome(feedback)} />}
						flag={flag}
						figure={deliveredFigure(feedback)}
						at={
							feedback.deliveredAt === undefined
								? { label: "composed", date: feedback.createdAt }
								: { label: "delivered", date: feedback.deliveredAt }
						}
					/>
				)
			}
			title={feedback && feedbackTitle(feedback)}
			description={
				feedback?.reviewedWork && <ReviewArtifactLink reviewedWork={feedback.reviewedWork} />
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
							title="We could not load this feedback"
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
				<SheetSection part="what">
					<FeedbackWhat feedback={record} />
				</SheetSection>
				<SheetSection part="state">
					<SheetHistory events={feedbackHistory(record)} now={feedbackNow(record)} />
				</SheetSection>
				{awaitingApproval ? (
					<ProposalPackage feedback={record} />
				) : (
					<SheetSection part="details" title="What it says">
						{record.proposedPlacements.length > 0 ? (
							<ReviewPackage feedback={record} />
						) : (
							<FeedbackBody feedback={record} />
						)}
					</SheetSection>
				)}
				{record.deliveryPolicy.length > 0 && (
					<Section level={3} title="Delivery checks" className="min-w-0">
						<DeliveryPolicyTrace evaluations={record.deliveryPolicy} />
					</Section>
				)}
				<SheetTechnical facts={feedbackTechnical(record)} />
			</DrawerBody>
			{awaitingApproval ? (
				// The decision is the footer like every sheet's actions, so it stays in reach while the
				// package above it is read; approving it unread is what the expanded package designs out.
				<DrawerFooter className="sm:flex-wrap sm:items-center">
					<p className="text-xs text-muted-foreground sm:mr-auto">
						Approval authorizes the summary and every line comment above. Delivery checks still run
						before anything is posted.
					</p>
					<div
						role="group"
						aria-label="Decide on this feedback"
						className="flex flex-wrap items-center justify-end gap-2"
					>
						<ProposalDecision
							canApprove={record.proposedPlacements.length > 0}
							isDeciding={isDeciding}
							onApprove={onApprove}
							onReject={onReject}
							queue={queue}
						/>
					</div>
				</DrawerFooter>
			) : (
				canWithdraw(record) && (
					<DrawerFooter>
						<WithdrawalPopover
							withdrawn={withdrawal !== undefined}
							disabled={isChangingWithdrawal}
							onSubmit={async (reason) => onChangeWithdrawal(withdrawal === undefined, reason)}
						/>
					</DrawerFooter>
				)
			)}
		</>
	);
}

function FeedbackWhat({ feedback }: { feedback: FeedbackRecord }) {
	const subjectDiffers = feedback.subject && feedback.subject.id !== feedback.recipient?.id;
	return (
		<ReviewFactGrid>
			<ReviewFact label="Developer">
				<div className="space-y-1">
					<ReviewPerson person={feedback.recipient} />
					{subjectDiffers === true && <ReviewPerson person={feedback.subject} prefix="About" />}
				</div>
			</ReviewFact>
			<ReviewFact label="Channel">
				<FeedbackLocationLabel feedback={feedback} />
			</ReviewFact>
			{hasText(feedback.reviewedWork?.title) && (
				<ReviewFact label="Reviewed work">
					<span className="break-words">{feedback.reviewedWork.title}</span>
				</ReviewFact>
			)}
			{feedback.deliveryState === "AWAITING_APPROVAL" && feedback.proposedPlacements.length > 0 && (
				<ReviewFact label="Will appear as">
					<ProposedPlaces feedback={feedback} />
				</ReviewFact>
			)}
			{hasText(feedback.reviewedRevision) && (
				<ReviewFact label="Reviewed revision">
					<code className="text-xs break-all">{revisionLabel(feedback.reviewedRevision)}</code>
				</ReviewFact>
			)}
		</ReviewFactGrid>
	);
}

/**
 * Where a proposal will appear once approved. On GitLab a note proposed for some lines goes up as a
 * comment linking to them, so the place says that rather than an inline note.
 */
function ProposedPlaces({ feedback }: { feedback: FeedbackRecord }) {
	const provider = feedback.reviewedWork?.provider;
	const places = [
		...new Set(
			feedback.proposedPlacements.map((placement) =>
				placementLabel(feedback.channel, deliveredPlacementType(placement.type, provider)),
			),
		),
	];
	return (
		<ul className="space-y-0.5">
			{places.map((place) => (
				<li key={place}>{place}</li>
			))}
		</ul>
	);
}

function anchorLabel(placement: ReviewPlacement): string | undefined {
	const { anchorPath, anchorStartLine, anchorEndLine } = placement;
	if (!hasText(anchorPath)) {
		return undefined;
	}
	if (anchorStartLine === undefined) {
		return anchorPath;
	}
	return codeCitationLocator({
		path: anchorPath,
		startLine: anchorStartLine,
		endLine: anchorEndLine,
	});
}

/**
 * Composed, decided, withheld or delivered comment by comment, each delivery to the provider with
 * its attempts and last error, withdrawn and restored, disputed: one list, oldest first. A comment's
 * permalink is the one link out, to where it was posted.
 */
export function feedbackHistory(feedback: FeedbackRecord): SheetEvent[] {
	const events: SheetEvent[] = [{ key: "composed", at: feedback.createdAt, title: "Composed" }];
	const { approval } = feedback;
	if (approval?.decision !== undefined) {
		const def = APPROVAL_DECISION_DEFS[approval.decision];
		const rejected = approval.decision === "REJECTED";
		events.push({
			key: "decided",
			at: approval.decidedAt,
			title: def.label,
			by: approval.decidedBy ?? ERASED_ACCOUNT,
			detail:
				rejected && (approval.rejectionReason !== undefined || hasText(approval.rejectionNote)) ? (
					<>
						{approval.rejectionReason !== undefined && (
							<span className="block">
								Reason: {proposalRejectionReasonLabel(approval.rejectionReason)}
							</span>
						)}
						{hasText(approval.rejectionNote) && (
							<span className="block whitespace-pre-wrap">Note: “{approval.rejectionNote}”</span>
						)}
					</>
				) : undefined,
		});
	}
	if (feedback.suppressionReason !== undefined) {
		const stopped = feedback.deliveryPolicy.find((evaluation) => !evaluation.allowed);
		events.push({
			key: "withheld",
			at: stopped?.evaluatedAt,
			title: "Withheld",
			detail: WITHHOLDING_REASON_DEFS[feedback.suppressionReason],
		});
	}
	const posted = feedback.placements.filter(
		(placement) => hasText(placement.postedCommentRef) || placement.mentorEntryId !== undefined,
	);
	const commentLinked = feedback.placements.some((placement) =>
		hasText(placement.postedCommentUrl),
	);
	const delivered = posted.length > 0 || feedback.deliveredAt !== undefined;
	// A sent delivery is the "Delivered" event itself: a second event for it said the outcome twice.
	// What only it knows, the link to what it posted and a retry that preceded it, joins that event.
	const sent = delivered ? feedback.dispatches.filter((dispatch) => dispatch.state === "SENT") : [];
	const sentDetail = sent.map((dispatch) => (
		<SentDispatchDetail key={dispatch.id} dispatch={dispatch} linked={commentLinked} />
	));
	if (posted.length > 0) {
		events.push({
			key: "delivered",
			at: feedback.deliveredAt,
			title: feedback.deliveryState === "PARTIALLY_DELIVERED" ? "Partially delivered" : "Delivered",
			detail: (
				<>
					<ul className="space-y-0.5">
						{posted.map((placement) => {
							const anchor = anchorLabel(placement);
							return (
								<li key={placement.id} className="flex flex-wrap items-center gap-x-2">
									<span>{PLACEMENT_DEFS[placement.placementType].label}</span>
									{anchor !== undefined && <code className="break-all">{anchor}</code>}
									{hasText(placement.postedCommentUrl) && (
										<InlineLink href={placement.postedCommentUrl} external>
											Open the comment
										</InlineLink>
									)}
								</li>
							);
						})}
					</ul>
					{sentDetail}
				</>
			),
		});
	} else if (feedback.deliveredAt !== undefined) {
		events.push({
			key: "delivered",
			at: feedback.deliveredAt,
			title: "Delivered",
			detail: sentDetail.length > 0 ? sentDetail : undefined,
		});
	}
	for (const dispatch of feedback.dispatches) {
		if (sent.includes(dispatch)) {
			continue;
		}
		// Plain words, not a second status badge: the status line above already gives the outcome,
		// and this event says what happened to this one delivery and why.
		events.push({
			key: `dispatch-${dispatch.id}`,
			at: dispatch.updatedAt,
			title: FEEDBACK_DISPATCH_STATE_DEFS[dispatch.state].label,
			detail: (
				<DispatchDetail
					dispatch={dispatch}
					linked={commentLinked}
					withheldFor={feedback.suppressionReason}
				/>
			),
		});
	}
	for (const withdrawal of feedback.withdrawals) {
		events.push({
			key: withdrawal.id,
			at: withdrawal.withdrawnAt,
			title: "Withdrawn",
			by: withdrawal.withdrawnBy ?? ERASED_ACCOUNT,
			detail: `Reason: “${withdrawal.reason}”`,
		});
		if (withdrawal.restoredAt !== undefined) {
			events.push({
				key: `${withdrawal.id}-restored`,
				at: withdrawal.restoredAt,
				title: "Restored",
				by: withdrawal.restoredBy ?? ERASED_ACCOUNT,
				detail: hasText(withdrawal.restorationReason)
					? `Reason: “${withdrawal.restorationReason}”`
					: undefined,
			});
		}
	}
	if (feedback.dispute !== undefined) {
		events.push({
			key: "disputed",
			at: feedback.dispute.disputedAt,
			title: "Disputed by the developer",
			detail: (
				<blockquote className="mt-1 border-l-2 pl-3 break-words whitespace-pre-wrap text-foreground">
					{feedback.dispute.explanation}
				</blockquote>
			),
		});
	}
	return events;
}

/**
 * What one delivery carried, how often it was tried, when it is tried next, why it was held back and
 * the last error, and the link to what it posted where no comment of the feedback links there
 * already. A reason the "Withheld" event already gives is not given again.
 */
function DispatchDetail({
	dispatch,
	linked,
	withheldFor,
}: {
	dispatch: ReviewFeedbackDispatch;
	linked: boolean;
	withheldFor: ReviewFeedbackDispatch["suppressionReason"];
}) {
	return (
		<>
			<span className="block">{dispatchCarried(dispatch)}</span>
			{dispatch.nextAttemptAt !== undefined && (
				<span className="block">
					Next attempt <ReviewDayTime at={dispatch.nextAttemptAt} className="tabular-nums" />
				</span>
			)}
			{dispatch.suppressionReason !== undefined && dispatch.suppressionReason !== withheldFor && (
				<span className="block">{WITHHOLDING_REASON_DEFS[dispatch.suppressionReason]}</span>
			)}
			{hasText(dispatch.lastError) && (
				<div className="mt-1">
					Last error:
					<pre className="mt-1 max-h-48 overflow-auto rounded-md bg-muted/50 p-2 text-xs break-words whitespace-pre-wrap">
						{dispatch.lastError}
					</pre>
				</div>
			)}
			{!linked && hasText(dispatch.deliveredExternalUrl) && (
				<InlineLink href={dispatch.deliveredExternalUrl} external>
					Open what was posted
				</InlineLink>
			)}
		</>
	);
}

/**
 * What a sent delivery adds to the "Delivered" event: the attempts it took when one was not enough,
 * and the link to what it posted where no comment links there already.
 */
function SentDispatchDetail({
	dispatch,
	linked,
}: {
	dispatch: ReviewFeedbackDispatch;
	linked: boolean;
}) {
	const retried = dispatch.attemptCount > 1;
	const link = !linked && hasText(dispatch.deliveredExternalUrl);
	if (!retried && !link) {
		return null;
	}
	return (
		<span className="mt-0.5 flex flex-wrap items-center gap-x-2">
			{retried && <span>{dispatchCarried(dispatch)}</span>}
			{link && (
				<InlineLink href={dispatch.deliveredExternalUrl} external>
					Open what was posted
				</InlineLink>
			)}
		</span>
	);
}

/** "The review’s comments · 5 attempts". */
function dispatchCarried(dispatch: ReviewFeedbackDispatch): string {
	const attempts = dispatch.attemptCount === 1 ? "1 attempt" : `${dispatch.attemptCount} attempts`;
	return `${FEEDBACK_DISPATCH_DESTINATION_LABELS[dispatch.destination]} · ${attempts}`;
}

/** What the current state means, in one sentence, in place of an alert. */
function feedbackNow(feedback: FeedbackRecord): ReactNode {
	if (activeWithdrawal(feedback) !== undefined) {
		return "It is off the developer’s practice pages and out of the feedback given to later reviews and Heph. The observations behind it are unchanged.";
	}
	if (feedback.dispute !== undefined) {
		return "While the dispute stands, a later review of the same work does not raise the same point again.";
	}
	if (feedback.deliveryState === "AWAITING_APPROVAL") {
		return "It waits for an admin to approve or reject it.";
	}
	if (isDeliveryInProgress(feedback)) {
		return deliveryInProgressLine(feedback);
	}
	return undefined;
}

/**
 * What "still on its way" means on this channel. Only a comment on the work is retried; a practice
 * pages card is delivered when the developer opens it, and a conversation piece when Heph raises it.
 */
function deliveryInProgressLine(feedback: FeedbackRecord): string {
	switch (feedback.channel) {
		case "IN_APP": {
			return "It is ready on the developer’s practice pages. It counts as delivered when they open it.";
		}
		case "IN_CHAT": {
			return "It is ready for the developer’s next conversation with Heph, which is what delivers it.";
		}
		case "IN_CONTEXT": {
			return "Delivery is still in progress. This updates as the remaining comments are tried again.";
		}
	}
}

function feedbackTechnical(feedback: FeedbackRecord): TechnicalFact[] {
	const facts: TechnicalFact[] = [
		{ label: "Feedback ID", value: <code>{feedback.id}</code>, copy: feedback.id },
		{ label: "Review ID", value: <code>{feedback.agentJobId}</code>, copy: feedback.agentJobId },
		{ label: "Delivery state", value: <code>{feedback.deliveryState}</code> },
	];
	if (feedback.suppressionReason !== undefined) {
		facts.push({ label: "Withheld reason", value: <code>{feedback.suppressionReason}</code> });
	}
	if (hasText(feedback.threadKey)) {
		facts.push({
			label: "Thread key",
			value: <code>{feedback.threadKey}</code>,
			copy: feedback.threadKey,
		});
	}
	if (hasText(feedback.replacesId)) {
		facts.push({
			label: "Replaces",
			value: <code>{feedback.replacesId}</code>,
			copy: feedback.replacesId,
		});
	}
	for (const placement of feedback.placements) {
		const ref = placement.postedCommentRef ?? placement.mentorEntryId;
		if (hasText(ref)) {
			facts.push({
				label: `${PLACEMENT_DEFS[placement.placementType].label} reference`,
				value: <code>{ref}</code>,
				copy: ref,
			});
		}
	}
	for (const [index, dispatch] of feedback.dispatches.entries()) {
		const label = feedback.dispatches.length === 1 ? "Delivery" : `Delivery ${index + 1}`;
		facts.push({ label: `${label} ID`, value: <code>{dispatch.id}</code>, copy: dispatch.id });
		if (hasText(dispatch.deliveredExternalRef)) {
			facts.push({
				label: `${label} reference`,
				value: <code>{dispatch.deliveredExternalRef}</code>,
				copy: dispatch.deliveredExternalRef,
			});
		}
	}
	if (feedback.approval?.actorAccountId !== undefined) {
		facts.push({ label: "Decided by account", value: String(feedback.approval.actorAccountId) });
	}
	return facts;
}

/**
 * Only a practice-page card that is waiting to be read or already shown can be taken back; a
 * withdrawn one can always be restored.
 */
function canWithdraw(feedback: FeedbackRecord) {
	return (
		feedback.channel === "IN_APP" &&
		(activeWithdrawal(feedback) !== undefined ||
			feedback.deliveryState === "PREPARED" ||
			feedback.deliveryState === "DELIVERED")
	);
}

function WithdrawalPopover({
	withdrawn,
	disabled,
	onSubmit,
}: {
	withdrawn: boolean;
	disabled: boolean;
	onSubmit: (reason: string) => Promise<unknown>;
}) {
	return (
		<CorrectionReasonPopover
			copy={
				withdrawn
					? {
							trigger: "Restore feedback",
							title: "Restore this feedback",
							description:
								"It returns to the developer’s practice pages if its observations can still be shown there. Nothing is sent again.",
							placeholder: "Why the feedback was right after all…",
						}
					: {
							trigger: "Withdraw feedback",
							title: "Withdraw this feedback",
							description:
								"It comes off the developer’s practice pages and out of the feedback given to later reviews and Heph. The observations behind it are unchanged. A developer who already saw it sees that it was withdrawn, not your reason.",
							placeholder: "What the feedback got wrong…",
						}
			}
			icon={withdrawn ? Undo2Icon : EyeOffIcon}
			destructive={!withdrawn}
			name="feedback-withdrawal-reason"
			disabled={disabled}
			onSubmit={onSubmit}
		/>
	);
}
