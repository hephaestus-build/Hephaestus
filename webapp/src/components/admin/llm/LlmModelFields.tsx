import { AlertTriangle, ChevronDown } from "lucide-react";
import { useId, useState } from "react";

import { FactList } from "@/components/auth/FactList";
import {
	DATA_HANDLING_DEFS,
	type DataHandlingTier,
	deriveDataHandlingTier,
	KEPT_AFTER_REPLY_DEFS,
	type KeptAfterReply,
	OPERATED_BY_DEFS,
	type OperatedBy,
} from "@/components/practice-vocabulary/data-handling-defs";
import { DataHandlingBadge } from "@/components/practice-vocabulary/DataHandlingBadge";
import { type StatusDefs, statusValues } from "@/components/practice-vocabulary/status-def";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
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
import { Switch } from "@/components/ui/switch";
import {
	type FieldErrors,
	type LlmModelFormField,
	validateLlmModelForm,
} from "@/lib/llm-form-validation";
import type { LlmAudience } from "@/lib/llm-pricing";

import { PriceModeEditor, type PriceModeValue } from "./PriceModeEditor";

export interface LlmModelFieldsValue {
	displayName: string;
	upstreamModelId: string;
	contextWindow: string;
	maxOutputTokens: string;
	supportsReasoning: boolean;
	/** Absent until the admin picks a fact, as the wire carries it; both facts or neither are sent. */
	operatedBy?: OperatedBy;
	keptAfterReply?: KeptAfterReply;
	trainingConfirmed: boolean;
	dataHandlingNote: string;
	enabled: boolean;
	price: PriceModeValue;
}

type EditedModel = {
	displayName: string;
	upstreamModelId: string;
	contextWindow?: number;
	maxOutputTokens?: number;
	supportsReasoning?: boolean;
	operatedBy?: OperatedBy;
	keptAfterReply?: KeptAfterReply;
	dataHandlingNote?: string;
	enabled?: boolean;
};

export function modelFieldsValueOf(
	model: EditedModel | null,
	price: PriceModeValue,
): LlmModelFieldsValue {
	const declared = model?.operatedBy !== undefined && model.keptAfterReply !== undefined;
	return {
		displayName: model?.displayName ?? "",
		upstreamModelId: model?.upstreamModelId ?? "",
		contextWindow: model?.contextWindow != null ? String(model.contextWindow) : "",
		maxOutputTokens: model?.maxOutputTokens != null ? String(model.maxOutputTokens) : "",
		supportsReasoning: model?.supportsReasoning ?? false,
		operatedBy: model?.operatedBy,
		keptAfterReply: model?.keptAfterReply,
		// A declared model was confirmed when it was declared; the admin is not asked twice.
		trainingConfirmed: declared,
		dataHandlingNote: model?.dataHandlingNote ?? "",
		enabled: model?.enabled ?? false,
		price,
	};
}

export function validateModelFields(
	value: LlmModelFieldsValue,
	isEdit: boolean,
): FieldErrors<LlmModelFormField> {
	return validateLlmModelForm({
		displayName: value.displayName,
		// Immutable once created, so an edit neither sends nor validates it.
		upstreamModelId: isEdit ? undefined : value.upstreamModelId,
		contextWindow: value.contextWindow,
		maxOutputTokens: value.maxOutputTokens,
		operatedBy: value.operatedBy,
		keptAfterReply: value.keptAfterReply,
		trainingConfirmed: value.trainingConfirmed,
		dataHandlingNote: value.dataHandlingNote,
		...value.price,
	});
}

/** The declaration as the request DTOs carry it: an unset fact is omitted, a blank note too. */
export function dataHandlingBodyOf(value: LlmModelFieldsValue) {
	return {
		operatedBy: value.operatedBy,
		keptAfterReply: value.keptAfterReply,
		dataHandlingNote: value.dataHandlingNote.trim() || undefined,
	};
}

const canBeActive = (price: PriceModeValue) => price.pricingMode !== "UNPRICED";

const withPrice = (value: LlmModelFieldsValue, price: PriceModeValue): LlmModelFieldsValue => ({
	...value,
	price,
	enabled: value.enabled && canBeActive(price),
});

const COPY = {
	instance: {
		displayNamePlaceholder: "e.g. GPT-5",
		upstreamIdPlaceholder: "e.g. openai/gpt-5",
		upstreamIdHint: "The exact id the provider expects. Slashes are part of the id.",
		activeHintEdit: "Only active models can be selected for new workspace requests.",
		activeHintCreate:
			"New models are saved inactive. Review the saved price and sharing before activating.",
		deactivationTitle: "Work on this model stops immediately, in every workspace",
		deactivationBody:
			"Practice reviews and Heph can't run on it until you reactivate it, or until each workspace picks another model.",
	},
	workspace: {
		displayNamePlaceholder: "e.g. GPT-5 mini",
		upstreamIdPlaceholder: "e.g. openai/gpt-5-mini",
		upstreamIdHint: "The exact id your provider expects. Slashes are part of the id.",
		activeHintEdit: "Only active models with a declared price can be selected.",
		activeHintCreate: "Starts inactive. Add a price, then activate.",
		deactivationTitle: "Work on this model stops immediately",
		deactivationBody:
			"Practice reviews and Heph can't run until you reactivate this model or pick another.",
	},
} satisfies Record<LlmAudience, Record<string, string>>;

interface FactChoiceProps<TValue extends string> {
	idPrefix: string;
	labelId: string;
	label: string;
	defs: StatusDefs<TValue>;
	value: TValue | undefined;
	onChange: (value: TValue) => void;
	invalid: boolean;
	describedBy?: string;
}

/** One fact as a radio group of cards: icon, title and the sentence the admin is agreeing to. */
function FactChoice<TValue extends string>({
	idPrefix,
	labelId,
	label,
	defs,
	value,
	onChange,
	invalid,
	describedBy,
}: FactChoiceProps<TValue>) {
	return (
		<Field data-invalid={invalid}>
			<FieldTitle id={labelId}>{label}</FieldTitle>
			<RadioGroup
				// `undefined` would make Base UI treat the group as uncontrolled for its whole life, so
				// "Leave undeclared" could never clear it; `null` is its controlled "nothing checked".
				value={value ?? null}
				onValueChange={(next) => {
					if (next !== null) onChange(next);
				}}
				className="gap-3"
				aria-labelledby={labelId}
				aria-invalid={invalid}
				aria-describedby={describedBy}
			>
				{statusValues(defs).map((option) => {
					const { icon: Icon, label: title, description } = defs[option];
					const id = `${idPrefix}-${option}`;
					return (
						<FieldLabel key={option} htmlFor={id}>
							<Field orientation="horizontal">
								<Icon className="mt-px size-5 shrink-0 text-muted-foreground" aria-hidden />
								<FieldContent>
									<FieldTitle id={`${id}-title`}>{title}</FieldTitle>
									<FieldDescription id={`${id}-detail`}>{description}</FieldDescription>
								</FieldContent>
								<RadioGroupItem
									id={id}
									value={option}
									aria-labelledby={`${id}-title`}
									aria-describedby={`${id}-detail`}
								/>
							</Field>
						</FieldLabel>
					);
				})}
			</RadioGroup>
		</Field>
	);
}

export interface LlmModelFieldsProps {
	audience: LlmAudience;
	idPrefix: string;
	isEdit: boolean;
	wasEnabled: boolean;
	/** The tier the model is stored under when editing; the form warns when the draft leaves it. */
	savedTier?: DataHandlingTier;
	value: LlmModelFieldsValue;
	onChange: (value: LlmModelFieldsValue) => void;
	errors: FieldErrors<LlmModelFormField>;
	upstreamIdSuggestions?: string[];
}

export function LlmModelFields({
	audience,
	idPrefix,
	isEdit,
	wasEnabled,
	savedTier,
	value,
	onChange,
	errors,
	upstreamIdSuggestions,
}: LlmModelFieldsProps) {
	const copy = COPY[audience];
	const update = (patch: Partial<LlmModelFieldsValue>) => onChange({ ...value, ...patch });

	const displayNameErrorId = useId();
	const upstreamModelIdErrorId = useId();
	const contextWindowErrorId = useId();
	const maxOutputTokensErrorId = useId();
	const dataHandlingErrorId = useId();
	const trainingErrorId = useId();
	const dataHandlingNoteErrorId = useId();
	const suggestionsId = `${idPrefix}-upstream-id-options`;

	const previewTier = deriveDataHandlingTier(value.operatedBy, value.keptAfterReply);
	const anyFactSet = value.operatedBy !== undefined || value.keptAfterReply !== undefined;
	const dataHandlingInvalid = Boolean(errors.dataHandling);
	const dataHandlingDescribedBy = errors.dataHandling ? dataHandlingErrorId : undefined;
	// A declared row holds only its exact tier, while the undeclared row takes any model — so
	// only a model leaving a declared tier drops out of the rows that hold it.
	const leftTier =
		savedTier !== undefined && savedTier !== "UNDECLARED" && previewTier !== savedTier
			? savedTier
			: undefined;

	// A field the admin cannot see cannot be corrected, so an error inside keeps the disclosure open.
	const [showAdvanced, setShowAdvanced] = useState(false);
	const advancedHasError =
		errors.contextWindow !== undefined || errors.maxOutputTokens !== undefined;

	return (
		<>
			<Field data-invalid={Boolean(errors.displayName)}>
				<FieldLabel htmlFor={`${idPrefix}-display-name`}>Display name</FieldLabel>
				<Input
					id={`${idPrefix}-display-name`}
					value={value.displayName}
					onChange={(e) => update({ displayName: e.target.value })}
					placeholder={copy.displayNamePlaceholder}
					// Inert under `noValidate`, but it is what announces the field as required (SC 3.3.2).
					required
					aria-invalid={Boolean(errors.displayName)}
					aria-describedby={errors.displayName ? displayNameErrorId : undefined}
				/>
				{errors.displayName && (
					<FieldError id={displayNameErrorId}>{errors.displayName}</FieldError>
				)}
			</Field>

			<Field data-invalid={Boolean(errors.upstreamModelId)}>
				<FieldLabel htmlFor={`${idPrefix}-upstream-id`}>Upstream model id</FieldLabel>
				<Input
					id={`${idPrefix}-upstream-id`}
					value={value.upstreamModelId}
					onChange={(e) => update({ upstreamModelId: e.target.value })}
					disabled={isEdit}
					placeholder={copy.upstreamIdPlaceholder}
					required={!isEdit}
					autoComplete="off"
					list={suggestionsId}
					aria-invalid={Boolean(errors.upstreamModelId)}
					aria-describedby={errors.upstreamModelId ? upstreamModelIdErrorId : undefined}
				/>
				{upstreamIdSuggestions && upstreamIdSuggestions.length > 0 && (
					<datalist id={suggestionsId}>
						{upstreamIdSuggestions.map((id) => (
							<option key={id} value={id} />
						))}
					</datalist>
				)}
				<FieldDescription>
					{isEdit ? "Create a new model to use a different upstream id." : copy.upstreamIdHint}
				</FieldDescription>
				{errors.upstreamModelId && (
					<FieldError id={upstreamModelIdErrorId}>{errors.upstreamModelId}</FieldError>
				)}
			</Field>

			<PriceModeEditor
				audience={audience}
				idPrefix={`${idPrefix}-price`}
				value={value.price}
				onChange={(price) => onChange(withPrice(value, price))}
				errors={errors}
			/>

			<Field orientation="horizontal">
				<FieldContent>
					<FieldLabel htmlFor={`${idPrefix}-enabled`}>Active</FieldLabel>
					<FieldDescription>
						{isEdit ? copy.activeHintEdit : copy.activeHintCreate}
					</FieldDescription>
				</FieldContent>
				<Switch
					id={`${idPrefix}-enabled`}
					checked={value.enabled}
					disabled={!isEdit || !canBeActive(value.price)}
					onCheckedChange={(enabled) => update({ enabled })}
				/>
			</Field>

			{wasEnabled && !value.enabled && (
				<Alert variant="warning">
					<AlertTriangle aria-hidden />
					<AlertTitle>{copy.deactivationTitle}</AlertTitle>
					<AlertDescription>{copy.deactivationBody}</AlertDescription>
				</Alert>
			)}

			<FieldSet>
				<FieldLegend variant="label">Data handling</FieldLegend>
				<FieldDescription>
					Developers read this as your promise. Declare what the agreement says, not what the
					hostname suggests.
				</FieldDescription>

				<FactChoice
					idPrefix={`${idPrefix}-operated-by`}
					labelId={`${idPrefix}-operated-by-label`}
					label="Operated by"
					defs={OPERATED_BY_DEFS}
					value={value.operatedBy}
					onChange={(operatedBy) => update({ operatedBy })}
					invalid={dataHandlingInvalid}
					describedBy={dataHandlingDescribedBy}
				/>

				<FactChoice
					idPrefix={`${idPrefix}-kept-after-reply`}
					labelId={`${idPrefix}-kept-after-reply-label`}
					label="Kept after the reply"
					defs={KEPT_AFTER_REPLY_DEFS}
					value={value.keptAfterReply}
					onChange={(keptAfterReply) => update({ keptAfterReply })}
					invalid={dataHandlingInvalid}
					describedBy={dataHandlingDescribedBy}
				/>

				{errors.dataHandling && (
					<FieldError id={dataHandlingErrorId}>{errors.dataHandling}</FieldError>
				)}

				<Field orientation="horizontal" data-invalid={Boolean(errors.trainingConfirmed)}>
					<Checkbox
						id={`${idPrefix}-training`}
						checked={value.trainingConfirmed}
						onCheckedChange={(trainingConfirmed) => update({ trainingConfirmed })}
						required={anyFactSet}
						aria-invalid={Boolean(errors.trainingConfirmed)}
						aria-describedby={errors.trainingConfirmed ? trainingErrorId : undefined}
					/>
					<FieldContent>
						<FieldLabel htmlFor={`${idPrefix}-training`} className="font-normal">
							Its terms rule out training on what it receives. Hephaestus does not accept models
							that train on developers' work.
						</FieldLabel>
						{errors.trainingConfirmed && (
							<FieldError id={trainingErrorId}>{errors.trainingConfirmed}</FieldError>
						)}
					</FieldContent>
				</Field>

				{anyFactSet && (
					<Button
						type="button"
						variant="ghost"
						size="sm"
						className="-ml-2 w-fit"
						onClick={() =>
							update({ operatedBy: undefined, keptAfterReply: undefined, trainingConfirmed: false })
						}
					>
						Leave undeclared
					</Button>
				)}

				{leftTier && (
					<Alert variant="warning">
						<AlertTriangle aria-hidden />
						<AlertTitle>
							Rows holding this model as {DATA_HANDLING_DEFS[leftTier].label} stop serving
						</AlertTitle>
						<AlertDescription>
							A row holds only models declared as its own tier. Nothing runs on this model there
							until it is reassigned.
						</AlertDescription>
					</Alert>
				)}

				<Field data-invalid={Boolean(errors.dataHandlingNote)}>
					<FieldLabel htmlFor={`${idPrefix}-data-handling-note`}>
						Note for admins <span className="font-normal text-muted-foreground">(optional)</span>
					</FieldLabel>
					<Input
						id={`${idPrefix}-data-handling-note`}
						value={value.dataHandlingNote}
						onChange={(e) => update({ dataHandlingNote: e.target.value })}
						maxLength={200}
						aria-invalid={Boolean(errors.dataHandlingNote)}
						aria-describedby={errors.dataHandlingNote ? dataHandlingNoteErrorId : undefined}
					/>
					<FieldDescription>
						Region, agreement, renewal date. Shown to admins only, never to developers.
					</FieldDescription>
					{errors.dataHandlingNote && (
						<FieldError id={dataHandlingNoteErrorId}>{errors.dataHandlingNote}</FieldError>
					)}
				</Field>

				<div className="space-y-3 rounded-lg border bg-muted/30 p-3">
					<div className="flex flex-wrap items-center gap-2 text-sm">
						<span className="font-medium">Developers will see:</span>
						<DataHandlingBadge tier={previewTier} />
					</div>
					{previewTier === "UNDECLARED" ? (
						<p className="text-sm text-muted-foreground">
							Choose both facts to declare this model.
						</p>
					) : (
						<FactList facts={DATA_HANDLING_DEFS[previewTier].facts} />
					)}
				</div>
			</FieldSet>

			<Collapsible open={showAdvanced || advancedHasError} onOpenChange={setShowAdvanced}>
				<CollapsibleTrigger
					render={
						<Button type="button" variant="ghost" size="sm" className="-ml-2 group/adv">
							Advanced: limits and capabilities
							<ChevronDown
								className="transition-transform group-aria-expanded/adv:rotate-180"
								aria-hidden
							/>
						</Button>
					}
				/>
				<CollapsibleContent>
					<FieldGroup className="pt-4">
						<div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
							<Field data-invalid={Boolean(errors.contextWindow)}>
								<FieldLabel htmlFor={`${idPrefix}-context-window`}>
									Context window{" "}
									<span className="font-normal text-muted-foreground">(optional)</span>
								</FieldLabel>
								<Input
									id={`${idPrefix}-context-window`}
									type="number"
									min={0}
									step={1}
									value={value.contextWindow}
									onChange={(e) => update({ contextWindow: e.target.value })}
									aria-invalid={Boolean(errors.contextWindow)}
									aria-describedby={errors.contextWindow ? contextWindowErrorId : undefined}
								/>
								{errors.contextWindow && (
									<FieldError id={contextWindowErrorId}>{errors.contextWindow}</FieldError>
								)}
							</Field>
							<Field data-invalid={Boolean(errors.maxOutputTokens)}>
								<FieldLabel htmlFor={`${idPrefix}-max-output`}>
									Max output tokens{" "}
									<span className="font-normal text-muted-foreground">(optional)</span>
								</FieldLabel>
								<Input
									id={`${idPrefix}-max-output`}
									type="number"
									min={0}
									step={1}
									value={value.maxOutputTokens}
									onChange={(e) => update({ maxOutputTokens: e.target.value })}
									aria-invalid={Boolean(errors.maxOutputTokens)}
									aria-describedby={errors.maxOutputTokens ? maxOutputTokensErrorId : undefined}
								/>
								{errors.maxOutputTokens && (
									<FieldError id={maxOutputTokensErrorId}>{errors.maxOutputTokens}</FieldError>
								)}
							</Field>
						</div>

						<Field orientation="horizontal">
							<Checkbox
								id={`${idPrefix}-supports-reasoning`}
								checked={value.supportsReasoning}
								onCheckedChange={(checked) => update({ supportsReasoning: checked })}
							/>
							<FieldContent>
								<FieldLabel htmlFor={`${idPrefix}-supports-reasoning`} className="font-normal">
									Supports a reasoning mode
								</FieldLabel>
							</FieldContent>
						</Field>
					</FieldGroup>
				</CollapsibleContent>
			</Collapsible>
		</>
	);
}
