import { EyeOffIcon, RotateCcwIcon, SearchCheckIcon, Undo2Icon } from "lucide-react";
import { Fragment, type ReactNode } from "react";

import type {
	FeedbackBasis,
	FeedbackCopy,
	FeedbackDecision,
	FeedbackSend,
	ReviewFeedbackDetail,
} from "@/api/types.gen";
import { accountName } from "@/components/admin/users/account-labels";
import { DotSeparator } from "@/components/common/DotSeparator";
import { InlineLink } from "@/components/common/InlineLink";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { StatusBadge } from "@/components/common/StatusBadge";
import type { LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { Section } from "@/components/layout/Section";
import { SectionStack } from "@/components/layout/SectionStack";
import { FeedbackChecks } from "@/components/practice-trace/FeedbackChecks";
import { UNVERIFIED_HISTORY_NOTE } from "@/components/practice-vocabulary/claim-currentness";
import {
	DELIVERY_STATE_DEFS,
	deliveryOutcome,
	isPartialDelivery,
	partialDeliveryDescription,
	withdrawalMark,
} from "@/components/practice-vocabulary/delivery-state-defs";
import {
	DEVELOPER_ANSWER_DEFS,
	developerAnswer,
} from "@/components/practice-vocabulary/developer-answer-defs";
import { codeCitationLocator } from "@/components/practice-vocabulary/evidence-source-defs";
import {
	copyCountsSentence,
	FEEDBACK_COPY_KIND_DEFS,
	FEEDBACK_COPY_STATE_DEFS,
	isPartialPackage,
} from "@/components/practice-vocabulary/feedback-copy-defs";
import {
	FEEDBACK_DECISION_DEFS,
	type FeedbackDecisionReason,
	feedbackDecisionReasonLabel,
} from "@/components/practice-vocabulary/feedback-decision-defs";
import type { FeedbackDestination } from "@/components/practice-vocabulary/feedback-destination-defs";
import { FEEDBACK_LOCATION_DEFS } from "@/components/practice-vocabulary/feedback-location-defs";
import { FEEDBACK_RESOLUTION_DEFS } from "@/components/practice-vocabulary/feedback-resolution-defs";
import {
	copiesStillOnWork,
	type DeliveryCheck,
	deliveryCheck,
	FEEDBACK_SEND_ACTION_LABELS,
	FEEDBACK_SEND_STATE_DEFS,
	type WithdrawalCorrection,
	withdrawalCorrection,
} from "@/components/practice-vocabulary/feedback-send-defs";
import { FEEDBACK_USEFULNESS_DEFS } from "@/components/practice-vocabulary/feedback-usefulness-defs";
import { WITHHOLDING_REASON_DEFS } from "@/components/practice-vocabulary/withholding-defs";
import { Button } from "@/components/ui/button";
import { DrawerBody, DrawerFooter } from "@/components/ui/drawer";
import { hasText } from "@/lib/text";

import { CorrectionReasonPopover, type CorrectionReasonCopy } from "./CorrectionReason";
import { FeedbackBody } from "./FeedbackBody";
import { FeedbackLocationLabel } from "./FeedbackLocationLabel";
import { levelFoldKey } from "./level-folds";
import { LevelBodySkeleton } from "./LevelBodySkeleton";
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
import { resultSummary } from "./result-words";
import { developerLabel } from "./review-format";
import { problemLevel, resultLevel } from "./review-levels";
import { ResultOutcome, ReviewStatusMark } from "./ReviewBadges";
import { ReviewDayTime } from "./ReviewDayTime";
import { ReviewFact, ReviewFactGrid } from "./ReviewFactGrid";
import { ReviewItemLink } from "./ReviewItem";
import { ReviewLevelHeader } from "./ReviewLevelHeader";
import { ReviewPackage } from "./ReviewPackage";
import { ReviewPerson } from "./ReviewPerson";

type FeedbackRecord = ReviewFeedbackDetail;

export interface FeedbackLevelProps {
	nested?: boolean;
	path: LevelPath;
	feedback: PanelState<{ feedback: FeedbackRecord }>;
	isDeciding: boolean;
	onApprove: () => void;
	onReject: (reason: FeedbackDecisionReason, note?: string) => void;
	/** Where this feedback sits among all awaiting approval; absent when it awaits none. */
	queue?: ApprovalQueue;
	/**
	 * Withdraws (`true`) or restores (`false`) the feedback, with the admin's note. Settles when the
	 * server has answered; the form keeps the note until it succeeded.
	 */
	onChangeWithdrawal: (withdrawn: boolean, note: string) => Promise<unknown>;
	isChangingWithdrawal: boolean;
	/**
	 * Tries again to remove withdrawn feedback from the work, keeping the withdrawal. Offered only
	 * while a posted copy waits on a removal the provider did not acknowledge.
	 */
	onRetryWithdrawal: () => void;
	isRetryingWithdrawal: boolean;
	/**
	 * Asks the provider again whether a comment whose write was never confirmed appeared on the work.
	 * It posts nothing. Offered only while the post has finished with that write still unknown.
	 */
	onCheckDelivery: () => void;
	isCheckingDelivery: boolean;
}

function inOrder(copies: readonly FeedbackCopy[]): FeedbackCopy[] {
	return [...copies].sort((a, b) => a.position - b.position);
}

/**
 * Who the feedback is for and where it goes, in the words of its row on the Feedback list. Never
 * its text: "What it says" shows that once, and the text can be private to the developer or hidden.
 */
function feedbackTitle(feedback: FeedbackRecord): string {
	const where = FEEDBACK_LOCATION_DEFS[feedback.location].label;
	return `For ${developerLabel(feedback.developer)} · ${where}`;
}

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
	onRetryWithdrawal,
	isRetryingWithdrawal,
	onCheckDelivery,
	isCheckingDelivery,
}: FeedbackLevelProps) {
	const feedback = state.status === "ready" ? state.feedback : undefined;
	const withdrawal = feedback && withdrawalMark(feedback);
	let flag: ReactNode;
	if (withdrawal !== undefined) {
		flag = <StatusBadge def={withdrawal} />;
	} else if (feedback !== undefined && feedback.disagreedAt !== null) {
		flag = <StatusBadge def={DEVELOPER_ANSWER_DEFS.DISAGREED} />;
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
						figure={
							isPartialPackage(feedback.copyCounts)
								? copyCountsSentence(feedback.copyCounts)
								: undefined
						}
						at={
							feedback.deliveredAt === null
								? { label: "composed", date: feedback.createdAt }
								: {
										label: feedback.state === "DELIVERED" ? "delivered" : "first appeared",
										date: feedback.deliveredAt,
									}
						}
					/>
				)
			}
			title={feedback && feedbackTitle(feedback)}
			description={feedback?.item && <ReviewItemLink item={feedback.item} />}
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
	const awaitingApproval = record.state === "AWAITING_APPROVAL";
	// Both recoveries queue the feedback's one send, so while either is asked for, or the withdrawal
	// changes, the other would only be refused.
	const recovering = isCheckingDelivery || isRetryingWithdrawal || isChangingWithdrawal;
	return (
		<>
			{header}
			<DrawerBody className="flex flex-col gap-8 pt-2">
				{record.unverifiedHistory && (
					<p className="text-sm text-muted-foreground">{UNVERIFIED_HISTORY_NOTE}</p>
				)}
				<SectionStack>
					<SheetSection part="what" foldKey={levelFoldKey("feedback", "what")}>
						<FeedbackWhat feedback={record} />
					</SheetSection>
					<SheetSection part="state" foldKey={levelFoldKey("feedback", "state")}>
						<SheetHistory events={feedbackHistory(record)} now={feedbackNow(record)} />
					</SheetSection>
					{awaitingApproval ? (
						<ProposalPackage feedback={record} />
					) : (
						<SheetSection
							part="details"
							title="What it says"
							foldKey={levelFoldKey("feedback", "says")}
						>
							{record.copies.length > 0 ? (
								<ReviewPackage feedback={record} />
							) : (
								<FeedbackBody feedback={record} />
							)}
						</SheetSection>
					)}
					{record.basis.length > 0 && (
						<Section
							level={3}
							size="lg"
							title="Based on"
							foldKey={levelFoldKey("feedback", "based-on")}
							className="min-w-0"
						>
							<FeedbackBasisList basis={record.basis} />
						</Section>
					)}
					{record.checks.length > 0 && (
						<Section
							level={3}
							size="lg"
							title="Checks"
							foldKey={levelFoldKey("feedback", "checks")}
							className="min-w-0"
						>
							<FeedbackChecks checks={record.checks} inChecksSection />
						</Section>
					)}
					<SheetTechnical
						facts={feedbackTechnical(record)}
						foldKey={levelFoldKey("feedback", "technical")}
					/>
				</SectionStack>
			</DrawerBody>
			{awaitingApproval ? (
				// The decision is the footer like every sheet's actions, so it stays in reach while the
				// package above it is read; approving it unread is what the expanded package designs out.
				<DrawerFooter className="sm:flex-wrap sm:items-center">
					<p className="text-xs text-muted-foreground sm:mr-auto">
						Approval authorizes every copy above. Feedback checks still run before anything is
						posted.
					</p>
					<div
						role="group"
						aria-label="Decide on this feedback"
						className="flex flex-wrap items-center justify-end gap-2"
					>
						<ProposalDecision
							canApprove={record.copies.length > 0}
							isDeciding={isDeciding}
							onApprove={onApprove}
							onReject={onReject}
							queue={queue}
						/>
					</div>
				</DrawerFooter>
			) : (
				canChangeWithdrawal(record) && (
					<DrawerFooter>
						{deliveryCheck(record) === "available" && (
							<Button variant="outline" disabled={recovering} onClick={onCheckDelivery}>
								<SearchCheckIcon aria-hidden />
								Check delivery
							</Button>
						)}
						{withdrawalCorrection(record) === "retryable" && (
							<Button variant="outline" disabled={recovering} onClick={onRetryWithdrawal}>
								<RotateCcwIcon aria-hidden />
								Retry withdrawal
							</Button>
						)}
						<CorrectionReasonPopover
							copy={withdrawalCopy(record)}
							icon={record.withdrawn ? Undo2Icon : EyeOffIcon}
							destructive={!record.withdrawn}
							name="feedback-withdrawal-reason"
							disabled={isChangingWithdrawal}
							onSubmit={async (note) => onChangeWithdrawal(!record.withdrawn, note)}
						/>
					</DrawerFooter>
				)
			)}
		</>
	);
}

function FeedbackWhat({ feedback }: { feedback: FeedbackRecord }) {
	return (
		<ReviewFactGrid>
			<ReviewFact label="Developer">
				<ReviewPerson person={feedback.developer} />
			</ReviewFact>
			<ReviewFact label="Destination">
				<FeedbackLocationLabel feedback={feedback} />
			</ReviewFact>
			{hasText(feedback.item?.title) && (
				<ReviewFact label="Work">
					<span className="break-words">{feedback.item.title}</span>
				</ReviewFact>
			)}
		</ReviewFactGrid>
	);
}

interface BasisResult {
	resultId: string;
	result: FeedbackBasis["result"];
	whole: boolean;
	problemIds: string[];
}

// Resting on a whole result and on problems of it are separate bases of one result, so the result
// is named once with each of them under it, in the order the feedback first names that result.
function basisByResult(basis: readonly FeedbackBasis[]): BasisResult[] {
	const results = new Map<string, BasisResult>();
	for (const entry of [...basis].sort((a, b) => a.ordinal - b.ordinal)) {
		const group = results.get(entry.resultId) ?? {
			resultId: entry.resultId,
			result: entry.result,
			whole: false,
			problemIds: [],
		};
		results.set(entry.resultId, group);
		if (entry.problemId === null) {
			group.whole = true;
		} else if (!group.problemIds.includes(entry.problemId)) {
			group.problemIds.push(entry.problemId);
		}
	}
	return [...results.values()];
}

/**
 * The results this feedback was written from, and whether it rests on the whole of each, on
 * problems of it, or on both. Each opens in this sheet's place; Back returns here.
 */
function FeedbackBasisList({ basis }: { basis: readonly FeedbackBasis[] }) {
	return (
		<ul aria-label="Based on" className="flex min-w-0 flex-col gap-3">
			{basisByResult(basis).map(({ resultId, result, whole, problemIds }) => {
				const summary = resultSummary(result);
				return (
					<li key={resultId} className="min-w-0 space-y-1">
						<ResultOutcome result={result} />
						<p className="text-sm font-medium break-words">
							<DetailStackLink
								entry={resultLevel(resultId)}
								single
								className="underline-offset-4 hover:underline"
							>
								{summary}
							</DetailStackLink>
						</p>
						<p className="text-xs text-muted-foreground">
							{result.practiceName}
							{whole && (
								<>
									<DotSeparator inline />
									The whole observation
								</>
							)}
							{problemIds.map((problemId, index) => {
								const label =
									index === 0
										? "One problem of this observation"
										: "Another problem of this observation";
								// Counts the problems this feedback rests on, not their place in the result.
								const reference =
									problemIds.length === 1
										? ""
										: ` (reference ${String(index + 1)} of ${String(problemIds.length)})`;
								return (
									<Fragment key={problemId}>
										<DotSeparator inline />
										<DetailStackLink
											entry={problemLevel(problemId)}
											single
											aria-label={`${label}: ${summary}${reference}`}
											className="underline-offset-4 hover:underline"
										>
											{label}
										</DetailStackLink>
									</Fragment>
								);
							})}
						</p>
					</li>
				);
			})}
		</ul>
	);
}

function copyAnchor(copy: FeedbackCopy): string | undefined {
	if (!hasText(copy.path)) {
		return undefined;
	}
	if (copy.startLine === null) {
		return copy.path;
	}
	return codeCitationLocator({
		path: copy.path,
		startLine: copy.startLine,
		endLine: copy.endLine ?? undefined,
	});
}

function LastError({ error }: { error: string }) {
	return (
		<div className="mt-1">
			Last error:
			<pre className="mt-1 max-h-48 overflow-auto rounded-md bg-muted/50 p-2 text-xs break-words whitespace-pre-wrap">
				{error}
			</pre>
		</div>
	);
}

function feedbackHistory(feedback: FeedbackRecord): SheetEvent[] {
	const events: SheetEvent[] = [
		{ key: "composed", at: feedback.createdAt, title: "Composed" },
		...feedback.decisions.map(decisionEvent),
	];
	const { stopReason } = feedback;
	if (stopReason !== null && feedback.state !== "AWAITING_APPROVAL") {
		const stop = feedback.checks.find((check) => !check.allowed && check.reason === stopReason);
		events.push({
			key: "stopped",
			at: stop?.checkedAt,
			title: DELIVERY_STATE_DEFS[feedback.state].label,
			detail: WITHHOLDING_REASON_DEFS[stopReason],
		});
	}
	const onWork = feedback.destination === "ON_WORK";
	const receipts = onWork
		? inOrder(feedback.copies).flatMap((copy) => copyEvent(copy, stopReason) ?? [])
		: [];
	events.push(...receipts);
	if (feedback.deliveredAt !== null && receipts.length === 0) {
		events.push({ key: "delivered", at: feedback.deliveredAt, title: "Delivered" });
	}
	const failed = feedback.copies.some((copy) => copy.state === "FAILED");
	const correction = withdrawalCorrection(feedback);
	const check = deliveryCheck(feedback);
	events.push(
		...feedback.sends.flatMap((send) => sendEvent(send, failed, correction, check) ?? []),
	);
	const response = responseEvent(feedback);
	if (response !== undefined) {
		events.push(response);
	}
	return events;
}

function decisionEvent(decision: FeedbackDecision): SheetEvent {
	const { reasonCode, note } = decision;
	return {
		key: decision.id,
		at: decision.decidedAt,
		title: FEEDBACK_DECISION_DEFS[decision.action].label,
		by: decision.actor === null ? ERASED_ACCOUNT : accountName(decision.actor),
		detail:
			reasonCode !== null || hasText(note) ? (
				<>
					{reasonCode !== null && (
						<span className="block">Reason: {feedbackDecisionReasonLabel(reasonCode)}</span>
					)}
					{hasText(note) && <span className="block whitespace-pre-wrap">Note: “{note}”</span>}
				</>
			) : undefined,
	};
}

function copyEvent(
	copy: FeedbackCopy,
	feedbackStopReason: FeedbackRecord["stopReason"],
): SheetEvent | undefined {
	const kind = FEEDBACK_COPY_KIND_DEFS[copy.kind].label;
	const anchor = copyAnchor(copy);
	const where = anchor !== undefined && <code className="break-all">{anchor}</code>;
	switch (copy.state) {
		case "PLANNED": {
			return undefined;
		}
		case "POSTED":
		case "RETRACTED": {
			return {
				key: copy.id,
				at: copy.postedAt ?? undefined,
				title: `${kind} posted`,
				detail: (
					<span className="flex flex-wrap items-center gap-x-2">
						{where}
						{hasText(copy.externalUrl) && (
							<InlineLink href={copy.externalUrl} external>
								Open the comment
							</InlineLink>
						)}
						{hasText(copy.lastError) && <LastError error={copy.lastError} />}
					</span>
				),
			};
		}
		case "WRITE_STARTED":
		case "FAILED": {
			const def = FEEDBACK_COPY_STATE_DEFS[copy.state];
			return {
				key: copy.id,
				at: copy.writeStartedAt ?? undefined,
				title: `${kind} ${def.label.toLowerCase()}`,
				detail: (
					<>
						{where}
						<span className="block">{def.description}</span>
						{hasText(copy.lastError) && <LastError error={copy.lastError} />}
					</>
				),
			};
		}
		case "WITHHELD": {
			const reason = copy.stopReason;
			return {
				key: copy.id,
				at: undefined,
				title: `${kind} withheld`,
				detail: (
					<>
						{where}
						{reason !== null && reason !== feedbackStopReason && (
							<span className="block">{WITHHOLDING_REASON_DEFS[reason]}</span>
						)}
					</>
				),
			};
		}
	}
}

function sendEvent(
	send: FeedbackSend,
	copiesFailed: boolean,
	correction: WithdrawalCorrection | undefined,
	check: DeliveryCheck | undefined,
): SheetEvent | undefined {
	const done = send.state === "DONE";
	// A post claimed while a write is unknown only reads it back. One already being sent may
	// instead be making its first write, and the record does not say which.
	const checking = send.action === "POST" && check === "checking";
	const writing = checking && send.state === "SENDING";
	let title: string;
	let action = FEEDBACK_SEND_ACTION_LABELS[send.action];
	if (writing) {
		title = "Writing or checking";
		action = "Write the comment, or read back the one already started";
	} else if (checking) {
		title =
			send.state === "PENDING" ? "Waiting to check" : FEEDBACK_SEND_STATE_DEFS[send.state].label;
		action = "Read the work for the unconfirmed comment";
	} else if (!done) {
		title = FEEDBACK_SEND_STATE_DEFS[send.state].label;
	} else if (
		send.action === "RETRACT" &&
		(correction === "retryable" || correction === "needsAttention")
	) {
		title = "Retraction incomplete";
	} else if (send.action === "RETRACT") {
		title = FEEDBACK_COPY_STATE_DEFS.RETRACTED.label;
	} else if (copiesFailed) {
		title = "Stopped trying";
	} else {
		return undefined;
	}
	return {
		key: `send-${send.action}`,
		at: send.updatedAt,
		title,
		detail: (
			<>
				<span className="block">
					{action} · {attemptsLabel(send.attemptCount)}
				</span>
				{checking && (
					<span className="block">
						{writing ? "The record does not say which." : "This check posts nothing."}
					</span>
				)}
				{send.state === "UNCERTAIN" && (
					<span className="block">{FEEDBACK_SEND_STATE_DEFS.UNCERTAIN.description}</span>
				)}
				{!done && (
					<span className="block">
						Next attempt <ReviewDayTime at={send.nextAttemptAt} className="tabular-nums" />
					</span>
				)}
				{hasText(send.lastError) && <LastError error={send.lastError} />}
			</>
		),
	};
}

function attemptsLabel(count: number): string {
	if (count === 0) {
		return "not tried yet";
	}
	return count === 1 ? "1 attempt" : `${count} attempts`;
}

function responseEvent(feedback: FeedbackRecord): SheetEvent | undefined {
	const { response } = feedback;
	const answer = developerAnswer(response);
	if (answer === undefined || response === null) {
		return undefined;
	}
	const { resolution, usefulness, comment } = response;
	const facts = [
		resolution !== null && resolution !== "DISAGREE"
			? FEEDBACK_RESOLUTION_DEFS[resolution].label
			: undefined,
		usefulness === null ? undefined : FEEDBACK_USEFULNESS_DEFS[usefulness].label,
	].filter(hasText);
	return {
		key: "response",
		at: response.createdAt ?? undefined,
		title: DEVELOPER_ANSWER_DEFS[answer].label,
		by: developerLabel(feedback.developer),
		detail: (
			<>
				{facts.length > 0 && <span className="block">{facts.join(" · ")}</span>}
				{hasText(comment) && (
					<blockquote className="mt-1 border-l-2 pl-3 break-words whitespace-pre-wrap text-foreground">
						{comment}
					</blockquote>
				)}
			</>
		),
	};
}

const WITHDRAWN_NOW: Record<FeedbackDestination, string> = {
	ON_WORK:
		"It is withdrawn. Copies it posted on the work are retracted there, and their receipts stay in this history. The observations behind it are unchanged.",
	PRACTICE_PAGE:
		"It is off the developer’s practice pages and out of the feedback given to later reviews and conversations. The observations behind it are unchanged.",
	CONVERSATION:
		"It is withdrawn, so Hephaestus does not raise it in a conversation. The observations behind it are unchanged.",
};

/** Where removing withdrawn feedback from the work stands; the copy counts come from their receipts. */
function withdrawnOnWorkNow(feedback: FeedbackRecord): string {
	const remaining = copiesStillOnWork(feedback.copies).length;
	const one = remaining === 1;
	const copies = one ? "one copy" : `${String(remaining)} copies`;
	// An unknown write is settled by reading the work again, not by a removal.
	const check = deliveryCheck(feedback);
	if (check === "available") {
		return `It is withdrawn, but ${copies} may still be on the work, because a write was never confirmed. Check delivery to find out. A comment found there is then retracted. The observations behind it are unchanged.`;
	}
	if (check === "checking") {
		return "It is withdrawn. Hephaestus is confirming whether a comment it began writing appeared on the work. A comment found there is then retracted. The observations behind it are unchanged.";
	}
	switch (withdrawalCorrection(feedback)) {
		case "pending": {
			return "It is withdrawn. Hephaestus is still removing the copies it posted on the work. The observations behind it are unchanged.";
		}
		case "retryable": {
			return `It is withdrawn, but ${copies} may still be on the work. At least one removal is unconfirmed. Check each copy above for details. The observations behind it are unchanged.`;
		}
		case "needsAttention": {
			return `It is withdrawn, but ${copies} may still be on the work, and Hephaestus cannot remove ${one ? "it" : "them"}. The observations behind it are unchanged.`;
		}
		case "complete":
		case undefined: {
			return WITHDRAWN_NOW.ON_WORK;
		}
	}
}

/** What the current state means, in one sentence, in place of an alert. */
function feedbackNow(feedback: FeedbackRecord): ReactNode {
	if (feedback.withdrawn) {
		return feedback.destination === "ON_WORK"
			? withdrawnOnWorkNow(feedback)
			: WITHDRAWN_NOW[feedback.destination];
	}
	if (feedback.disagreedAt !== null) {
		return "While the developer’s disagreement stands, a later review of the same work does not raise the same point again.";
	}
	switch (feedback.state) {
		case "AWAITING_APPROVAL": {
			return "It waits for a workspace admin to approve or reject it.";
		}
		case "READY": {
			return readyLine(feedback);
		}
		case "UNCONFIRMED": {
			return unconfirmedLine(feedback);
		}
		case "FAILED": {
			return isPartialDelivery(feedback) ? partialDeliveryDescription(feedback) : undefined;
		}
		case "DELIVERED":
		case "WITHHELD":
		case "REJECTED":
		case "REPLACED": {
			return undefined;
		}
	}
}

function unconfirmedLine(feedback: FeedbackRecord): string {
	if (feedback.destination !== "ON_WORK") {
		return "A conversation linked it, but its appearance was not recorded, so it does not count as delivered.";
	}
	const check = deliveryCheck(feedback);
	if (isPartialDelivery(feedback)) {
		const description = partialDeliveryDescription(feedback);
		switch (check) {
			case "available": {
				return `${description} Check delivery to find out whether the remaining copies appeared. Until then, later reviews post no new feedback for this developer on this work.`;
			}
			case "checking": {
				return `${description} Hephaestus is checking whether the remaining copies appeared on the work.`;
			}
			case undefined: {
				return description;
			}
		}
	}
	// Only the approval stage holds later feedback; preparation and the write stop it instead.
	switch (check) {
		case "available": {
			return "A comment was being written to the work, but the provider has not confirmed whether it appeared. It does not count as delivered until that is known. Until then, later reviews post no new feedback for this developer on this work.";
		}
		case "checking": {
			return "Hephaestus is confirming whether a comment it began writing appeared on the work. Until the provider confirms it, it does not count as delivered.";
		}
		case undefined: {
			return "A write to the work was not confirmed, so it does not count as delivered.";
		}
	}
}

function readyLine(feedback: FeedbackRecord): string {
	switch (feedback.destination) {
		case "PRACTICE_PAGE": {
			return "It is ready on the developer’s practice pages. It counts as delivered when they open it.";
		}
		case "CONVERSATION": {
			return "It is ready for the developer’s next conversation with Hephaestus, which is what delivers it.";
		}
		case "ON_WORK": {
			return feedback.sends.some((send) => send.state === "UNCERTAIN")
				? "A write was not confirmed. The provider is checked before anything is posted again."
				: "Delivery is still in progress. This updates as the copies not yet posted are tried again.";
		}
	}
}

function feedbackTechnical(feedback: FeedbackRecord): TechnicalFact[] {
	const facts: TechnicalFact[] = [
		{ label: "Feedback ID", value: <code>{feedback.id}</code>, copy: feedback.id },
		{ label: "Review ID", value: <code>{feedback.reviewId}</code>, copy: feedback.reviewId },
		{ label: "Delivery state", value: <code>{feedback.state}</code> },
	];
	if (feedback.stopReason !== null) {
		facts.push({ label: "Stop reason", value: <code>{feedback.stopReason}</code> });
	}
	if (hasText(feedback.contentDigest)) {
		facts.push({
			label: "Content digest",
			value: <code>{feedback.contentDigest}</code>,
			copy: feedback.contentDigest,
		});
	}
	if (hasText(feedback.replacesId)) {
		facts.push({
			label: "Replaces",
			value: <code>{feedback.replacesId}</code>,
			copy: feedback.replacesId,
		});
	}
	for (const [index, entry] of [...feedback.basis]
		.sort((a, b) => a.ordinal - b.ordinal)
		.entries()) {
		const number = feedback.basis.length === 1 ? "" : ` ${String(index + 1)}`;
		facts.push({
			label: `Basis observation${number}`,
			value: <code>{entry.resultId}</code>,
			copy: entry.resultId,
		});
		if (entry.problemId !== null) {
			facts.push({
				label: `Basis problem${number}`,
				value: <code>{entry.problemId}</code>,
				copy: entry.problemId,
			});
		}
	}
	const copies = inOrder(feedback.copies);
	for (const copy of copies) {
		const ref = copy.externalRef ?? copy.mentorEntryId;
		if (hasText(ref)) {
			const sameKind = copies.filter((other) => other.kind === copy.kind);
			const kind = FEEDBACK_COPY_KIND_DEFS[copy.kind].label;
			const label = sameKind.length === 1 ? kind : `${kind} ${sameKind.indexOf(copy) + 1}`;
			facts.push({ label: `${label} reference`, value: <code>{ref}</code>, copy: ref });
		}
	}
	return facts;
}

function canChangeWithdrawal(feedback: FeedbackRecord): boolean {
	return (
		feedback.withdrawn ||
		feedback.state === "READY" ||
		feedback.state === "DELIVERED" ||
		feedback.state === "UNCONFIRMED" ||
		isPartialDelivery(feedback)
	);
}

const WITHDRAW_DESCRIPTIONS: Record<FeedbackDestination, string> = {
	ON_WORK:
		"Copies already posted on the work are retracted there, and nothing more is posted. Their receipts stay on this sheet, and the observations behind it are unchanged.",
	PRACTICE_PAGE:
		"It comes off the developer’s practice pages and out of the feedback given to later reviews and conversations. The observations behind it are unchanged. A developer who already saw it sees that it was withdrawn, not your reason.",
	CONVERSATION:
		"Hephaestus does not raise it in a conversation, and it leaves the feedback given to later reviews. The observations behind it are unchanged.",
};

// No removal can reach a comment whose write is unknown: it has no provider reference until found.
const UNKNOWN_WRITE_WITHDRAW_DESCRIPTION =
	"An unconfirmed comment may still be on the work. Hephaestus can retract that comment only if a check finds it. Receipts and observations are kept.";

function withdrawalCopy(feedback: FeedbackRecord): CorrectionReasonCopy {
	if (feedback.withdrawn) {
		return {
			trigger: "Restore feedback",
			title: "Restore this feedback",
			description:
				feedback.destination === "PRACTICE_PAGE"
					? "It returns to the developer’s practice pages if its observations can still be shown there. Nothing is sent again."
					: "The withdrawal ends. It is sent again only after every check passes.",
			placeholder: "Why the feedback was right after all…",
		};
	}
	let description = WITHDRAW_DESCRIPTIONS[feedback.destination];
	if (deliveryCheck(feedback) !== undefined) {
		// Posted copies beside the unknown one are still retracted the usual way.
		description = feedback.copies.some((copy) => copy.state === "POSTED")
			? `Copies already posted on the work are retracted there. ${UNKNOWN_WRITE_WITHDRAW_DESCRIPTION}`
			: UNKNOWN_WRITE_WITHDRAW_DESCRIPTION;
	}
	return {
		trigger: "Withdraw feedback",
		title: "Withdraw this feedback",
		description,
		placeholder: "What the feedback got wrong…",
	};
}
