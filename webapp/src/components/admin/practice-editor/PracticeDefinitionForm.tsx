import deepEqual from "fast-deep-equal";
import { ChevronRight, RotateCcw } from "lucide-react";
import { type ReactElement, type ReactNode, useRef, useState } from "react";
import { UnsavedPracticeDraft } from "./unsaved-practice-draft";

import type {
	PracticeDefinition,
	PracticeDefinitionInput,
	PracticeDefinitionOptions,
	PracticeItemTypeOption,
} from "@/api/types.gen";
import { CodeEditor } from "@/components/common/CodeEditor";
import { FormErrorSummary } from "@/components/common/FormErrorSummary";
import { InlineLink } from "@/components/common/InlineLink";
import {
	ABOUT_ROLE_LABELS,
	type AboutRole,
} from "@/components/practice-vocabulary/about-role-labels";
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
import { hasText } from "@/lib/text";

import { generateSlug, itemKindHint } from "./constants";
import {
	blankDraft,
	clearedBySave,
	definitionOfDraft,
	draftErrors,
	draftOf,
	FIELD_IDS,
	freshKindDraft,
	type KindDraft,
	kindDraftOf,
	MAX_GUIDANCE_LENGTH,
	NO_GROUP,
	type PracticeDefinitionDraft,
} from "./practice-definition-draft";
import { PracticeAppliesWhenEditor } from "./PracticeAppliesWhenEditor";
import { PracticeGuideEditor, type PracticeGuideView } from "./PracticeGuideEditor";
import { PracticeReviewedByEditor } from "./PracticeReviewedByEditor";
import { PracticeReviewSettingsEditor } from "./PracticeReviewSettingsEditor";
import { PracticeVisualEditor } from "./PracticeVisualEditor";
import { itemTypeFor, orderedItemTypes } from "./review-settings";

const PRACTICE_SCRIPT_GUIDE_URL = "https://docs.hephaestus.build/admin/practice-scripts";

// Base UI Select requires a string value even when About names no developer.
const ABOUT_THE_WORK = "__work__";

export interface PracticeDefinitionGroupOption {
	slug: string;
	name: string;
}

/**
 * One saved practice as both editor consoles read it: its identity, its group, and the one
 * closed definition. Feedback mode and review scope are workspace settings and never enter it.
 */
export interface PracticeDefinitionValue {
	slug: string;
	/** The kind of item the practice reviews. Chosen on create; it never changes afterwards. */
	kind: string;
	/** Where the practice is listed. Not part of the definition, so it never makes a new version. */
	groupSlug: string | null;
	definition: PracticeDefinition;
}

export type PracticeDefinitionSubmission = Omit<PracticeDefinitionValue, "definition"> & {
	definition: PracticeDefinitionInput;
};

interface PracticeDefinitionFormBaseProps {
	groups: readonly PracticeDefinitionGroupOption[];
	isPending: boolean;
	disabled?: boolean;
	isSubmitDisabled?: boolean;
	/** Sits at the top of the scrolling body, so a host's banner shares the fields' padding. */
	beforeFields?: ReactNode;
	afterFields?: ReactNode;
	cancelAction: ReactNode;
	/**
	 * Return a promise that rejects when the save failed: the unsaved-changes guard then stays down
	 * from submit until it hears otherwise, so a caller navigating straight after an awaited save is
	 * not asked to discard what it just saved. Returning nothing leaves the guard as it is.
	 */
	onSubmit: (value: PracticeDefinitionSubmission) => void | Promise<void>;
	definitionOptions: PracticeDefinitionOptions;
	/**
	 * A link to the AI models page, rendered as *AI models* in the practice script's help. Only a
	 * workspace has that page; without it the words stay plain.
	 */
	practiceScriptModelsLink?: ReactElement;
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

function initialDraft(
	definitionOptions: PracticeDefinitionOptions,
	initialData: PracticeDefinitionValue | undefined,
): PracticeDefinitionDraft {
	if (initialData) {
		return draftOf(initialData);
	}
	return blankDraft(orderedItemTypes(definitionOptions)[0]);
}

function submitLabel(mode: PracticeDefinitionFormProps["mode"], isPending: boolean): string {
	if (mode === "create") {
		return isPending ? "Creating…" : "Create practice";
	}
	return isPending ? "Saving…" : "Save changes";
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
		practiceScriptModelsLink,
	} = props;
	const formDisabled = isPending || disabled;
	const [draft, setDraft] = useState(() => initialDraft(definitionOptions, initialData));
	// Counts refused submits, not "has submitted": it re-keys the summary so a second refusal
	// focuses it again.
	const [refusals, setRefusals] = useState(0);
	const [showTechnical, setShowTechnical] = useState(false);
	const [showScript, setShowScript] = useState(() => hasText(initialData?.definition.script));
	const [guideView, setGuideView] = useState<PracticeGuideView>("write");
	const itemType = itemTypeFor(definitionOptions, draft.kind);
	const automated = draft.reviewedBy.by === "HEPHAESTUS";
	// `useRef` takes no lazy initialiser, so the map is built on the first render and every later one
	// is spared building a map to discard.
	// https://react.dev/reference/react/useRef#avoiding-recreating-the-ref-contents
	const kindDraftsRef = useRef<Map<string, KindDraft>>(null);
	if (kindDraftsRef.current === null) {
		kindDraftsRef.current = new Map([[draft.kind, kindDraftOf(draft)]]);
	}
	const kindDrafts = kindDraftsRef.current;
	const isDirty = !deepEqual(draft, initialDraft(definitionOptions, initialData));
	const unsavedChanges = useUnsavedChanges({
		isDirty,
		disabled: formDisabled,
	});
	const update = (patch: Partial<PracticeDefinitionDraft>) =>
		setDraft((previous) => ({ ...previous, ...patch }));

	const handleNameChange = (name: string) => {
		setDraft((previous) => {
			const slugWasEdited = mode === "create" && previous.slug !== generateSlug(previous.name);
			return {
				...previous,
				name,
				...(mode === "create" && !slugWasEdited ? { slug: generateSlug(name) } : {}),
			};
		});
	};

	const selectKind = (next: PracticeItemTypeOption) => {
		if (next.kind === draft.kind) {
			return;
		}
		kindDrafts.set(draft.kind, kindDraftOf(draft));
		const restored = kindDrafts.get(next.kind) ?? freshKindDraft(next);
		// Each kind keeps its own script. A switch that swaps the script in or out opens its section,
		// so the change is not made out of sight.
		if (restored.script !== draft.script) {
			setShowScript(true);
		}
		setDraft((previous) => ({ ...previous, kind: next.kind, ...restored }));
	};

	const errors = draftErrors(draft, mode, definitionOptions, {
		revealTechnical: () => setShowTechnical(true),
		revealGuide: () => setGuideView("write"),
		revealScript: () => setShowScript(true),
	});
	const valid = errors.summary.length === 0;
	const shown = refusals > 0 ? errors : undefined;
	const fieldError = (fieldId: string) => shown?.byField[fieldId];

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
		const submission = props.onSubmit(
			definitionOfDraft(draft, itemType, definitionOptions.sourceContract),
		);
		unsavedChanges.track(submission);
	};

	const groupItems = [
		{ value: NO_GROUP, label: "Unassigned" },
		...groups.map((group) => ({ value: group.slug, label: group.name })),
	];

	return (
		<form onSubmit={handleSubmit} noValidate className="flex min-h-0 flex-1 flex-col">
			{unsavedChanges.dialog}
			<DrawerBody className="flex flex-col gap-8">
				<UnsavedPracticeDraft value={isDirty}>{beforeFields}</UnsavedPracticeDraft>
				<FormErrorSummary key={refusals} errors={shown?.summary ?? []} />
				<fieldset disabled={formDisabled} className="contents">
					{/* The panel is the measure. Capping the controls narrower than the panel they sit in
					    strands the footer's buttons to their right, and the drawer is already sized for
					    this form. Prose still gets a reading width of its own. */}
					<div className="space-y-10">
						<p className="max-w-2xl text-sm text-muted-foreground">
							Define one way of working that someone can observe. Write it so that developers,
							mentors and automated reviewers read it the same way.
						</p>

						<section className="space-y-4">
							<div>
								<h2 className="text-lg font-semibold">Practice</h2>
								<p className="text-sm text-muted-foreground">
									Name the practice and choose what it reviews. Fields marked{" "}
									<span aria-hidden>*</span>
									<span className="sr-only">with an asterisk</span> are required.
								</p>
							</div>
							<FieldGroup className="gap-4">
								<TextField
									id={FIELD_IDS.name}
									label="Name *"
									value={draft.name}
									onChange={handleNameChange}
									placeholder="e.g. Explain what changed and why"
									maxLength={128}
									description="Start with a verb and keep it short."
									error={fieldError(FIELD_IDS.name)}
								/>

								<Field>
									<FieldLabel id="practice-group-label" htmlFor="practice-group">
										Practice group
									</FieldLabel>
									<Select
										items={groupItems}
										value={draft.groupSlug}
										onValueChange={(value) => update({ groupSlug: value ?? NO_GROUP })}
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
										Optional. A group collects related practices.
									</FieldDescription>
								</Field>

								{mode === "create" ? (
									<FieldSet>
										<FieldLegend variant="label">Reviews</FieldLegend>
										<FieldDescription>
											The kind of work cannot change after the practice exists.
										</FieldDescription>
										<RadioGroup
											value={draft.kind}
											onValueChange={(value) => {
												const next = itemTypeFor(definitionOptions, value);
												if (next) {
													selectKind(next);
												}
											}}
											className="gap-2"
										>
											{orderedItemTypes(definitionOptions).map((option) => (
												<FieldLabel key={option.kind} htmlFor={`practice-kind-${option.kind}`}>
													<Field orientation="horizontal">
														<RadioGroupItem
															id={`practice-kind-${option.kind}`}
															value={option.kind}
														/>
														<FieldContent>
															<FieldTitle>{option.label}</FieldTitle>
															<FieldDescription>{itemKindHint(option.kind)}</FieldDescription>
														</FieldContent>
													</Field>
												</FieldLabel>
											))}
										</RadioGroup>
									</FieldSet>
								) : (
									<div className="space-y-1 text-sm">
										<p className="font-medium">Reviews</p>
										<p>{itemType?.label ?? "A kind of work this instance no longer offers."}</p>
										<p className="text-muted-foreground">
											You cannot change this after the practice exists.
										</p>
									</div>
								)}
							</FieldGroup>
						</section>

						<Separator />

						<section className="space-y-5">
							<div>
								<h2 className="text-lg font-semibold">Review guidance</h2>
								<p className="text-sm text-muted-foreground">
									Explain the practice in plain language before you choose how it is reviewed.
								</p>
							</div>
							<Field data-invalid={hasText(fieldError(FIELD_IDS.criteria)) ? "true" : undefined}>
								<FieldLabel htmlFor={FIELD_IDS.criteria}>What to look for *</FieldLabel>
								<FieldDescription id="practice-criteria-description">
									Describe what shows this practice and when a reviewer should stay silent. Do not
									ask the reviewer to infer intent or facts outside the selected work. For example:
									“Look for a description that explains the behavior change and why. Stay silent for
									automated dependency updates.” Markdown is supported.
								</FieldDescription>
								<Textarea
									id={FIELD_IDS.criteria}
									value={draft.criteria}
									onChange={(event) => update({ criteria: event.target.value })}
									placeholder="Describe the standard, what doing it well or poorly looks like, and where it does not apply…"
									className="min-h-56"
									required
									maxLength={50_000}
									aria-invalid={hasText(fieldError(FIELD_IDS.criteria))}
									aria-describedby={`practice-criteria-description${
										hasText(fieldError(FIELD_IDS.criteria)) ? " practice-criteria-error" : ""
									}`}
								/>
								{hasText(fieldError(FIELD_IDS.criteria)) && (
									<FieldError id="practice-criteria-error">
										{fieldError(FIELD_IDS.criteria)}
									</FieldError>
								)}
							</Field>

							<GuidanceField
								id="practice-why"
								label="Why it matters"
								value={draft.whyItMatters}
								onChange={(whyItMatters) => update({ whyItMatters })}
								placeholder="Explain why this practice is worth caring about…"
								description="Shown to developers. It does not change how the practice is reviewed."
							/>
							<GuidanceField
								id="practice-good"
								label="What good looks like"
								value={draft.whatGoodLooksLike}
								onChange={(whatGoodLooksLike) => update({ whatGoodLooksLike })}
								placeholder="Describe a concrete example of doing this well…"
								description="Give one concrete example a developer can act on."
							/>
							<GuidanceField
								id="practice-going-well"
								label="Going well"
								value={draft.goingWell}
								onChange={(goingWell) => update({ goingWell })}
								placeholder="e.g. You keep each change small enough to review in one sitting."
								description="Shown to a developer whose work meets this practice. Say what they keep doing."
							/>
							<PracticeVisualEditor
								value={draft.visual}
								onChange={(visual) => update({ visual })}
								svgError={fieldError(FIELD_IDS.visualSvg)}
								altError={fieldError(FIELD_IDS.visualAlt)}
							/>
							<PracticeGuideEditor
								value={draft.guide}
								onChange={(guide) => update({ guide })}
								view={guideView}
								onViewChange={setGuideView}
								error={fieldError(FIELD_IDS.guide)}
							/>
						</section>

						<Separator />

						<PracticeReviewedByEditor
							value={draft.reviewedBy}
							onChange={(reviewedBy) => update({ reviewedBy })}
							cleared={clearedBySave(draft)}
							error={shown?.reviewedBy?.message}
							errorFocusId={shown?.reviewedBy?.focusId}
							disabled={formDisabled}
						/>

						<Separator />

						<section className="space-y-4" aria-labelledby="practice-review-heading">
							<div>
								<h2 id="practice-review-heading" className="text-lg font-semibold">
									When this practice is reviewed
								</h2>
								<p className="text-sm text-muted-foreground">
									A practice is reviewed on the events you choose, and only when it applies. If a
									way of working needs a different judgment at a different event, make it a second
									practice.
								</p>
							</div>

							{itemType ? (
								<PracticeReviewSettingsEditor
									itemType={itemType}
									value={draft.settings}
									onChange={(settings) => update({ settings })}
									reviewedBy={draft.reviewedBy.by}
									error={shown?.settings?.message}
									errorFocusId={shown?.settings?.focusId}
									disabled={formDisabled}
								/>
							) : (
								<p className="text-sm text-muted-foreground">
									This practice reviews a kind of work this instance no longer offers, so its review
									settings cannot be changed here.
								</p>
							)}

							{itemType && (
								<AboutField
									itemType={itemType}
									about={draft.about}
									onChange={(about) => update({ about })}
									error={fieldError(FIELD_IDS.about)}
								/>
							)}

							<PracticeAppliesWhenEditor
								id={FIELD_IDS.appliesWhen}
								value={draft.appliesWhen}
								onChange={(appliesWhen) => update({ appliesWhen })}
								itemType={itemType}
								disabled={formDisabled}
								error={fieldError(FIELD_IDS.appliesWhen)}
							/>

							{draft.appliesWhen.clauses.length > 0 && (
								<TextField
									id={FIELD_IDS.otherwise}
									label="Otherwise *"
									value={draft.appliesWhen.otherwise}
									onChange={(otherwise) =>
										update({ appliesWhen: { ...draft.appliesWhen, otherwise } })
									}
									placeholder="e.g. the change touches no Swift source file"
									description="Why the practice does not apply when no condition holds. Developers and admins read it."
									error={fieldError(FIELD_IDS.otherwise)}
								/>
							)}
						</section>

						{automated && (
							<>
								<Separator />
								<CollapsibleSection
									title="Script"
									subtitle={
										hasText(draft.script.trim())
											? "The practice script that reviews this practice."
											: "Built-in. Add a practice script to review this practice your own way."
									}
									open={showScript}
									onOpenChange={setShowScript}
								>
									<p className="max-w-2xl text-sm text-muted-foreground">
										TypeScript that reviews this practice and returns its candidate observation. It
										can decide in code, call models, or prepare places for the review to decide. It
										never writes feedback. Without a script, the practice runs the built-in review.{" "}
										<InlineLink href={PRACTICE_SCRIPT_GUIDE_URL} external>
											Write a practice script
										</InlineLink>
										. It can call the models on{" "}
										{practiceScriptModelsLink === undefined ? (
											"AI models"
										) : (
											<InlineLink render={practiceScriptModelsLink}>AI models</InlineLink>
										)}
										.
									</p>
									<CodeEditor
										value={draft.script}
										onChange={(script) => update({ script })}
										language="typescript"
										ariaLabel="Practice script"
										className="h-[400px]"
										readOnly={formDisabled}
									/>
									{hasText(fieldError(FIELD_IDS.script)) && (
										<FieldError id={FIELD_IDS.script}>{fieldError(FIELD_IDS.script)}</FieldError>
									)}
								</CollapsibleSection>
							</>
						)}

						{afterFields}

						<Separator />

						<CollapsibleSection
							title="Technical settings"
							subtitle="Identifier and line notes"
							open={showTechnical}
							onOpenChange={setShowTechnical}
						>
							<PracticeIdentifierField
								mode={mode}
								name={draft.name}
								slug={draft.slug}
								error={fieldError(FIELD_IDS.slug)}
								onChange={(slug) => update({ slug })}
							/>
							<Field orientation="horizontal">
								<Switch
									id="practice-line-notes"
									checked={draft.lineNotes}
									disabled={formDisabled}
									onCheckedChange={(lineNotes) => update({ lineNotes })}
									aria-describedby="practice-line-notes-description"
								/>
								<FieldContent>
									<FieldLabel htmlFor="practice-line-notes">Line notes</FieldLabel>
									<FieldDescription id="practice-line-notes-description">
										Show this practice’s feedback beside the lines it is about. Off keeps it in the
										summary.
									</FieldDescription>
								</FieldContent>
							</Field>
						</CollapsibleSection>
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

interface TextFieldProps {
	id: string;
	label: string;
	value: string;
	onChange: (value: string) => void;
	placeholder: string;
	maxLength?: number;
	description: string;
	error?: string;
}

function TextField({
	id,
	label,
	value,
	onChange,
	placeholder,
	maxLength,
	description,
	error,
}: TextFieldProps) {
	return (
		<Field data-invalid={hasText(error) ? "true" : undefined}>
			<FieldLabel htmlFor={id}>{label}</FieldLabel>
			<Input
				id={id}
				value={value}
				onChange={(event) => onChange(event.target.value)}
				placeholder={placeholder}
				maxLength={maxLength}
				aria-invalid={hasText(error)}
				aria-describedby={`${id}-description${hasText(error) ? ` ${id}-error` : ""}`}
			/>
			<FieldDescription id={`${id}-description`}>{description}</FieldDescription>
			{hasText(error) && <FieldError id={`${id}-error`}>{error}</FieldError>}
		</Field>
	);
}

interface GuidanceFieldProps {
	id: string;
	label: string;
	value: string;
	onChange: (value: string) => void;
	placeholder: string;
	description: string;
}

function GuidanceField({
	id,
	label,
	value,
	onChange,
	placeholder,
	description,
}: GuidanceFieldProps) {
	return (
		<Field>
			<FieldLabel htmlFor={id}>{label}</FieldLabel>
			<Textarea
				id={id}
				value={value}
				onChange={(event) => onChange(event.target.value)}
				placeholder={placeholder}
				className="min-h-24"
				maxLength={MAX_GUIDANCE_LENGTH}
				aria-describedby={`${id}-description`}
			/>
			<FieldDescription id={`${id}-description`}>{description}</FieldDescription>
		</Field>
	);
}

interface AboutFieldProps {
	itemType: PracticeItemTypeOption;
	about: AboutRole | null;
	onChange: (about: AboutRole | null) => void;
	error?: string;
}

// Keep unsupported saved roles visible so the author can explicitly replace them.
function AboutField({ itemType, about, onChange, error }: AboutFieldProps) {
	const roles: AboutRole[] =
		about === null || itemType.aboutRoles.includes(about)
			? itemType.aboutRoles
			: [about, ...itemType.aboutRoles];
	const items = [
		{ value: ABOUT_THE_WORK, label: "The work" },
		...roles.map((role) => ({
			value: role,
			label: itemType.aboutRoles.includes(role)
				? ABOUT_ROLE_LABELS[role]
				: `${ABOUT_ROLE_LABELS[role]} (not available for this kind of work)`,
		})),
	];
	return (
		<FieldGroup>
			<Field orientation="responsive" data-invalid={hasText(error) ? "true" : undefined}>
				<FieldContent>
					<FieldLabel htmlFor={FIELD_IDS.about}>About</FieldLabel>
					<FieldDescription id="practice-about-description">
						Whose work an observation is about. The work means no developer.
					</FieldDescription>
				</FieldContent>
				<Select
					items={items}
					value={about ?? ABOUT_THE_WORK}
					onValueChange={(value) => onChange(roles.find((role) => role === value) ?? null)}
				>
					<SelectTrigger
						id={FIELD_IDS.about}
						aria-invalid={hasText(error)}
						aria-describedby={`practice-about-description${hasText(error) ? " practice-about-error" : ""}`}
						className="w-full @md/field-group:w-56"
					>
						<SelectValue />
					</SelectTrigger>
					<SelectContent aria-label="About">
						{items.map((item) => (
							<SelectItem key={item.value} value={item.value}>
								{item.label}
							</SelectItem>
						))}
					</SelectContent>
				</Select>
				{hasText(error) && <FieldError id="practice-about-error">{error}</FieldError>}
			</Field>
		</FieldGroup>
	);
}

interface CollapsibleSectionProps {
	title: string;
	subtitle: string;
	open: boolean;
	onOpenChange: (open: boolean) => void;
	children: ReactNode;
}

function CollapsibleSection({
	title,
	subtitle,
	open,
	onOpenChange,
	children,
}: CollapsibleSectionProps) {
	return (
		<Collapsible open={open} onOpenChange={onOpenChange}>
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
					<span className="block text-lg font-semibold">{title}</span>
					<span className="block text-sm font-normal text-muted-foreground">{subtitle}</span>
				</span>
			</CollapsibleTrigger>
			<CollapsibleContent className="mt-4 space-y-6">{children}</CollapsibleContent>
		</Collapsible>
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
			<FieldLabel htmlFor={FIELD_IDS.slug}>Identifier</FieldLabel>
			<div className="flex items-center gap-2">
				<Input
					id={FIELD_IDS.slug}
					value={slug}
					onChange={(event) => onChange(event.target.value)}
					disabled={mode === "edit"}
					required={mode === "create"}
					minLength={3}
					maxLength={64}
					aria-invalid={hasText(error)}
					aria-describedby={`practice-slug-description${hasText(error) ? " practice-slug-error" : ""}`}
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
