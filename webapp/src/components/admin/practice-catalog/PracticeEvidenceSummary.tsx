import type { ComponentProps } from "react";

import { cn } from "cn";
import type {
	PracticeAutomatedReviewPolicy,
	PracticeAutomatedReviewValidation,
	PracticeBinding,
	PracticeEvidenceSourceOption,
	PracticeSignalOption,
} from "@/api/types.gen";
import { momentDef } from "@/components/admin/practice-catalog/occasion-moments";
import { Badge } from "@/components/ui/badge";

import { evidenceSourceLabel, mentoringSupportLabel } from "./evidence-presentation";

/**
 * Total over the wire union, so a status the API learns to send cannot arrive unlabelled.
 *
 * `AUTHOR_DECLARED` is currently the only value the server can produce, so this reads the same on
 * every practice. A badge that never varies is not information — it is rendered as a sentence that
 * says what to do about it, not as a chip that looks like a warning.
 */
const VALIDATION_DEFS: Record<
	PracticeAutomatedReviewValidation["status"],
	{ sentence: string; variant: ComponentProps<typeof Badge>["variant"] }
> = {
	AUTHOR_DECLARED: {
		sentence:
			"Nobody has measured how often this practice is right. Its author described what it checks; that description has not been tested against real work. This is why a practice starts by asking you to approve each piece of feedback.",
		variant: "outline",
	},
};

function signalLabel(signal: string, signals: readonly PracticeSignalOption[]) {
	return signals.find((option) => option.signal === signal)?.displayName ?? signal;
}

interface OccasionSummaryProps {
	binding: PracticeBinding;
	sources: readonly PracticeEvidenceSourceOption[];
	signals: readonly PracticeSignalOption[];
}

/**
 * Listed per occasion rather than merged into one set: a merged list would claim every review reads
 * everything any one of them does.
 */
function OccasionSummary({ binding, sources, signals }: OccasionSummaryProps) {
	const required = binding.needs.filter((need) => need.stance !== "CONTEXTUAL");
	const contextual = binding.needs.filter((need) => need.stance === "CONTEXTUAL");
	return (
		<div className="space-y-2 rounded-md border p-3">
			<div className="flex flex-wrap items-center gap-1.5">
				{binding.signals.map((signal) => {
					const Icon = momentDef(signal).icon;
					return (
						<Badge key={signal} variant="secondary">
							<Icon className="size-3" aria-hidden />
							{signalLabel(signal, signals)}
						</Badge>
					);
				})}
				{binding.onDrafts && <Badge variant="outline">Drafts included</Badge>}
			</div>
			<dl className="grid gap-x-4 gap-y-1.5 sm:grid-cols-[6.5rem_1fr]">
				<dt className="text-muted-foreground">Must have</dt>
				<dd className="flex flex-wrap gap-1.5">
					{required.length > 0 ? (
						required.map((need) => (
							<Badge key={need.sourceKind} variant="secondary">
								{evidenceSourceLabel(need.sourceKind, sources)}
								{/* "captured whole", never "nothing in the world is missing": completeness is a
								    property of one capture of one source. */}
								{need.stance === "EXHAUSTIVE" && (
									<span className="text-muted-foreground">· captured whole</span>
								)}
							</Badge>
						))
					) : (
						<span className="text-muted-foreground">Nothing</span>
					)}
				</dd>
				<dt className="text-muted-foreground">May also use</dt>
				<dd className="flex flex-wrap gap-1.5">
					{contextual.length > 0 ? (
						contextual.map((need) => (
							<Badge key={need.sourceKind} variant="outline">
								{evidenceSourceLabel(need.sourceKind, sources)}
							</Badge>
						))
					) : (
						<span className="text-muted-foreground">Nothing</span>
					)}
				</dd>
			</dl>
		</div>
	);
}

export interface PracticeEvidenceSummaryProps {
	policy: PracticeAutomatedReviewPolicy;
	bindings: readonly PracticeBinding[];
	validation: PracticeAutomatedReviewValidation;
	sources: readonly PracticeEvidenceSourceOption[];
	signals?: readonly PracticeSignalOption[];
	workTypeLabel: string;
	className?: string;
	showValidation?: boolean;
	showValidationDetails?: boolean;
}

export interface PracticeAutomatedReviewValidationSummaryProps {
	validation: PracticeAutomatedReviewValidation;
}

/** Says what the reader should do about the accuracy of this practice, in a sentence. */
export function PracticeAutomatedReviewValidationNote({
	validation,
}: PracticeAutomatedReviewValidationSummaryProps) {
	return (
		<p className="text-muted-foreground text-sm">{VALIDATION_DEFS[validation.status].sentence}</p>
	);
}

export function PracticeAutomatedReviewValidationSummary({
	validation,
}: PracticeAutomatedReviewValidationSummaryProps) {
	return (
		<div className="space-y-1 text-sm">
			<PracticeAutomatedReviewValidationNote validation={validation} />
			{/* Monospaced and breakable: nobody reads a digest, they compare one against another. */}
			<p className="text-muted-foreground text-xs">
				Rules <code className="break-all">{validation.reviewRuleFingerprint}</code> · policy{" "}
				<code className="break-all">{validation.policyDigest}</code>
			</p>
		</div>
	);
}

export function PracticeEvidenceSummary({
	policy,
	bindings,
	validation,
	sources,
	signals = [],
	workTypeLabel,
	className,
	showValidation = true,
	showValidationDetails = true,
}: PracticeEvidenceSummaryProps) {
	return (
		<dl className={cn("grid gap-x-6 gap-y-3 text-sm sm:grid-cols-2", className)}>
			<div>
				<dt className="font-medium">Work it reviews</dt>
				<dd className="text-muted-foreground">
					{workTypeLabel}
					<span className="mt-1 block text-xs">
						Evidence written against source contract {policy.sourceContractVersion}
					</span>
				</dd>
			</div>
			<div>
				<dt className="font-medium">Mentoring support</dt>
				<dd className="text-muted-foreground">
					{mentoringSupportLabel(policy.automatedReview)}
					<span className="mt-1 block text-xs">
						Developers, peers, and mentors may use context no automated review can reach.
					</span>
				</dd>
			</div>
			<div>
				<dt className="font-medium">When evidence requirements are not met</dt>
				<dd className="text-muted-foreground">Skip this practice rather than guess</dd>
			</div>
			<div className="sm:col-span-2">
				<dt className="font-medium">When it is reviewed, and what it reads</dt>
				<dd className="mt-1 space-y-2">
					{bindings.length > 0 ? (
						bindings.map((binding, index) => (
							<OccasionSummary
								key={binding.signals.join(",") || index}
								binding={binding}
								sources={sources}
								signals={signals}
							/>
						))
					) : (
						<span className="text-muted-foreground">No occasion starts a review</span>
					)}
				</dd>
			</div>
			{showValidation && (
				<div className="sm:col-span-2">
					<dt className="font-medium">How reliable this is</dt>
					<dd>
						{showValidationDetails ? (
							<PracticeAutomatedReviewValidationSummary validation={validation} />
						) : (
							<PracticeAutomatedReviewValidationNote validation={validation} />
						)}
					</dd>
				</div>
			)}
			{policy.knownLimitations.length > 0 && (
				<div className="sm:col-span-2">
					<dt className="font-medium">What the evidence cannot support</dt>
					<dd>
						<ul className="space-y-1">
							{policy.knownLimitations.map((limitation) => (
								<li key={limitation.code}>{limitation.description}</li>
							))}
						</ul>
					</dd>
				</div>
			)}
		</dl>
	);
}
