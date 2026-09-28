import { CircleSlashIcon, MessageSquareTextIcon, Undo2Icon } from "lucide-react";
import { useId, useState } from "react";

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
import {
	MARKED_INCORRECT_DEF,
	PROVIDER_COPY_IN_FORCE,
	PROVIDER_COPY_RESTORED,
} from "@/components/practice-vocabulary/observation-invalidation-defs";
import { withholdingReasonSentence } from "@/components/practice-vocabulary/withholding-defs";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { DrawerBody, DrawerFooter } from "@/components/ui/drawer";
import {
	Empty,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import { Field, FieldDescription, FieldLabel } from "@/components/ui/field";
import {
	Popover,
	PopoverContent,
	PopoverDescription,
	PopoverHeader,
	PopoverTitle,
	PopoverTrigger,
} from "@/components/ui/popover";
import { Spinner } from "@/components/ui/spinner";
import { Textarea } from "@/components/ui/textarea";
import { hasText } from "@/lib/text";

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
import { ReviewRow, ReviewRowList, ReviewRowMeta } from "./ReviewRow";

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
					<ObservationEvidence evidence={record.evidence} detector={record.evidence?.detector} />
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
										<DetailStackLink entry={feedbackLevel(feedback.feedbackId)}>
											{/* Named by what it is to *this* observation. Titling it with the delivery
											    place would say nothing about the thing the link opens, and repeat
											    the fact the meta line beside it already carries. */}
											{feedback.role === "PRIMARY"
												? "Feedback about this observation"
												: "Feedback this observation supports"}
										</DetailStackLink>
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

function CorrectionEntry({
	action,
	actor,
	at,
	reason,
	note,
}: {
	action: string;
	actor: string | undefined;
	at: Date;
	reason: string | undefined;
	note?: string;
}) {
	return (
		<li className="space-y-1 rounded-lg border p-3">
			<p className="text-muted-foreground">
				<span className="font-medium text-foreground">{action}</span> by{" "}
				{actor ?? "an account that no longer exists"} <RelativeTime value={at} />
			</p>
			{hasText(reason) && <p className="break-words whitespace-pre-wrap">{reason}</p>}
			{hasText(note) && <p className="text-muted-foreground">{note}</p>}
		</li>
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
	const [open, setOpen] = useState(false);
	const [reason, setReason] = useState("");
	const reasonId = useId();
	const submit = async () => {
		try {
			await onSubmit(reason.trim());
			setReason("");
			setOpen(false);
		} catch {
			// The controller reports the failure; the reason stays for another try.
		}
	};
	const copy = invalidated
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
			};
	return (
		<Popover open={open} onOpenChange={setOpen}>
			<PopoverTrigger render={<Button variant="outline" disabled={disabled} />}>
				{disabled && <Spinner />}
				{!disabled && (invalidated ? <Undo2Icon /> : <CircleSlashIcon />)}
				{copy.trigger}
			</PopoverTrigger>
			<PopoverContent align="end" side="top" className="w-[min(24rem,calc(100vw-2rem))] gap-4 p-4">
				<PopoverHeader>
					<PopoverTitle>{copy.title}</PopoverTitle>
					<PopoverDescription>{copy.description}</PopoverDescription>
				</PopoverHeader>
				<Field>
					<FieldLabel htmlFor={reasonId}>Reason</FieldLabel>
					<Textarea
						id={reasonId}
						name="observation-validity-reason"
						autoComplete="off"
						value={reason}
						onChange={(event) => setReason(event.target.value)}
						maxLength={500}
						placeholder={copy.placeholder}
					/>
					<FieldDescription>Required · {reason.length}/500</FieldDescription>
				</Field>
				<div className="flex justify-end gap-2 border-t pt-3">
					<Button variant="ghost" size="sm" onClick={() => setOpen(false)}>
						Cancel
					</Button>
					<Button
						variant={invalidated ? "default" : "destructive"}
						size="sm"
						disabled={disabled || !hasText(reason.trim())}
						onClick={() => {
							void submit();
						}}
					>
						{disabled && <Spinner />}
						{copy.trigger}
					</Button>
				</div>
			</PopoverContent>
		</Popover>
	);
}
