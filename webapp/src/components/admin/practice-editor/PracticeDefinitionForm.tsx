import deepEqual from "fast-deep-equal";
import { ChevronRight, RotateCcw } from "lucide-react";
import { useId, useRef, useState } from "react";

import type {
	PracticeAutomatedReviewPolicy,
	PracticePrecondition,
	PracticeEvidenceRequirement,
	PracticeDeliveryBehavior,
	UpdatePracticeRequest,
	PracticeDefinitionOptions,
	PracticeEvidenceOutcome,
	PracticeWorkTypeDefinitionOptions,
} from "@/api/types.gen";
import {
	generateSlug,
	isValidSlug,
	workArtifactHint,
} from "@/components/admin/practice-editor/constants";
import { canAttemptAutomatedReview } from "@/components/admin/practice-editor/evidence-presentation";
import {
	gatePresentation,
	parseGate,
} from "@/components/admin/practice-editor/practice-precondition";
import {
	PracticeMentoringSupportEditor,
	practicePolicyError,
	practicePolicyErrorTarget,
} from "@/components/admin/practice-editor/PracticeMentoringSupportEditor";
import {
	PracticeReviewSettingsEditor,
	type PracticeOccasionMode,
	withoutEvidence,
	withRecommendedEvidence,
} from "@/components/admin/practice-editor/PracticeReviewSettingsEditor";
import {
	artifactKindOfSignals,
	type ReviewSettingsProblem,
	reviewSettingsProblem,
	EMPTY_REVIEW_SETTINGS,
	normalizeReviewSettings,
	orderedWorkTypes,
	recommendedReviewSettings,
	workTypeOptionsFor,
} from "@/components/admin/practice-editor/review-settings";
import { CodeEditor } from "@/components/common/CodeEditor";
import { type FormError, FormErrorSummary } from "@/components/common/FormErrorSummary";
import {
	ACTOR_ROLE_LABELS,
	type ActorRole,
} from "@/components/practice-vocabulary/actor-role-labels";
import { Button } from "@/components/ui/button";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { DrawerBody, DrawerFooter } from "@/components/ui/drawer";
import {
	Field,
	FieldContent,
	FieldDescription,
	FieldError,
	FieldGroup,
	FieldLabel,
	FieldLegend,
	FieldSet,
	FieldTitle,
} from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import {
	Select,
	SelectContent,
	SelectItem,
	SelectTrigger,
	SelectValue,
} from "@/components/ui/select";
import { Separator } from "@/components/ui/separator";
import { Spinner } from "@/components/ui/spinner";
import { Switch } from "@/components/ui/switch";
import { Textarea } from "@/components/ui/textarea";
import { useUnsavedChanges } from "@/hooks/use-unsaved-changes";
import { artifactKindLabel, artifactKindNoun, isKnownArtifactKind } from "@/lib/artifact-kinds";
import { hasText } from "@/lib/text";

type DefinitionChange = NonNullable<UpdatePracticeRequest["definitionChanges"]>[number];

const NO_GROUP = "__none__";

export interface PracticeDefinitionGroupOption {
	slug: string;
	name: string;
}

export interface PracticeDefinitionValue {
	slug: string;
	name: string;
	groupSlug?: string;
	/**
	 * The settings that determine when this practice is reviewed. The kind of
	 * work is read off its signals; it is not carried separately.
	 */
	signals: string[];
	evidenceRequirements: PracticeEvidenceRequirement[];
	reviewWhen: Record<string, string[]>;
	subject: ActorRole;
	precondition?: PracticePrecondition;
	definitionChanges?: DefinitionChange[];
	criteria: string;
	whyItMatters?: string;
	whatGoodLooksLike?: string;
	precomputeScript?: string;
	automatedReviewPolicy: PracticeAutomatedReviewPolicy;
	deliveryBehavior?: PracticeDeliveryBehavior;
}

interface PracticeDefinitionFormBaseProps {
	groups: readonly PracticeDefinitionGroupOption[];
	isPending: boolean;
	disabled?: boolean;
	isSubmitDisabled?: boolean;
	/** Sits at the top of the scrolling body, so a host's banner shares the fields' padding. */
	beforeFields?: React.ReactNode;
	afterFields?: React.ReactNode;
	cancelAction: React.ReactNode;
	/**
	 * Return a promise that rejects when the save failed: the unsaved-changes guard then stays down
	 * from submit until it hears otherwise, so a caller navigating straight after an awaited save is
	 * not asked to discard what it just saved. Returning nothing leaves the guard as it is.
	 */
	onSubmit: (value: PracticeDefinitionValue) => void | Promise<void>;
	definitionOptions: PracticeDefinitionOptions;
	evidenceOutcome?: PracticeEvidenceOutcome;
}

interface PracticeDefinitionFormCreateProps extends PracticeDefinitionFormBaseProps {
	mode: "create";
	initialData?: never;
}

interface PracticeDefinitionFormEditProps extends PracticeDefinitionFormBaseProps {
	mode: "edit";
	initialData: PracticeDefinitionValue;
}

export type PracticeDefinitionFormProps =
	| PracticeDefinitionFormCreateProps
	| PracticeDefinitionFormEditProps;

interface FormState {
	name: string;
	slug: string;
	groupSlug: string;
	/**
	 * Held rather than read off the signals: the occasion's signals are what the author is editing,
	 * and unticking the last of them would otherwise take the kind of work — and with it the editor
	 * that is the only way to tick one again — off the screen.
	 */
	artifactKind: string;
	signals: string[];
	evidenceRequirements: PracticeEvidenceRequirement[];
	reviewWhen: Record<string, string[]>;
	subject: ActorRole;
	precondition?: PracticePrecondition;
	definitionChanges: DefinitionChange[];
	gateText: string;
	criteria: string;
	whyItMatters: string;
	whatGoodLooksLike: string;
	precomputeScript: string;
	automatedReviewPolicy: PracticeAutomatedReviewPolicy;
	deliveryBehavior: PracticeDeliveryBehavior;
}

/** Everything a work type owns, stashed so switching away and back does not discard the work. */
interface WorkTypeDraft {
	signals: string[];
	evidenceRequirements: PracticeEvidenceRequirement[];
	reviewWhen: Record<string, string[]>;
	subject: ActorRole;
	precondition?: PracticePrecondition;
	gateText: string;
	precomputeScript: string;
	automatedReviewPolicy: PracticeAutomatedReviewPolicy;
}

function initialState(
	definitionOptions: PracticeDefinitionOptions,
	initialData?: PracticeDefinitionValue,
): FormState {
	const fallback = orderedWorkTypes(definitionOptions)[0];
	return initialData ? stateOf(initialData, fallback) : blankState(fallback);
}

function blankState(fallback: PracticeWorkTypeDefinitionOptions | undefined): FormState {
	return {
		name: "",
		slug: "",
		groupSlug: NO_GROUP,
		artifactKind: fallback?.artifactKind ?? "",
		...(fallback ? recommendedReviewSettings(fallback) : EMPTY_REVIEW_SETTINGS),
		definitionChanges: [],
		gateText: "",
		criteria: "",
		whyItMatters: "",
		whatGoodLooksLike: "",
		precomputeScript: "",
		automatedReviewPolicy: fallback?.recommendedPolicy ?? EMPTY_POLICY,
		deliveryBehavior: { summaryOnly: false },
	};
}

function stateOf(
	initialData: PracticeDefinitionValue,
	fallback: PracticeWorkTypeDefinitionOptions | undefined,
): FormState {
	return {
		name: initialData.name,
		slug: initialData.slug,
		groupSlug: initialData.groupSlug ?? NO_GROUP,
		artifactKind: artifactKindOfSignals(initialData.signals) ?? fallback?.artifactKind ?? "",
		...normalizeReviewSettings(initialData),
		definitionChanges: [],
		gateText: initialData.precondition ? JSON.stringify(initialData.precondition, null, 2) : "",
		criteria: initialData.criteria,
		whyItMatters: initialData.whyItMatters ?? "",
		whatGoodLooksLike: initialData.whatGoodLooksLike ?? "",
		precomputeScript: initialData.precomputeScript ?? "",
		automatedReviewPolicy: initialData.automatedReviewPolicy,
		deliveryBehavior: { summaryOnly: false, ...initialData.deliveryBehavior },
	};
}

function draftOf(form: FormState): WorkTypeDraft {
	return {
		...normalizeReviewSettings(form),
		gateText: form.gateText,
		precomputeScript: form.precomputeScript,
		automatedReviewPolicy: form.automatedReviewPolicy,
	};
}

/**
 * Guidance-only leads, because it is the only one of the three that forbids evidence outright — and
 * `canAttemptAutomatedReview` can still say yes to a policy whose mode is NONE.
 */
function occasionModeOf(
	policy: PracticeAutomatedReviewPolicy,
	canRunMentoring: boolean,
): PracticeOccasionMode {
	if (policy.automatedReview.mode === "NONE") {
		return "guidance-only";
	}
	return canRunMentoring ? "reviewed" : "human-review";
}

interface FormErrors {
	name?: string;
	slug?: string;
	criteria?: string;
	policy?: string;
	reviewSettings?: ReviewSettingsProblem;
	gate?: string;
	subject?: string;
	delivery?: string;
	/**
	 * One list, in the order the fields appear, so the summary reads down the form and the first
	 * entry is also the field to focus. The summary and the focus target both come from it, so they
	 * cannot disagree; the messages beside each control are worded for that control and are not.
	 */
	summary: FormError[];
}

const NO_ERRORS: FormErrors = { summary: [] };

function formErrors(
	form: FormState,
	mode: PracticeDefinitionFormProps["mode"],
	selectedWorkType: PracticeWorkTypeDefinitionOptions | undefined,
	revealSlug: () => void,
	deliveryId: string,
): FormErrors {
	const nameTooShort = form.name.trim().length < 3;
	const criteriaTooShort = form.criteria.trim().length < 3;
	const slugInvalid = mode === "create" && !isValidSlug(form.slug);
	const policy = practicePolicyError(form.automatedReviewPolicy);
	const reviewSettings = reviewSettingsProblem(form, form.automatedReviewPolicy, selectedWorkType);
	const gateError = parseGate(form.gateText, selectedWorkType).error;
	const subjectError =
		selectedWorkType && !selectedWorkType.subjectRoles.includes(form.subject)
			? "Choose a person this kind of work can identify."
			: undefined;
	const preferredSlug = form.deliveryBehavior.redundantToSlug?.trim();
	const deliveryError =
		hasText(preferredSlug) && !/^[a-z0-9]+(?:-[a-z0-9]+)*$/u.test(preferredSlug)
			? "Use lowercase letters, numbers, and single hyphens."
			: undefined;
	const summary = [
		nameTooShort && {
			fieldId: "practice-name",
			message: "Give the practice a name of at least three characters.",
		},
		criteriaTooShort && {
			fieldId: "practice-criteria",
			message: "Say what this practice checks, in at least three characters.",
		},
		policy && {
			fieldId: practicePolicyErrorTarget(form.automatedReviewPolicy),
			message: policy,
		},
		reviewSettings && { fieldId: reviewSettings.focusId, message: reviewSettings.message },
		hasText(subjectError) && { fieldId: "practice-subject", message: subjectError },
		hasText(gateError) && { fieldId: "practice-gate", message: gateError },
		slugInvalid && {
			fieldId: "practice-slug",
			message: "The identifier must be lowercase letters, numbers and hyphens.",
			// Lives inside the collapsed Technical settings panel, which unmounts its contents.
			reveal: revealSlug,
		},
		hasText(deliveryError) && {
			fieldId: `${deliveryId}-redundant`,
			message: deliveryError,
			reveal: revealSlug,
		},
	].filter((entry): entry is FormError => Boolean(entry));
	return {
		name: nameTooShort ? "Name must be at least 3 characters" : undefined,
		slug: slugInvalid ? "Use 3–64 lowercase letters, numbers, and single hyphens." : undefined,
		criteria: criteriaTooShort ? "Criteria must be at least 3 characters" : undefined,
		policy,
		reviewSettings,
		gate: gateError,
		subject: subjectError,
		delivery: deliveryError,
		summary,
	};
}

function submitLabel(mode: PracticeDefinitionFormProps["mode"], isPending: boolean): string {
	if (mode === "create") {
		return isPending ? "Creating…" : "Create practice";
	}
	return isPending ? "Saving…" : "Save changes";
}

/** Only reachable on an instance that offers no reviewable work type at all. */
const EMPTY_POLICY: PracticeAutomatedReviewPolicy = {
	sourceContractVersion: "",
	automatedReview: { mode: "NONE", evidenceSufficiency: "NONE" },
	whenEvidenceIsInsufficient: "SKIP_AUTOMATED_REVIEW",
	knownLimitations: [],
};

/**
 * Keeps the answer the author already gave about how far automated review may go: changing what is
 * reviewed is not a decision to start reviewing it.
 */
function recommendedPolicyWithCurrentSupport(
	recommended: PracticeAutomatedReviewPolicy,
	current: PracticeAutomatedReviewPolicy,
): PracticeAutomatedReviewPolicy {
	if (current.automatedReview.mode === "NONE") {
		return {
			...recommended,
			automatedReview: { mode: "NONE", evidenceSufficiency: "NONE" },
			knownLimitations: [],
		};
	}
	if (current.automatedReview.evidenceSufficiency === "DECLARED_EVIDENCE_INSUFFICIENT") {
		return {
			...recommended,
			automatedReview: {
				mode: "LANGUAGE_MODEL",
				evidenceSufficiency: "DECLARED_EVIDENCE_INSUFFICIENT",
			},
			knownLimitations: current.knownLimitations,
			insufficiencyReason: current.insufficiencyReason,
		};
	}
	return recommended;
}

/** A kind the instance no longer offers, named where this build still has words for it. */
function withdrawnKindSentence(artifactKind: string): string {
	return isKnownArtifactKind(artifactKind)
		? `This practice reviews ${artifactKindNoun(artifactKind, 2)}, which this instance no longer offers.`
		: "This practice reviews a kind of work this instance no longer offers.";
}

export function PracticeDefinitionForm(props: PracticeDefinitionFormProps) {
	const {
		mode,
		groups,
		isPending,
		disabled = false,
		isSubmitDisabled,
		beforeFields,
		afterFields,
		cancelAction,
		initialData,
		definitionOptions,
		evidenceOutcome,
	} = props;
	const formDisabled = isPending || disabled;
	const [form, setForm] = useState<FormState>(() => initialState(definitionOptions, initialData));
	const deliveryId = useId();
	// Counts refused submits, not "has submitted": it re-keys the summary so a second refusal
	// focuses it again.
	const [refusals, setRefusals] = useState(0);
	const [showAdvanced, setShowAdvanced] = useState(() => Boolean(initialData?.precomputeScript));
	const workTypes = orderedWorkTypes(definitionOptions);
	const groupItems = [
		{ value: NO_GROUP, label: "Unassigned" },
		...groups.map((group) => ({ value: group.slug, label: group.name })),
	];
	const { artifactKind } = form;
	const selectedWorkType = workTypeOptionsFor(definitionOptions, artifactKind);
	// Recorded history belongs to the work type the practice was reviewed under: switching work type
	// changes which sources are allowed, so the same rows would resolve to "Another source".
	const workTypeUnchanged = artifactKindOfSignals(initialData?.signals ?? []) === artifactKind;
	// `useRef` takes no lazy initialiser, so the map is built on the first render and every later one
	// is spared building a map to discard.
	// https://react.dev/reference/react/useRef#avoiding-recreating-the-ref-contents
	const draftsRef = useRef<Map<string, WorkTypeDraft>>(null);
	if (draftsRef.current === null) {
		draftsRef.current = new Map(artifactKind ? [[artifactKind, draftOf(form)]] : []);
	}
	const workTypeDrafts = draftsRef.current;
	const supportedAutomatedReviewModes = selectedWorkType?.supportedAutomatedReviewModes ?? [];
	const canRunMentoring = canAttemptAutomatedReview(
		form.automatedReviewPolicy,
		supportedAutomatedReviewModes,
	);
	const occasionMode = occasionModeOf(form.automatedReviewPolicy, canRunMentoring);
	const gateHelp = gatePresentation(selectedWorkType);
	const subjectRole = form.subject;
	const subjectRoles =
		selectedWorkType?.subjectRoles.includes(subjectRole) === true
			? selectedWorkType.subjectRoles
			: [subjectRole, ...(selectedWorkType?.subjectRoles ?? [])];
	const subjectItems = subjectRoles.map((role) => ({
		value: role,
		label:
			selectedWorkType?.subjectRoles.includes(role) === true
				? ACTOR_ROLE_LABELS[role]
				: `${ACTOR_ROLE_LABELS[role]} (not available for this work)`,
	}));
	const unsavedChanges = useUnsavedChanges({
		isDirty: !deepEqual(
			{ ...form, definitionChanges: [] },
			initialState(definitionOptions, initialData),
		),
		disabled: formDisabled,
	});

	const handleNameChange = (name: string) => {
		setForm((previous) => {
			const slugWasEdited = mode === "create" && previous.slug !== generateSlug(previous.name);
			return {
				...previous,
				name,
				...(mode === "create" && !slugWasEdited ? { slug: generateSlug(name) } : {}),
			};
		});
	};

	const selectWorkType = (next: PracticeWorkTypeDefinitionOptions) => {
		setForm((previous) => {
			const previousKind = previous.artifactKind;
			if (previousKind === next.artifactKind) {
				return previous;
			}
			if (previousKind) {
				workTypeDrafts.set(previousKind, draftOf(previous));
			}
			const draft = workTypeDrafts.get(next.artifactKind);
			const automatedReviewPolicy =
				draft?.automatedReviewPolicy ??
				recommendedPolicyWithCurrentSupport(next.recommendedPolicy, previous.automatedReviewPolicy);
			const reviewFields = draft ?? {
				...recommendedReviewSettings(next),
				subject: previous.subject,
				precondition: previous.precondition,
			};
			return {
				...previous,
				artifactKind: next.artifactKind,
				automatedReviewPolicy,
				gateText:
					draft?.gateText ??
					(reviewFields.precondition ? JSON.stringify(reviewFields.precondition, null, 2) : ""),
				...(automatedReviewPolicy.automatedReview.mode === "NONE"
					? withoutEvidence(reviewFields)
					: reviewFields),
				precondition: reviewFields.precondition,
				precomputeScript: draft?.precomputeScript ?? "",
			};
		});
	};

	// Evidence is forbidden while no review runs and mandatory as soon as one does, so the support
	// choice has to reach into the occasion rather than leave the author to be refused on save.
	const updatePolicy = (automatedReviewPolicy: PracticeAutomatedReviewPolicy) => {
		setForm((previous) => {
			const nowGuidanceOnly = automatedReviewPolicy.automatedReview.mode === "NONE";
			const wasGuidanceOnly = previous.automatedReviewPolicy.automatedReview.mode === "NONE";
			let reviewFields = normalizeReviewSettings(previous);
			if (nowGuidanceOnly) {
				reviewFields = withoutEvidence(reviewFields);
			} else if (wasGuidanceOnly && selectedWorkType) {
				reviewFields = withRecommendedEvidence(reviewFields, selectedWorkType);
			}
			return {
				...previous,
				automatedReviewPolicy,
				...normalizeReviewSettings(reviewFields),
				precomputeScript: nowGuidanceOnly ? "" : previous.precomputeScript,
			};
		});
	};

	const errors = formErrors(form, mode, selectedWorkType, () => setShowAdvanced(true), deliveryId);
	const valid = errors.summary.length === 0;
	const shownErrors = refusals > 0 ? errors : NO_ERRORS;

	const handleSubmit = (event: React.SubmitEvent<HTMLFormElement>) => {
		event.preventDefault();
		if (!valid) {
			setRefusals((count) => count + 1);
			const [first] = errors.summary;
			if (first) {
				first.reveal?.();
				requestAnimationFrame(() => document.getElementById(first.fieldId)?.focus());
			}
			return;
		}

		const overlapGroup = form.deliveryBehavior.overlapGroup?.trim();
		const redundantToSlug = form.deliveryBehavior.redundantToSlug?.trim();
		const submission = props.onSubmit({
			slug: form.slug,
			name: form.name.trim(),
			...normalizeReviewSettings(form),
			definitionChanges: form.definitionChanges.length > 0 ? form.definitionChanges : undefined,
			criteria: form.criteria.trim(),
			...(form.groupSlug === NO_GROUP ? {} : { groupSlug: form.groupSlug }),
			...(form.whyItMatters.trim() ? { whyItMatters: form.whyItMatters.trim() } : {}),
			...(form.whatGoodLooksLike.trim()
				? { whatGoodLooksLike: form.whatGoodLooksLike.trim() }
				: {}),
			...(canRunMentoring && form.precomputeScript.trim()
				? { precomputeScript: form.precomputeScript.trim() }
				: {}),
			automatedReviewPolicy: form.automatedReviewPolicy,
			deliveryBehavior: {
				summaryOnly: form.deliveryBehavior.summaryOnly,
				overlapGroup: hasText(overlapGroup) ? overlapGroup : undefined,
				redundantToSlug: hasText(redundantToSlug) ? redundantToSlug : undefined,
			},
		});
		// After dispatch, not before: `track` needs the promise the dispatch returns.
		unsavedChanges.track(submission);
	};

	return (
		<form onSubmit={handleSubmit} noValidate className="flex min-h-0 flex-1 flex-col">
			{unsavedChanges.dialog}
			<DrawerBody className="flex flex-col gap-8">
				{beforeFields}
				<FormErrorSummary key={refusals} errors={shownErrors.summary} />
				<fieldset disabled={formDisabled} className="contents">
					{/* The panel is the measure. Capping the controls narrower than the panel they sit in
					    strands the footer's buttons to their right, and the drawer is already sized for
					    this form. Prose still gets a reading width of its own. */}
					<div className="space-y-10">
						<p className="max-w-2xl text-sm text-muted-foreground">
							Define one observable way of working. The same definition should make sense to a
							developer, peer, human mentor, and an automated reviewer.
						</p>

						<section className="space-y-4">
							<div>
								<h2 className="text-lg font-semibold">Practice</h2>
								<p className="text-sm text-muted-foreground">
									Name the practice and choose where it applies. Fields marked{" "}
									<span aria-hidden>*</span>
									<span className="sr-only">with an asterisk</span> are required.
								</p>
							</div>
							<FieldGroup className="gap-4">
								<Field data-invalid={hasText(shownErrors.name) ? "true" : undefined}>
									<FieldLabel htmlFor="practice-name">Name *</FieldLabel>
									<Input
										id="practice-name"
										value={form.name}
										onChange={(event) => handleNameChange(event.target.value)}
										placeholder="e.g. Explain what changed and why"
										required
										minLength={3}
										maxLength={128}
										aria-invalid={hasText(shownErrors.name)}
										aria-describedby={hasText(shownErrors.name) ? "practice-name-error" : undefined}
									/>
									<FieldDescription>Use a short, action-oriented name.</FieldDescription>
									{hasText(shownErrors.name) && (
										<FieldError id="practice-name-error">{shownErrors.name}</FieldError>
									)}
								</Field>

								<Field>
									<FieldLabel id="practice-group-label" htmlFor="practice-group">
										Practice group
									</FieldLabel>
									<Select
										items={groupItems}
										value={form.groupSlug}
										onValueChange={(value) =>
											setForm((previous) => ({ ...previous, groupSlug: value ?? NO_GROUP }))
										}
									>
										<SelectTrigger
											id="practice-group"
											aria-describedby="practice-group-description"
										>
											<SelectValue />
										</SelectTrigger>
										<SelectContent aria-labelledby="practice-group-label">
											{groupItems.map((item) => (
												<SelectItem key={item.value} value={item.value}>
													{item.label}
												</SelectItem>
											))}
										</SelectContent>
									</Select>
									<FieldDescription id="practice-group-description">
										Put this practice in a group.
									</FieldDescription>
								</Field>
							</FieldGroup>
						</section>

						<Separator />

						<section className="space-y-5">
							<div>
								<h2 className="text-lg font-semibold">Review guidance</h2>
								<p className="text-sm text-muted-foreground">
									Explain the practice in plain language before configuring how it is reviewed.
								</p>
							</div>
							<Field data-invalid={hasText(shownErrors.criteria) ? "true" : undefined}>
								<FieldLabel htmlFor="practice-criteria">What to look for *</FieldLabel>
								<FieldDescription id="practice-criteria-description">
									Describe one observable way of working, what shows it, and when a reviewer should
									stay silent. Do not ask the reviewer to infer intent or facts outside the selected
									work. For example: “Look for a description that explains the behavior change and
									why. Stay silent for automated dependency updates.” Markdown is supported.
								</FieldDescription>
								<Textarea
									id="practice-criteria"
									value={form.criteria}
									onChange={(event) =>
										setForm((previous) => ({ ...previous, criteria: event.target.value }))
									}
									placeholder="Describe the standard, signals of doing it well or poorly, and cases where it does not apply…"
									className="min-h-56"
									required
									minLength={3}
									maxLength={50_000}
									aria-invalid={hasText(shownErrors.criteria)}
									aria-describedby={`practice-criteria-description${
										hasText(shownErrors.criteria) ? " practice-criteria-error" : ""
									}`}
								/>
								{hasText(shownErrors.criteria) && (
									<FieldError id="practice-criteria-error">{shownErrors.criteria}</FieldError>
								)}
							</Field>

							<Field>
								<FieldLabel htmlFor="practice-why">Why it matters</FieldLabel>
								<Textarea
									id="practice-why"
									value={form.whyItMatters}
									onChange={(event) =>
										setForm((previous) => ({ ...previous, whyItMatters: event.target.value }))
									}
									placeholder="Explain why this practice is worth caring about…"
									className="min-h-24"
									maxLength={2000}
								/>
								<FieldDescription>
									Shown to developers. It does not change review rules.
								</FieldDescription>
							</Field>
							<Field>
								<FieldLabel htmlFor="practice-good">What good looks like</FieldLabel>
								<Textarea
									id="practice-good"
									value={form.whatGoodLooksLike}
									onChange={(event) =>
										setForm((previous) => ({ ...previous, whatGoodLooksLike: event.target.value }))
									}
									placeholder="Describe a concrete example of doing this well…"
									className="min-h-24"
									maxLength={2000}
								/>
								<FieldDescription>
									Give one concrete example a developer can act on.
								</FieldDescription>
							</Field>
						</section>

						<Separator />

						<PracticeMentoringSupportEditor
							value={form.automatedReviewPolicy}
							recommended={selectedWorkType?.recommendedPolicy ?? form.automatedReviewPolicy}
							supportedAutomatedReviewModes={supportedAutomatedReviewModes}
							disabled={formDisabled}
							onChange={updatePolicy}
							error={shownErrors.policy}
						/>

						<Separator />

						<section className="space-y-4" aria-labelledby="practice-occasions-heading">
							<div>
								<h2 id="practice-occasions-heading" className="text-lg font-semibold">
									When this practice is reviewed
								</h2>
								<p className="text-sm text-muted-foreground">
									A practice is reviewed on one occasion: the moments that start a review, and what
									that review reads. A way of working that you judge differently at a different
									moment is a second practice, not a second occasion. Examples are what is in front
									of you when the work arrives, or what the merge never resolved.
								</p>
							</div>

							<FieldSet>
								<FieldLegend variant="label">Review this kind of work</FieldLegend>
								<FieldDescription>
									Changing this starts the moments and the evidence again from the recommended ones.
								</FieldDescription>
								<RadioGroup
									value={artifactKind}
									onValueChange={(value) => {
										const next = workTypes.find((option) => option.artifactKind === value);
										if (next) {
											selectWorkType(next);
										}
									}}
									className="gap-2"
								>
									{workTypes.map((option) => (
										<FieldLabel
											key={option.artifactKind}
											htmlFor={`practice-artifact-${option.artifactKind}`}
										>
											<Field orientation="horizontal">
												<RadioGroupItem
													id={`practice-artifact-${option.artifactKind}`}
													value={option.artifactKind}
												/>
												<FieldContent>
													<FieldTitle>{artifactKindLabel(option.artifactKind)}</FieldTitle>
													<FieldDescription>
														{workArtifactHint(option.artifactKind)}
													</FieldDescription>
												</FieldContent>
											</Field>
										</FieldLabel>
									))}
								</RadioGroup>
							</FieldSet>

							{selectedWorkType ? (
								<PracticeReviewSettingsEditor
									options={selectedWorkType}
									reviewFields={form}
									mode={occasionMode}
									outcome={workTypeUnchanged ? evidenceOutcome : undefined}
									disabled={formDisabled}
									error={shownErrors.reviewSettings?.message}
									errorFocusId={shownErrors.reviewSettings?.focusId}
									onChange={(reviewFields) =>
										setForm((previous) => ({ ...previous, ...reviewFields }))
									}
								/>
							) : (
								<p className="text-sm text-muted-foreground">
									{withdrawnKindSentence(artifactKind)} Choose a kind of work above to say when it
									is reviewed.
								</p>
							)}
							{selectedWorkType && (
								<div className="space-y-4">
									<FieldGroup>
										<Field
											orientation="responsive"
											data-invalid={hasText(shownErrors.subject) ? "true" : undefined}
										>
											<FieldLabel htmlFor="practice-subject">
												Person this practice judges
											</FieldLabel>
											<Select
												items={subjectItems}
												value={subjectRole}
												onValueChange={(value) =>
													setForm((previous) => ({
														...previous,
														definitionChanges: [
															...new Set([...previous.definitionChanges, "SUBJECT" as const]),
														],
														subject:
															selectedWorkType.subjectRoles.find((role) => role === value) ??
															previous.subject,
													}))
												}
											>
												<SelectTrigger
													id="practice-subject"
													aria-invalid={hasText(shownErrors.subject)}
													aria-describedby={
														hasText(shownErrors.subject) ? "practice-subject-error" : undefined
													}
													className="w-full @md/field-group:w-56"
												>
													<SelectValue />
												</SelectTrigger>
												<SelectContent aria-label="Person this practice judges">
													{subjectItems.map((item) => (
														<SelectItem key={item.value} value={item.value}>
															{item.label}
														</SelectItem>
													))}
												</SelectContent>
											</Select>
											{hasText(shownErrors.subject) && (
												<FieldError id="practice-subject-error">{shownErrors.subject}</FieldError>
											)}
										</Field>
									</FieldGroup>
									<Field>
										<FieldLabel htmlFor="practice-gate">Only review when</FieldLabel>
										<FieldDescription>{gateHelp.description}</FieldDescription>
										<Textarea
											id="practice-gate"
											aria-invalid={hasText(errors.gate)}
											aria-describedby={hasText(errors.gate) ? "practice-gate-error" : undefined}
											value={form.gateText}
											onChange={(event) => {
												const gateText = event.target.value;
												const parsed = parseGate(gateText);
												setForm((previous) => ({
													...previous,
													gateText,
													definitionChanges: [
														...new Set([...previous.definitionChanges, "PRECONDITION" as const]),
													],
													precondition: hasText(parsed.error)
														? previous.precondition
														: parsed.value,
												}));
											}}
											rows={7}
											placeholder={gateHelp.example}
										/>
										{hasText(errors.gate) && (
											<FieldError id="practice-gate-error">{errors.gate}</FieldError>
										)}
									</Field>
								</div>
							)}
						</section>

						{afterFields}

						<Separator />

						<Collapsible open={showAdvanced} onOpenChange={setShowAdvanced}>
							<CollapsibleTrigger
								render={
									<Button
										type="button"
										variant="ghost"
										size="inline"
										className="group min-w-0 items-start text-left whitespace-normal disabled:opacity-100"
									/>
								}
							>
								<ChevronRight className="mt-0.5 size-4 transition-transform group-aria-expanded:rotate-90" />
								<span>
									<span className="block text-lg font-semibold">Technical settings</span>
									<span className="block text-sm font-normal text-muted-foreground">
										Identifier, feedback delivery{canRunMentoring ? ", and static analysis" : ""}
									</span>
								</span>
							</CollapsibleTrigger>
							<CollapsibleContent className="mt-4 space-y-6 rounded-lg border p-4">
								<PracticeIdentifierField
									mode={mode}
									name={form.name}
									slug={form.slug}
									error={shownErrors.slug}
									onChange={(slug) => setForm((previous) => ({ ...previous, slug }))}
								/>

								<div className="space-y-4">
									<div>
										<p className="font-medium">Feedback delivery</p>
										<p className="text-sm text-muted-foreground">
											Use these choices when feedback from this practice overlaps with other
											feedback.
										</p>
									</div>
									<label
										className="flex items-center justify-between gap-4 text-sm"
										htmlFor={`${deliveryId}-summary`}
									>
										<span>Show this practice in the summary, not beside a diff line</span>
										<Switch
											id={`${deliveryId}-summary`}
											checked={form.deliveryBehavior.summaryOnly}
											disabled={formDisabled}
											onCheckedChange={(summaryOnly) =>
												setForm((previous) => ({
													...previous,
													deliveryBehavior: { ...previous.deliveryBehavior, summaryOnly },
												}))
											}
										/>
									</label>
									<Field>
										<FieldLabel htmlFor={`${deliveryId}-overlap`}>Overlap group</FieldLabel>
										<Input
											id={`${deliveryId}-overlap`}
											value={form.deliveryBehavior.overlapGroup ?? ""}
											disabled={formDisabled}
											onChange={(event) =>
												setForm((previous) => ({
													...previous,
													deliveryBehavior: {
														...previous.deliveryBehavior,
														overlapGroup: event.target.value,
													},
												}))
											}
										/>
										<FieldDescription>
											On an issue, only the first practice that is not met in this group is shown.
										</FieldDescription>
									</Field>
									<Field>
										<FieldLabel htmlFor={`${deliveryId}-redundant`}>
											Preferred practice slug
										</FieldLabel>
										<Input
											id={`${deliveryId}-redundant`}
											pattern="[a-z0-9]+(-[a-z0-9]+)*"
											title="Use lowercase letters, numbers, and single hyphens."
											aria-invalid={hasText(shownErrors.delivery)}
											aria-describedby={
												hasText(shownErrors.delivery) ? `${deliveryId}-redundant-error` : undefined
											}
											value={form.deliveryBehavior.redundantToSlug ?? ""}
											disabled={formDisabled}
											onChange={(event) =>
												setForm((previous) => ({
													...previous,
													deliveryBehavior: {
														...previous.deliveryBehavior,
														redundantToSlug: event.target.value,
													},
												}))
											}
										/>
										<FieldDescription>
											When both practices are not met, show feedback from the preferred practice
											instead of this one.
										</FieldDescription>
										<FieldError id={`${deliveryId}-redundant-error`}>
											{shownErrors.delivery}
										</FieldError>
									</Field>
								</div>

								{canRunMentoring && (
									<div className="space-y-3">
										<div>
											<p className="font-medium">Static analysis</p>
											<p className="text-sm text-muted-foreground">
												Optional TypeScript that prepares structured context before a review. Most
												practices do not need it.
											</p>
										</div>
										<CodeEditor
											value={form.precomputeScript}
											onChange={(value) =>
												setForm((previous) => ({ ...previous, precomputeScript: value }))
											}
											language="typescript"
											ariaLabel="Precompute script"
											className="h-[400px]"
											readOnly={formDisabled}
										/>
									</div>
								)}
							</CollapsibleContent>
						</Collapsible>
					</div>
				</fieldset>
			</DrawerBody>

			<DrawerFooter>
				{cancelAction}
				<Button type="submit" disabled={formDisabled || isSubmitDisabled}>
					{isPending && <Spinner className="size-4" />}
					{submitLabel(mode, isPending)}
				</Button>
			</DrawerFooter>
		</form>
	);
}

interface PracticeIdentifierFieldProps {
	mode: PracticeDefinitionFormProps["mode"];
	/** What the identifier is generated from, so one an author took over can be handed back. */
	name: string;
	slug: string;
	error?: string;
	onChange: (slug: string) => void;
}

function PracticeIdentifierField({
	mode,
	name,
	slug,
	error,
	onChange,
}: PracticeIdentifierFieldProps) {
	const wasEdited = mode === "create" && slug !== generateSlug(name);
	return (
		<Field data-invalid={hasText(error) ? "true" : undefined}>
			<FieldLabel htmlFor="practice-slug">Identifier</FieldLabel>
			<div className="flex items-center gap-2">
				<Input
					id="practice-slug"
					value={slug}
					onChange={(event) => onChange(event.target.value)}
					disabled={mode === "edit"}
					required={mode === "create"}
					minLength={3}
					maxLength={64}
					aria-invalid={hasText(error)}
					aria-describedby={
						["practice-slug-description", hasText(error) ? "practice-slug-error" : undefined]
							.filter(Boolean)
							.join(" ") || undefined
					}
				/>
				{wasEdited && (
					<Button
						type="button"
						variant="ghost"
						size="icon-sm"
						onClick={() => onChange(generateSlug(name))}
						aria-label="Reset to generated identifier"
					>
						<RotateCcw className="size-3.5" aria-hidden />
					</Button>
				)}
			</div>
			<FieldDescription id="practice-slug-description">
				Generated from the name for URLs and integrations. It cannot be changed later.
			</FieldDescription>
			{hasText(error) && <FieldError id="practice-slug-error">{error}</FieldError>}
		</Field>
	);
}
