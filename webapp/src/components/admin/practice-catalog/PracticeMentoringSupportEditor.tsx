import { Plus, Trash2 } from "lucide-react";
import { useRef } from "react";

import type { PracticeAutomatedReviewPolicy } from "@/api/types.gen";
import { Button } from "@/components/ui/button";
import {
	Field,
	FieldContent,
	FieldDescription,
	FieldError,
	FieldLabel,
	FieldTitle,
} from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { hasText } from "@/lib/text";

type MentoringSupport = "AI_SUPPORTED" | "HUMAN_CONTEXT_REQUIRED" | "GUIDANCE_ONLY";

const LIMITATION_CODE = /^[A-Z][A-Z0-9_]{2,63}$/u;

export function mentoringSupportOf(policy: PracticeAutomatedReviewPolicy): MentoringSupport {
	if (policy.automatedReview.mode === "NONE") {
		return "GUIDANCE_ONLY";
	}
	if (policy.automatedReview.evidenceSufficiency === "DECLARED_EVIDENCE_INSUFFICIENT") {
		return "HUMAN_CONTEXT_REQUIRED";
	}
	return "AI_SUPPORTED";
}

/**
 * Derived from the text, never random: the server digests code and description together, and that
 * digest decides whether a curated update changed review rules. Retyping the same sentence must
 * yield the same code.
 */
export function limitationCodeFor(description: string) {
	const slug = description
		.toUpperCase()
		.replaceAll(/[^A-Z0-9]+/gu, "_")
		.replaceAll(/^_+|_+$/gu, "")
		.slice(0, 63);
	return LIMITATION_CODE.test(slug) ? slug : `LIMITATION_${fnv1a(description)}`;
}

/** Deterministic, non-cryptographic fallback for text that cannot form a legal code. */
function fnv1a(input: string) {
	let hash = 0x81_1c_9d_c5;
	for (let index = 0; index < input.length; index += 1) {
		// oxlint-disable-next-line unicorn/prefer-code-point -- The code is stored, so the hash stays over UTF-16 units; code points would change it for an astral character.
		hash ^= input.charCodeAt(index);
		hash = Math.imul(hash, 0x01_00_01_93) >>> 0;
	}
	return hash.toString(16).toUpperCase().padStart(8, "0");
}

function humanReviewReasonIsMissing(policy: PracticeAutomatedReviewPolicy) {
	return (
		policy.automatedReview.evidenceSufficiency === "DECLARED_EVIDENCE_INSUFFICIENT" &&
		policy.insufficiencyReason !== undefined &&
		!policy.insufficiencyReason.description.trim()
	);
}

export function practicePolicyErrorTarget(policy: PracticeAutomatedReviewPolicy) {
	return humanReviewReasonIsMissing(policy)
		? "practice-human-review-reason"
		: "practice-support-heading";
}

/**
 * Only what holds however the review was occasioned. What a review reads belongs to the occasion and
 * is checked per binding.
 */
export function practicePolicyError(policy: PracticeAutomatedReviewPolicy) {
	if (policy.automatedReview.mode === "NONE" && policy.knownLimitations.length > 0) {
		return "Guidance only cannot declare evidence limitations.";
	}
	for (const limitation of policy.knownLimitations) {
		if (!LIMITATION_CODE.test(limitation.code)) {
			return "Limitation identifiers must use 3–64 uppercase letters, numbers, and underscores.";
		}
		if (!limitation.description.trim() || limitation.description.length > 500) {
			return "Each limitation needs a description of 1–500 characters.";
		}
	}
	if (
		policy.automatedReview.evidenceSufficiency === "DECLARED_EVIDENCE_INSUFFICIENT" &&
		policy.knownLimitations.length === 0
	) {
		return "Explain at least one limitation that requires additional context.";
	}
	return;
}

export interface PracticeMentoringSupportEditorProps {
	value: PracticeAutomatedReviewPolicy;
	/** The recommended frame for the selected work type, restored when leaving guidance only. */
	recommended: PracticeAutomatedReviewPolicy;
	supportedAutomatedReviewModes: readonly PracticeAutomatedReviewPolicy["automatedReview"]["mode"][];
	onChange: (value: PracticeAutomatedReviewPolicy) => void;
	error?: string;
	disabled?: boolean;
}

/** Practice-wide on purpose: whether a review is attempted cannot differ between its occasions. */
export function PracticeMentoringSupportEditor({
	value,
	recommended,
	supportedAutomatedReviewModes,
	onChange,
	error,
	disabled = false,
}: PracticeMentoringSupportEditorProps) {
	const savedPolicy = useRef<PracticeAutomatedReviewPolicy | undefined>(
		value.automatedReview.mode === "NONE" ? undefined : value,
	);
	const addLimitationButton = useRef<HTMLButtonElement>(null);
	const support = mentoringSupportOf(value);
	const supportsAiReview = supportedAutomatedReviewModes.includes("LANGUAGE_MODEL");
	const humanReviewReasonMissing = humanReviewReasonIsMissing(value);
	const showHumanReviewReasonError = Boolean(error) && humanReviewReasonMissing;

	const focusLimitation = (focusTarget: string) => {
		requestAnimationFrame(() => {
			if (focusTarget === "add") {
				addLimitationButton.current?.focus();
			} else {
				document.getElementById(`practice-limitation-description-${focusTarget}`)?.focus();
			}
		});
	};

	const updateSupport = (next: MentoringSupport) => {
		if (next === "GUIDANCE_ONLY") {
			if (value.automatedReview.mode !== "NONE") {
				savedPolicy.current = value;
			}
			onChange({
				...value,
				automatedReview: { mode: "NONE", evidenceSufficiency: "NONE" },
				knownLimitations: [],
				insufficiencyReason: undefined,
			});
			return;
		}
		const restored =
			value.automatedReview.mode === "NONE" ? (savedPolicy.current ?? recommended) : value;
		const needsHumanContext = next === "HUMAN_CONTEXT_REQUIRED";
		onChange({
			...restored,
			automatedReview: {
				mode: "LANGUAGE_MODEL",
				evidenceSufficiency: needsHumanContext
					? "DECLARED_EVIDENCE_INSUFFICIENT"
					: "SUFFICIENT_WHEN_REQUIREMENTS_MET",
			},
			knownLimitations: restored.knownLimitations.filter((limitation) =>
				limitation.description.trim(),
			),
			insufficiencyReason: needsHumanContext
				? (restored.insufficiencyReason ?? { code: limitationCodeFor(""), description: "" })
				: undefined,
		});
	};

	return (
		<section
			className="space-y-4"
			aria-labelledby="practice-support-heading"
			aria-describedby={hasText(error) ? "practice-support-error" : undefined}
		>
			<div>
				<h2 id="practice-support-heading" className="text-lg font-semibold" tabIndex={-1}>
					How this practice is mentored
				</h2>
				<p className="text-sm text-muted-foreground">
					Choose the responsible level of support. This does not limit what a developer, peer, or
					human mentor can observe.
				</p>
			</div>

			<RadioGroup
				value={support}
				onValueChange={(next) => updateSupport(next)}
				className="gap-3"
				aria-label="How this practice is mentored"
			>
				<FieldLabel htmlFor="practice-mentoring-ai-supported">
					<Field orientation="horizontal" data-disabled={disabled || !supportsAiReview}>
						<RadioGroupItem
							id="practice-mentoring-ai-supported"
							value="AI_SUPPORTED"
							disabled={disabled || !supportsAiReview}
						/>
						<FieldContent>
							<FieldTitle>AI-supported mentoring</FieldTitle>
							<FieldDescription>
								An automated review reads the connected work and offers practice-focused guidance.
								It skips the practice when required evidence is unavailable.
								{!supportsAiReview && (
									<> This work type has no AI review available on this instance.</>
								)}
							</FieldDescription>
						</FieldContent>
					</Field>
				</FieldLabel>
				<FieldLabel htmlFor="practice-mentoring-human-context">
					<Field orientation="horizontal" data-disabled={disabled}>
						<RadioGroupItem
							id="practice-mentoring-human-context"
							value="HUMAN_CONTEXT_REQUIRED"
							disabled={disabled}
						/>
						<FieldContent>
							<FieldTitle>Human review needed</FieldTitle>
							<FieldDescription>
								Connected work cannot support a responsible conclusion, so no automated review runs
								and a developer, peer, or mentor reviews it instead.
							</FieldDescription>
						</FieldContent>
					</Field>
				</FieldLabel>
				<FieldLabel htmlFor="practice-mentoring-guidance-only">
					<Field orientation="horizontal" data-disabled={disabled}>
						<RadioGroupItem
							id="practice-mentoring-guidance-only"
							value="GUIDANCE_ONLY"
							disabled={disabled}
						/>
						<FieldContent>
							<FieldTitle>Guidance only</FieldTitle>
							<FieldDescription>
								Keep the criteria and guidance without running any automated review.
							</FieldDescription>
						</FieldContent>
					</Field>
				</FieldLabel>
			</RadioGroup>

			{support === "HUMAN_CONTEXT_REQUIRED" && value.insufficiencyReason && (
				<Field data-invalid={showHumanReviewReasonError || undefined}>
					<FieldLabel htmlFor="practice-human-review-reason">
						Why is human review needed? *
					</FieldLabel>
					<Input
						id="practice-human-review-reason"
						disabled={disabled}
						value={value.insufficiencyReason.description}
						onChange={(event) =>
							onChange({
								...value,
								insufficiencyReason: {
									code: limitationCodeFor(event.target.value),
									description: event.target.value,
								},
							})
						}
						maxLength={500}
						aria-invalid={showHumanReviewReasonError || undefined}
						aria-describedby={
							showHumanReviewReasonError
								? "practice-human-review-reason-error practice-human-review-reason-description"
								: "practice-human-review-reason-description"
						}
					/>
					<FieldDescription id="practice-human-review-reason-description">
						Name the context a person may have that the connected work cannot provide.
					</FieldDescription>
					{showHumanReviewReasonError && (
						<FieldError id="practice-human-review-reason-error">
							Say what a person can see here that the connected work cannot show.
						</FieldError>
					)}
				</Field>
			)}

			{value.automatedReview.mode !== "NONE" && (
				<div className="space-y-3">
					<div>
						<p className="font-medium">What this evidence cannot support</p>
						<p className="text-sm text-muted-foreground">
							State which claims this kind of work cannot support, however a review was occasioned
							and even when every requirement passes.
						</p>
					</div>
					{value.knownLimitations.map((limitation, index) => {
						// Identity for markup is the row's position: the code is derived from the text and
						// would change under the caret on every keystroke.
						const limitationId = String(index);
						return (
							<div
								key={limitationId}
								className="grid gap-3 rounded-lg border p-4 sm:grid-cols-[1fr_auto]"
							>
								<Field>
									<FieldLabel htmlFor={`practice-limitation-description-${limitationId}`}>
										Description <span className="sr-only">for limitation {index + 1}</span>
									</FieldLabel>
									<Input
										disabled={disabled}
										id={`practice-limitation-description-${limitationId}`}
										value={limitation.description}
										onChange={(event) =>
											onChange({
												...value,
												knownLimitations: value.knownLimitations.map((item, itemIndex) =>
													itemIndex === index
														? {
																...item,
																description: event.target.value,
																code: limitationCodeFor(event.target.value),
															}
														: item,
												),
											})
										}
										maxLength={500}
									/>
								</Field>
								<Button
									type="button"
									disabled={disabled}
									variant="ghost"
									size="icon-sm"
									className="self-end"
									onClick={() => {
										const remaining = value.knownLimitations.filter(
											(_, itemIndex) => itemIndex !== index,
										);
										const focusTarget =
											remaining.length === 0
												? "add"
												: String(Math.min(index, remaining.length - 1));
										onChange({ ...value, knownLimitations: remaining });
										focusLimitation(focusTarget);
									}}
									aria-label={`Remove limitation ${index + 1}`}
								>
									<Trash2 className="size-4" />
								</Button>
							</div>
						);
					})}
					<Button
						ref={addLimitationButton}
						type="button"
						disabled={disabled}
						variant="outline"
						size="sm"
						onClick={() => {
							onChange({
								...value,
								knownLimitations: [
									...value.knownLimitations,
									{ code: limitationCodeFor(""), description: "" },
								],
							});
							focusLimitation(String(value.knownLimitations.length));
						}}
					>
						<Plus className="size-4" />
						Add limitation
					</Button>
				</div>
			)}
			{hasText(error) && <FieldError id="practice-support-error">{error}</FieldError>}
		</section>
	);
}
