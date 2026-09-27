import { Link } from "@tanstack/react-router";
import { CircleSlashIcon, MessageSquareTextIcon, Undo2Icon } from "lucide-react";
import { useId, useState } from "react";

import type {
	GetPracticeReviewObservationResponse,
	ObservationInvalidation,
	Practice,
} from "@/api/types.gen";
import { MissingRecordEmpty } from "@/components/common/MissingRecordEmpty";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { RelativeTime } from "@/components/common/RelativeTime";
import { StatusBadge } from "@/components/common/StatusBadge";
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
	/**
	 * Invalidates (`false`) or restores (`true`) the observation, with the admin's reason. Settles when the
	 * server has answered; the form keeps the reason until it succeeded.
	 */
	onChangeValidity: (valid: boolean, reason: string) => Promise<unknown>;
	isChangingValidity: boolean;
}

export function ObservationDetailPage({
	workspaceSlug,
	search,
	observation,
	isLoading,
	error,
	onRetry,
	practices,
	onChangeValidity,
	isChangingValidity,
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
	const artifactSlug = reviewArtifactTypeSlug(observation.reviewedWork.kind);
	const latest = observation.invalidations.at(0);
	const inForce = latest !== undefined && latest.restoredAt === undefined ? latest : undefined;

	return (
		<article className="max-w-4xl min-w-0 space-y-8">
			{breadcrumbs}
			<ReviewDetailHeader
				chips={
					<>
						<ObservationResultBadge observation={observation} />
						<ClaimCurrentnessBadge currentness={observation.claimCurrentness} />
						{inForce && <StatusBadge def={MARKED_INCORRECT_DEF} />}
					</>
				}
				actions={
					<ValidityPopover
						invalidated={inForce !== undefined}
						disabled={isChangingValidity}
						onSubmit={async (reason) => onChangeValidity(inForce !== undefined, reason)}
					/>
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
			{inForce && <InvalidationAlert invalidation={inForce} />}

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
						<ReviewArtifactLink reviewedWork={observation.reviewedWork} />
						{hasText(observation.reviewedWork.title) && (
							<p className="break-words text-muted-foreground">{observation.reviewedWork.title}</p>
						)}
						{artifactSlug && (
							<Link
								className="font-medium underline underline-offset-4"
								to="/w/$workspaceSlug/admin/practices/reviews/targets/$artifactKind/$artifactId"
								params={{
									workspaceSlug,
									artifactKind: artifactSlug,
									artifactId: observation.reviewedWork.id,
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

			{observation.invalidations.length > 0 && (
				<section aria-labelledby="corrections-heading" className="space-y-3">
					<h3 id="corrections-heading" className="text-lg font-semibold">
						Corrections
					</h3>
					<ol aria-labelledby="corrections-heading" className="space-y-3 text-sm">
						{observation.invalidations.flatMap((invalidation) => [
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
				</section>
			)}
		</article>
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
			// The route reports the failure; the reason stays for another try.
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
			<PopoverContent align="end" className="w-[min(24rem,calc(100vw-2rem))] gap-4 p-4">
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
