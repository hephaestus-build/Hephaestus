import { CircleSlashIcon, MessageSquareTextIcon, Undo2Icon } from "lucide-react";

import type {
	GetPracticeReviewObservationResponse,
	ObservationInvalidation,
	Practice,
} from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { RelativeTime } from "@/components/common/RelativeTime";
import { StatusBadge } from "@/components/common/StatusBadge";
import type { LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { Section } from "@/components/layout/Section";
import {
	ClaimCurrentnessAlert,
	ClaimCurrentnessBadge,
} from "@/components/practice-vocabulary/ClaimCurrentness";
import { deliveryOutcome } from "@/components/practice-vocabulary/delivery-outcome-defs";
import { DELIVERY_PLACE_DEFS } from "@/components/practice-vocabulary/delivery-place-defs";
import { DEVELOPER_RESPONSE_DEFS } from "@/components/practice-vocabulary/observation-dispute-defs";
import {
	MARKED_INCORRECT_DEF,
	PROVIDER_COPY_IN_FORCE,
	PROVIDER_COPY_RESTORED,
} from "@/components/practice-vocabulary/observation-invalidation-defs";
import { withholdingReasonSentence } from "@/components/practice-vocabulary/withholding-defs";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { DrawerBody, DrawerFooter } from "@/components/ui/drawer";
import {
	Empty,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import { hasText } from "@/lib/text";

import { CorrectionEntry, CorrectionReasonPopover } from "./CorrectionReason";
import { DisputeAlert } from "./DisputeAlert";
import { LevelBodySkeleton } from "./LevelBodySkeleton";
import { ObservationEvidence } from "./ObservationEvidence";
import { feedbackLevel, workLevel } from "./review-levels";
import { ReviewArtifactLink } from "./ReviewArtifact";
import { ObservationResultBadge } from "./ReviewBadges";
import { ReviewFact, ReviewFactGrid } from "./ReviewFactGrid";
import { ReviewLevelHeader } from "./ReviewLevelHeader";
import { ReviewPerson } from "./ReviewPerson";
import { ReviewPracticeLink } from "./ReviewPracticeLink";
import { ReviewProvenanceLine } from "./ReviewProvenanceLine";
import { ReviewRow, ReviewRowLink, ReviewRowList, ReviewRowMeta } from "./ReviewRow";

export interface ObservationLevelProps {
	nested?: boolean;
	path: LevelPath;
	observation: PanelState<{ observation: GetPracticeReviewObservationResponse }>;
	practices: Practice[] | undefined;
	/**
	 * Invalidates (`false`) or restores (`true`) the observation, with the admin's reason. Settles when the
	 * server has answered; the form keeps the reason until it succeeded.
	 */
	onChangeValidity: (valid: boolean, reason: string) => Promise<unknown>;
	isChangingValidity: boolean;
}

/**
 * One observation: what the review saw, why, from which passages, and what feedback it became. Its
 * one decision — mark it incorrect, or restore it — is the footer.
 */
export function ObservationLevel({
	nested,
	path,
	observation: state,
	practices,
	onChangeValidity,
	isChangingValidity,
}: ObservationLevelProps) {
	const observation = state.status === "ready" ? state.observation : undefined;
	const latest = observation?.invalidations.at(0);
	const inForce = latest !== undefined && latest.restoredAt === undefined ? latest : undefined;
	const header = (
		<ReviewLevelHeader
			nested={nested}
			path={path}
			kind="observation"
			loading={state.status === "loading"}
			chips={
				observation && (
					<>
						<ObservationResultBadge observation={observation} />
						<ClaimCurrentnessBadge currentness={observation.claimCurrentness} />
						{inForce && <StatusBadge def={MARKED_INCORRECT_DEF} />}
						{observation.disputes.length > 0 && (
							<StatusBadge def={DEVELOPER_RESPONSE_DEFS.DISPUTED} />
						)}
					</>
				)
			}
			title={observation?.summary}
			description={
				observation && (
					<ReviewProvenanceLine
						agentJobId={observation.agentJobId}
						verb="Observed"
						at={observation.observedAt}
					/>
				)
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
							title="Couldn't load this observation"
							onRetry={state.onRetry}
						/>
					) : (
						<LevelBodySkeleton />
					)}
				</DrawerBody>
			</>
		);
	}

	const { observation: record } = state;
	const work = workLevel(record.reviewedWork.kind, record.reviewedWork.id);

	return (
		<>
			{header}
			<DrawerBody className="flex flex-col gap-8 pt-2">
				<ClaimCurrentnessAlert currentness={record.claimCurrentness} />
				{inForce && <InvalidationAlert invalidation={inForce} />}
				{record.disputes.map((dispute) => (
					<DisputeAlert key={dispute.feedbackId} dispute={dispute} linkFeedback />
				))}

				<ReviewFactGrid>
					<ReviewFact label="Practice">
						<ReviewPracticeLink
							practiceSlug={record.practiceSlug}
							practiceName={record.practiceName}
							group={record.group}
							practice={practices?.find((practice) => practice.slug === record.practiceSlug)}
						/>
					</ReviewFact>
					<ReviewFact label="Developer">
						<ReviewPerson person={record.subject} />
					</ReviewFact>
					<ReviewFact label="Reviewed work">
						<div className="space-y-1">
							<ReviewArtifactLink reviewedWork={record.reviewedWork} />
							{hasText(record.reviewedWork.title) && (
								<p className="break-words text-muted-foreground">{record.reviewedWork.title}</p>
							)}
							{work && (
								<InlineLink className="font-medium" render={<DetailStackLink entry={work} />}>
									See everything reviewed on this work
								</InlineLink>
							)}
						</div>
					</ReviewFact>
				</ReviewFactGrid>

				{hasText(record.evidenceRationale) && (
					<Section level={3} title="Why this was raised">
						<p className="text-sm leading-relaxed whitespace-pre-wrap">
							{record.evidenceRationale}
						</p>
					</Section>
				)}

				<Section level={3} title="Evidence">
					<ObservationEvidence evidence={record.evidence} />
				</Section>

				<Section level={3} title="Feedback from this observation">
					{record.feedback.length === 0 ? (
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
							{record.feedback.map((feedback) => (
								<ReviewRow
									key={feedback.feedbackId}
									status={deliveryOutcome(feedback)}
									title={
										<ReviewRowLink entry={feedbackLevel(feedback.feedbackId)}>
											{/* Named by what it is to *this* observation. Titling it with the delivery
											    place would say nothing about the thing the link opens, and repeat
											    the fact the meta line beside it already carries. */}
											{feedback.role === "PRIMARY"
												? "Feedback about this observation"
												: "Feedback this observation supports"}
										</ReviewRowLink>
									}
									meta={
										<>
											<ReviewRowMeta items={[DELIVERY_PLACE_DEFS[feedback.channel].label]} />
											{feedback.suppressionReason && (
												<p>{withholdingReasonSentence(feedback.suppressionReason)}</p>
											)}
										</>
									}
								/>
							))}
						</ReviewRowList>
					)}
				</Section>

				{record.invalidations.length > 0 && (
					<Section level={3} title="Corrections">
						<ol className="space-y-3 text-sm">
							{record.invalidations.flatMap((invalidation) => [
								invalidation.restoredAt !== undefined && (
									<CorrectionEntry
										key={`${invalidation.id}-restored`}
										action="Restored"
										actor={invalidation.restoredBy}
										at={invalidation.restoredAt}
										reason={invalidation.restorationReason}
										note={PROVIDER_COPY_RESTORED[invalidation.providerCopy]}
									/>
								),
								<CorrectionEntry
									key={invalidation.id}
									action="Marked incorrect"
									actor={invalidation.invalidatedBy}
									at={invalidation.invalidatedAt}
									reason={invalidation.reason}
								/>,
							])}
						</ol>
					</Section>
				)}
			</DrawerBody>
			<DrawerFooter>
				<ValidityPopover
					invalidated={inForce !== undefined}
					disabled={isChangingValidity}
					onSubmit={async (reason) => onChangeValidity(inForce !== undefined, reason)}
				/>
			</DrawerFooter>
		</>
	);
}

function InvalidationAlert({ invalidation }: { invalidation: ObservationInvalidation }) {
	return (
		<Alert variant="destructive">
			<CircleSlashIcon />
			<AlertTitle>Marked as incorrect</AlertTitle>
			<AlertDescription>
				<p>
					{invalidation.invalidatedBy ?? "A workspace admin"} marked this observation as incorrect{" "}
					<RelativeTime value={invalidation.invalidatedAt} />: “{invalidation.reason}”. It no longer
					counts toward the developer’s standing, their practice page or the mentor, and feedback
					about it that had not reached anyone was stopped. The developer sees it labelled with this
					reason in their review history.
				</p>
				<p>{PROVIDER_COPY_IN_FORCE[invalidation.providerCopy]}</p>
			</AlertDescription>
		</Alert>
	);
}

function ValidityPopover({
	invalidated,
	disabled,
	onSubmit,
}: {
	invalidated: boolean;
	disabled: boolean;
	onSubmit: (reason: string) => Promise<unknown>;
}) {
	return (
		<CorrectionReasonPopover
			copy={
				invalidated
					? {
							trigger: "Restore observation",
							title: "Restore this observation",
							description:
								"It counts again from now on. Feedback that was stopped stays stopped, and nothing is re-sent.",
							placeholder: "Why the observation was right after all…",
						}
					: {
							trigger: "Mark as incorrect",
							title: "Mark this observation as incorrect",
							description:
								"The review record stays as it was. The developer sees your reason next to the observation.",
							placeholder: "What the observation got wrong…",
						}
			}
			icon={invalidated ? Undo2Icon : CircleSlashIcon}
			destructive={!invalidated}
			name="observation-validity-reason"
			disabled={disabled}
			onSubmit={onSubmit}
		/>
	);
}
