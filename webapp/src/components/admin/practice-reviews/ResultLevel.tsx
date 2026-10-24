import { BadgeCheckIcon, CircleSlashIcon, Undo2Icon } from "lucide-react";
import type { ReactNode } from "react";

import { cn } from "cn";
import type {
	OpenQuestion,
	ResultMark,
	ReviewBoundFeedback,
	ReviewResultDetail,
} from "@/api/types.gen";
import { accountName } from "@/components/admin/users/account-labels";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { StatusBadge } from "@/components/common/StatusBadge";
import { UNTRUSTED_MARKDOWN_PROSE, UntrustedMarkdown } from "@/components/common/UntrustedMarkdown";
import type { LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { Section } from "@/components/layout/Section";
import { SectionStack } from "@/components/layout/SectionStack";
import { ProblemList } from "@/components/practice-problems/ProblemList";
import { NON_CURRENT } from "@/components/practice-vocabulary/claim-currentness";
import {
	deliveryOutcome,
	withdrawalMark,
} from "@/components/practice-vocabulary/delivery-state-defs";
import { FEEDBACK_DESTINATION_DEFS } from "@/components/practice-vocabulary/feedback-destination-defs";
import {
	DISAGREEMENT_DEFS,
	REMOVE_MARK_ACTION,
	RESULT_MARK_ACTIONS,
	RESULT_MARK_DEFS,
	type ResultMarkKind,
} from "@/components/practice-vocabulary/result-mark-defs";
import { REVIEW_ORIGIN_DEFS } from "@/components/practice-vocabulary/review-origin-defs";
import { Badge } from "@/components/ui/badge";
import { DrawerBody, DrawerFooter } from "@/components/ui/drawer";
import { hasText } from "@/lib/text";

import { type CorrectionReasonCopy, CorrectionReasonPopover } from "./CorrectionReason";
import { levelFoldKey } from "./level-folds";
import { LevelBodySkeleton } from "./LevelBodySkeleton";
import {
	ERASED_ACCOUNT,
	type SheetEvent,
	SheetHistory,
	SheetSection,
	SheetStatusLine,
	SheetTechnical,
} from "./RecordSheet";
import { RESULT_WORDS_UNAVAILABLE, resultSummary } from "./result-words";
import { citationLocation, ResultEvidence } from "./ResultEvidence";
import { feedbackLevel, problemLevel } from "./review-levels";
import { ResultOutcome } from "./ReviewBadges";
import { ReviewFact, ReviewFactGrid } from "./ReviewFactGrid";
import { ReviewItemLink } from "./ReviewItem";
import { ReviewLevelHeader } from "./ReviewLevelHeader";
import { ReviewPerson } from "./ReviewPerson";

const HIDDEN_EVIDENCE = "The evidence of this observation is hidden.";

const RESULT_END_SENTENCES: Record<NonNullable<ReviewResultDetail["endReason"]>, string> = {
	REPLACED: "A later review of the same work replaced it.",
	OUTRANKED: "A review ordered later already held the current observation, so this one is history.",
	WORK_CHANGED: "The work changed after this observation.",
	REPAIRED: "A later review found the work repaired.",
	REMOVED: "The work it is about was removed.",
};

export interface ResultLevelProps {
	nested?: boolean;
	path: LevelPath;
	result: PanelState<{ result: ReviewResultDetail }>;
	/**
	 * Marks the result with the admin's reason. Settles when the server has answered; the form keeps
	 * the reason until it succeeded.
	 */
	onMark: (kind: ResultMarkKind, reason: string) => Promise<unknown>;
	onRemoveMark: (markId: string, reason: string) => Promise<unknown>;
	isMarking: boolean;
}

function activeMark(result: ReviewResultDetail, kind: ResultMarkKind): ResultMark | undefined {
	return result.marks.find((mark) => mark.kind === kind && mark.removedAt === null);
}

function resultFlag(result: ReviewResultDetail): ReactNode {
	if (activeMark(result, "INCORRECT") !== undefined) {
		return <StatusBadge def={RESULT_MARK_DEFS.INCORRECT} />;
	}
	if (result.disagreements.length > 0) {
		return <StatusBadge def={DISAGREEMENT_DEFS.DISAGREED} />;
	}
	if (result.currentness !== "CURRENT") {
		const config = NON_CURRENT[result.currentness];
		return <Badge variant={config.badgeVariant}>{config.badge}</Badge>;
	}
	return undefined;
}

// Developer ratings and private notes stay private; disagreements answer this result and appear in full.
export function ResultLevel({
	nested,
	path,
	result: state,
	onMark,
	onRemoveMark,
	isMarking,
}: ResultLevelProps) {
	const result = state.status === "ready" ? state.result : undefined;
	const header = (
		<ReviewLevelHeader
			nested={nested}
			path={path}
			kind="result"
			loading={state.status === "loading"}
			chips={
				result && (
					<SheetStatusLine
						status={<ResultOutcome result={result} problems={result.problems.length} />}
						flag={resultFlag(result)}
						at={{ label: "recorded", date: result.recordedAt }}
					/>
				)
			}
			title={result === undefined ? undefined : resultSummary(result)}
			description={result && <ReviewItemLink item={result.item} />}
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
							title="We could not load this observation"
							onRetry={state.onRetry}
						/>
					) : (
						<LevelBodySkeleton />
					)}
				</DrawerBody>
			</>
		);
	}

	const { result: record } = state;
	const { problems } = record;
	const citations = record.evidence?.citations ?? [];
	const openQuestions = record.evidence?.openQuestions ?? [];
	const incorrect = activeMark(record, "INCORRECT");
	const accepted = activeMark(record, "ACCEPTED");

	return (
		<>
			{header}
			<DrawerBody className="pt-2">
				<SectionStack>
					<SheetSection part="what" foldKey={levelFoldKey("result", "what")}>
						<ReviewFactGrid>
							<ReviewFact label="Practice">
								<span className="break-words">{record.practiceName}</span>
							</ReviewFact>
							<ReviewFact label="Developer">
								<ReviewPerson person={record.developer} />
							</ReviewFact>
							{hasText(record.item.title) && (
								<ReviewFact label="Reviewed work">
									<span className="break-words">{record.item.title}</span>
								</ReviewFact>
							)}
							<ReviewFact label="Started by">
								<span className="space-y-0.5">
									<span className="block">{REVIEW_ORIGIN_DEFS[record.origin].label}</span>
									<span className="block text-muted-foreground">
										{REVIEW_ORIGIN_DEFS[record.origin].description}
									</span>
								</span>
							</ReviewFact>
						</ReviewFactGrid>
					</SheetSection>

					<SheetSection part="state" foldKey={levelFoldKey("result", "state")}>
						<SheetHistory events={resultHistory(record)} now={resultNow(record)} />
					</SheetSection>

					<SheetSection part="details" foldKey={levelFoldKey("result", "details")}>
						<div className="space-y-6">
							{hasText(record.explanation) && (
								// Every outcome carries an explanation, so its heading names none: a met or
								// inapplicable observation raised nothing.
								<Section level={4} size="sm" title="Why this observation">
									{/* The review writes its explanation in Markdown, as the developer's own row shows it. */}
									<div className={cn(UNTRUSTED_MARKDOWN_PROSE, "text-sm")}>
										<UntrustedMarkdown headingLevel={5}>{record.explanation}</UntrustedMarkdown>
									</div>
								</Section>
							)}
							{problems.length > 0 && (
								<Section level={4} size="sm" title="Problems">
									<ProblemList
										problems={problems}
										open={problemLevel}
										evidence={(problem) => (
											<ResultEvidence
												citations={problem.citations}
												hidden={record.evidenceHidden}
												headingLevel={5}
											/>
										)}
									/>
								</Section>
							)}
							{openQuestions.length > 0 && (
								<Section level={4} size="sm" title="Open questions">
									<ul aria-label="Open questions" className="space-y-4 text-sm">
										{openQuestions.map((question, index) => (
											<OpenQuestionItem
												key={`${question.openQuestion}:${index}`}
												question={question}
											/>
										))}
									</ul>
								</Section>
							)}
							{/* A not met result's passages are its problems'. Its own section stands only when it
						    cites something of its own, or when nothing else on the sheet would say that
						    nothing was quoted. Hidden evidence is neither: the server sends none of it. */}
							{record.evidenceHidden ? (
								<Section level={4} size="sm" title="Evidence">
									<p className="text-sm text-muted-foreground">
										{record.summary === null ? RESULT_WORDS_UNAVAILABLE : HIDDEN_EVIDENCE}
									</p>
								</Section>
							) : (
								(citations.length > 0 || problems.length === 0) && (
									<Section level={4} size="sm" title="Evidence">
										<ResultEvidence citations={citations} headingLevel={5} />
									</Section>
								)
							)}
							{/* Only what the record holds: none listed is no claim that none was written. */}
							{record.feedback.length > 0 && (
								<Section level={4} size="sm" title="Feedback">
									<ResultFeedback feedback={record.feedback} />
								</Section>
							)}
						</div>
					</SheetSection>

					<SheetTechnical
						foldKey={levelFoldKey("result", "technical")}
						facts={[
							{ label: "Observation ID", value: <code>{record.id}</code>, copy: record.id },
							{ label: "Review ID", value: <code>{record.reviewId}</code>, copy: record.reviewId },
							{
								label: "Practice version",
								value:
									record.practiceVersionNumber === null
										? "—"
										: String(record.practiceVersionNumber),
							},
							{
								label: "Version",
								value: record.itemVersion === null ? "—" : <code>{record.itemVersion}</code>,
							},
							{ label: "Currentness", value: <code>{record.currentness}</code> },
						]}
					/>
				</SectionStack>
			</DrawerBody>
			<DrawerFooter>
				{incorrect === undefined ? (
					<>
						<MarkPopover
							kind="ACCEPTED"
							active={accepted}
							disabled={isMarking}
							onMark={onMark}
							onRemoveMark={onRemoveMark}
						/>
						<MarkPopover
							kind="INCORRECT"
							active={undefined}
							disabled={isMarking}
							onMark={onMark}
							onRemoveMark={onRemoveMark}
						/>
					</>
				) : (
					<MarkPopover
						kind="INCORRECT"
						active={incorrect}
						disabled={isMarking}
						onMark={onMark}
						onRemoveMark={onRemoveMark}
					/>
				)}
			</DrawerFooter>
		</>
	);
}

/**
 * The feedback written from this result: where each went and how far it got. Each opens its own
 * sheet in this one's place, where its full delivery story and any reason it stopped are; Back
 * returns here.
 */
function ResultFeedback({ feedback }: { feedback: readonly ReviewBoundFeedback[] }) {
	return (
		<ul aria-label="Feedback written from this observation" className="flex min-w-0 flex-col gap-2">
			{feedback.map((entry) => {
				const destination = FEEDBACK_DESTINATION_DEFS[entry.destination].label;
				// The bound record carries no stop reason, so a refined state reads as its base state.
				const facts = { ...entry, stopReason: null };
				const delivery = deliveryOutcome(facts);
				const withdrawal = withdrawalMark(facts);
				return (
					<li key={entry.id} className="flex min-w-0 flex-wrap items-center gap-2 text-sm">
						<DetailStackLink
							entry={feedbackLevel(entry.id)}
							single
							aria-label={
								withdrawal === undefined
									? `${destination}, ${delivery.label}`
									: `${destination}, ${delivery.label}, ${withdrawal.label.toLowerCase()}`
							}
							className="font-medium underline-offset-4 hover:underline"
						>
							{destination}
						</DetailStackLink>
						<StatusBadge def={delivery} />
						{withdrawal !== undefined && <StatusBadge def={withdrawal} />}
					</li>
				);
			})}
		</ul>
	);
}

function actorName(account: ResultMark["createdBy"]): string {
	return account === null ? ERASED_ACCOUNT : accountName(account);
}

export function resultHistory(result: ReviewResultDetail): SheetEvent[] {
	const DisagreedIcon = DISAGREEMENT_DEFS.DISAGREED.icon;
	const events: SheetEvent[] = [{ key: "recorded", at: result.recordedAt, title: "Recorded" }];
	for (const disagreement of result.disagreements) {
		const destination = FEEDBACK_DESTINATION_DEFS[disagreement.destination].label.toLowerCase();
		events.push({
			key: `disagreed-${disagreement.feedbackId}`,
			at: disagreement.respondedAt,
			icon: <DisagreedIcon aria-hidden className="size-3.5 text-warning" />,
			title: `The developer disagreed with feedback ${destination}`,
			detail: (
				<blockquote className="mt-1 border-l-2 pl-3 break-words whitespace-pre-wrap text-foreground">
					{disagreement.comment}
				</blockquote>
			),
		});
	}
	for (const mark of result.marks) {
		events.push({
			key: mark.id,
			at: mark.createdAt,
			title: RESULT_MARK_DEFS[mark.kind].label,
			by: actorName(mark.createdBy),
			detail: `Reason: “${mark.reason}”`,
		});
		if (mark.removedAt !== null) {
			events.push({
				key: `${mark.id}-removed`,
				at: mark.removedAt,
				title: "Mark removed",
				by: actorName(mark.removedBy),
				detail: hasText(mark.removalReason) ? `Reason: “${mark.removalReason}”` : undefined,
			});
		}
	}
	if (result.endedAt !== null && result.endReason !== null) {
		events.push({
			key: "ended",
			at: result.endedAt,
			title: "No longer current",
			detail: RESULT_END_SENTENCES[result.endReason],
		});
	}
	return events;
}

function resultNow(result: ReviewResultDetail): ReactNode {
	if (activeMark(result, "INCORRECT") !== undefined) {
		return RESULT_MARK_DEFS.INCORRECT.description;
	}
	if (result.disagreements.length > 0) {
		return DISAGREEMENT_DEFS.DISAGREED.description;
	}
	if (result.currentness !== "CURRENT") {
		return NON_CURRENT[result.currentness].description;
	}
	return undefined;
}

// Open questions identify uncertainty; they cannot become feedback claims.
function OpenQuestionItem({ question }: { question: OpenQuestion }) {
	const location = question.citation === null ? undefined : citationLocation(question.citation);
	return (
		<li className="space-y-1 break-words">
			<p className="font-medium">{question.openQuestion}</p>
			<p className="text-muted-foreground">Would settle it: {question.wouldSettleIt}</p>
			{location !== undefined && (
				<p className="text-xs text-muted-foreground">
					At <code className="break-all">{location}</code>
				</p>
			)}
		</li>
	);
}

const MARK_COPY: Record<ResultMarkKind, CorrectionReasonCopy> = {
	INCORRECT: {
		trigger: RESULT_MARK_ACTIONS.INCORRECT,
		title: "Mark this observation incorrect",
		description:
			"The review record stays as it was. Its pending feedback stops, and the developer sees your reason next to the observation.",
		placeholder: "What the observation got wrong…",
	},
	ACCEPTED: {
		trigger: RESULT_MARK_ACTIONS.ACCEPTED,
		title: "Mark this observation accepted",
		description: "It records that the observation is right. No feedback changes.",
		placeholder: "Why the observation is right…",
	},
};

const REMOVE_COPY: Record<ResultMarkKind, CorrectionReasonCopy> = {
	INCORRECT: {
		trigger: REMOVE_MARK_ACTION,
		title: "Remove the incorrect mark",
		description:
			"The observation counts again from now on. Feedback that was stopped stays stopped, and nothing is sent again.",
		placeholder: "Why the observation was right after all…",
	},
	ACCEPTED: {
		trigger: REMOVE_MARK_ACTION,
		title: "Remove the accepted mark",
		description: "The observation goes back to the open lists. No feedback changes.",
		placeholder: "Why the observation is no longer accepted…",
	},
};

function MarkPopover({
	kind,
	active,
	disabled,
	onMark,
	onRemoveMark,
}: {
	kind: ResultMarkKind;
	active: ResultMark | undefined;
	disabled: boolean;
	onMark: ResultLevelProps["onMark"];
	onRemoveMark: ResultLevelProps["onRemoveMark"];
}) {
	if (active !== undefined) {
		return (
			<CorrectionReasonPopover
				copy={REMOVE_COPY[kind]}
				icon={Undo2Icon}
				destructive={false}
				name={`result-${kind.toLowerCase()}-removal-reason`}
				disabled={disabled}
				onSubmit={async (reason) => onRemoveMark(active.id, reason)}
			/>
		);
	}
	return (
		<CorrectionReasonPopover
			copy={MARK_COPY[kind]}
			icon={kind === "INCORRECT" ? CircleSlashIcon : BadgeCheckIcon}
			destructive={kind === "INCORRECT"}
			name={`result-${kind.toLowerCase()}-reason`}
			disabled={disabled}
			onSubmit={async (reason) => onMark(kind, reason)}
		/>
	);
}
