import { AlertTriangle, ChevronDown, ChevronsUpDownIcon } from "lucide-react";
import { useId, useState } from "react";

import type { LlmModelLimits } from "@/api/types.gen";
import { FactList } from "@/components/auth/FactList";
import { definitionFor, type StatusDefs, statusValues } from "@/components/common/status-def";
import {
	AI_MODEL_BRANDS,
	AI_MODEL_BRAND_META,
	type AiModelBrand,
} from "@/components/icons/ai-model-brand-logos";
import { AiMark } from "@/components/icons/AiMark";
import {
	UNKNOWN_DATA_HANDLING_DEF,
	DATA_HANDLING_DEFS,
	type DataHandlingTier,
	deriveDataHandlingTier,
	OPERATED_BY_DEFS,
	type OperatedBy,
} from "@/components/practice-vocabulary/data-handling-defs";
import { DataHandlingBadge } from "@/components/practice-vocabulary/DataHandlingBadge";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import {
	Combobox,
	ComboboxContent,
	ComboboxEmpty,
	ComboboxIcon,
	ComboboxItem,
	ComboboxItemIndicator,
	ComboboxList,
	ComboboxSearchInput,
	ComboboxTrigger,
} from "@/components/ui/combobox";
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
import { Switch } from "@/components/ui/switch";
import {
	type FieldErrors,
	type LlmModelFormField,
	validateLlmModelForm,
} from "@/lib/llm-form-validation";
import type { LlmAudience } from "@/lib/llm-pricing";
import {
	isReasoningEffortChoice,
	PROVIDER_DEFAULT_EFFORT,
	REASONING_EFFORT_CHOICES,
	type ReasoningEffort,
	type ReasoningEffortChoice,
} from "@/lib/reasoning-effort";
import { hasText } from "@/lib/text";

import { PriceModeEditor, type PriceModeValue } from "./PriceModeEditor";

export interface LlmModelFieldsValue {
	displayName: string;
	upstreamModelId: string;
	contextWindow: string;
	maxOutputTokens: string;
	reasoningEffort: ReasoningEffortChoice;
	/** Absent until the admin declares the model, as the wire carries it. */
	operatedBy?: OperatedBy;
	brand?: AiModelBrand;
	dataHandlingNote: string;
	enabled: boolean;
	price: PriceModeValue;
}

interface EditedModel {
	displayName: string;
	upstreamModelId: string;
	contextWindow?: number;
	maxOutputTokens?: number;
	reasoningEffort?: ReasoningEffort;
	operatedBy?: OperatedBy;
	brand?: AiModelBrand;
	dataHandlingNote?: string;
	enabled?: boolean;
}

export function modelFieldsValueOf(
	model: EditedModel | null,
	price: PriceModeValue,
): LlmModelFieldsValue {
	return {
		displayName: model?.displayName ?? "",
		upstreamModelId: model?.upstreamModelId ?? "",
		contextWindow: model?.contextWindow == null ? "" : String(model.contextWindow),
		maxOutputTokens: model?.maxOutputTokens == null ? "" : String(model.maxOutputTokens),
		reasoningEffort: model?.reasoningEffort ?? PROVIDER_DEFAULT_EFFORT,
		operatedBy: model?.operatedBy,
		brand: model?.brand,
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
		dataHandlingNote: value.dataHandlingNote,
		...value.price,
	});
}

export function modelDetailsBodyOf(value: LlmModelFieldsValue) {
	return {
		operatedBy: value.operatedBy,
		brand: value.brand,
		dataHandlingNote: value.dataHandlingNote.trim() || undefined,
	};
}

const canBeActive = (price: PriceModeValue) => price.pricingMode !== "UNPRICED";

const BRAND_OPTIONS = ["NONE", ...AI_MODEL_BRANDS] as const;

function brandLabel(brand: (typeof BRAND_OPTIONS)[number]): string {
	return brand === "NONE" ? "Not listed or unknown" : AI_MODEL_BRAND_META[brand].label;
}

const withPrice = (value: LlmModelFieldsValue, price: PriceModeValue): LlmModelFieldsValue => ({
	...value,
	price,
	enabled: value.enabled && canBeActive(price),
});

const COPY = {
	instance: {
		displayNamePlaceholder: "e.g. GPT-5",
		upstreamIdPlaceholder: "e.g. openai/gpt-5",
		upstreamIdHint: "The exact ID the provider expects. Slashes are part of the ID.",
		activeHintEdit: "Only active models can be selected for new workspace requests.",
		activeHintCreate:
			"New models are saved inactive. Review the saved price and sharing before activating.",
		deactivationTitle: "Work on this model stops immediately, in every workspace",
		deactivationBody:
			"Practice reviews and conversations cannot run on it until you reactivate it, or until each workspace picks another model.",
	},
	workspace: {
		displayNamePlaceholder: "e.g. GPT-5 mini",
		upstreamIdPlaceholder: "e.g. openai/gpt-5-mini",
		upstreamIdHint: "The exact ID your provider expects. Slashes are part of the ID.",
		activeHintEdit: "Only active models with a declared price can be selected.",
		activeHintCreate: "Starts inactive. Add a price, then activate.",
		deactivationTitle: "Work on this model stops immediately",
		deactivationBody:
			"Practice reviews and conversations cannot run until you reactivate this model or pick another.",
	},
} satisfies Record<LlmAudience, Record<string, string>>;

interface FactChoiceProps<TValue extends string> {
	idPrefix: string;
	labelId: string;
	label: string;
	defs: StatusDefs<TValue>;
	value: TValue | undefined;
	onChange: (value: TValue) => void;
}

/** One fact as a radio group of cards: icon, title and the sentence the admin is agreeing to. */
function FactChoice<TValue extends string>({
	idPrefix,
	labelId,
	label,
	defs,
	value,
	onChange,
}: FactChoiceProps<TValue>) {
	return (
		<Field>
			<FieldTitle id={labelId}>{label}</FieldTitle>
			<RadioGroup
				// `undefined` would make Base UI treat the group as uncontrolled for its whole life, so
				// "Leave undeclared" could never clear it; `null` is its controlled "nothing checked".
				value={value ?? null}
				onValueChange={(next) => {
					if (next !== null) {
						onChange(next);
					}
				}}
				className="gap-3"
				aria-labelledby={labelId}
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

const formatTokens = (count: number) => count.toLocaleString("en-US");

/** Where a limit's value comes from, and what the field does to it. */
function limitHint(
	value: number | undefined,
	source: LlmModelLimits["contextWindowSource"],
): string {
	if (value === undefined || source === undefined) {
		return "Unknown. Test the connection to read it from the provider, or enter it.";
	}
	switch (source) {
		case "ADMIN": {
			return "You set this value. Clear it to use the provider's value, if it reports one.";
		}
		case "DISCOVERED": {
			return `The provider reports ${formatTokens(value)}. Enter a value to override it.`;
		}
		case "CATALOG": {
			return `The provider's documentation states ${formatTokens(value)}. Enter a value to override it.`;
		}
		case "DERIVED": {
			return `${formatTokens(value)}: a quarter of the context window, at most 16,384. Enter a value to override it.`;
		}
	}
}

interface TokenLimitFieldProps {
	id: string;
	label: string;
	value: string;
	onChange: (value: string) => void;
	error?: string;
	/** The value the model runs with. */
	limit?: number;
	/** Its source; `null` while the model is created, when there is nothing to describe yet. */
	source: LlmModelLimits["contextWindowSource"] | null;
}

/** One token limit: the admin's override, with the value the model runs with and where it comes from. */
function TokenLimitField({
	id,
	label,
	value,
	onChange,
	error,
	limit,
	source,
}: TokenLimitFieldProps) {
	const errorId = useId();
	const hintId = `${id}-hint`;
	const described = source !== null;
	const describedBy = [described ? hintId : "", hasText(error) ? errorId : ""]
		.filter(Boolean)
		.join(" ");
	return (
		<Field data-invalid={Boolean(error)}>
			<FieldLabel htmlFor={id}>
				{label} <span className="font-normal text-muted-foreground">(optional)</span>
			</FieldLabel>
			<Input
				id={id}
				type="number"
				min={0}
				step={1}
				value={value}
				placeholder={
					source && source !== "ADMIN" && limit !== undefined ? String(limit) : undefined
				}
				onChange={(e) => onChange(e.target.value)}
				aria-invalid={Boolean(error)}
				aria-describedby={describedBy || undefined}
			/>
			{described && <FieldDescription id={hintId}>{limitHint(limit, source)}</FieldDescription>}
			{hasText(error) && <FieldError id={errorId}>{error}</FieldError>}
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
	/** The limits the saved model runs with, each with its source; absent while the model is created. */
	limits?: LlmModelLimits;
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
	limits,
}: LlmModelFieldsProps) {
	const copy = COPY[audience];
	const update = (patch: Partial<LlmModelFieldsValue>) => onChange({ ...value, ...patch });

	const displayNameErrorId = useId();
	const upstreamModelIdErrorId = useId();
	const dataHandlingNoteErrorId = useId();
	const suggestionsId = `${idPrefix}-upstream-id-options`;

	const previewTier = deriveDataHandlingTier(value.operatedBy);
	const declared = value.operatedBy !== undefined;
	// A declared row holds only its exact tier, while the undeclared row takes any model, so
	// only a model leaving a declared tier drops out of the rows that hold it.
	const leftTier =
		savedTier !== undefined && savedTier !== "UNDECLARED" && previewTier !== savedTier
			? savedTier
			: undefined;

	// A field the admin cannot see cannot be corrected, so an error inside keeps the disclosure open.
	const [showAdvanced, setShowAdvanced] = useState(false);
	const advancedHasError =
		errors.contextWindow !== undefined || errors.maxOutputTokens !== undefined;
	// A model that cannot run shows why, and the two fields that fix it.
	const unbounded = limits !== undefined && !limits.bounded;

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
					aria-describedby={hasText(errors.displayName) ? displayNameErrorId : undefined}
				/>
				{hasText(errors.displayName) && (
					<FieldError id={displayNameErrorId}>{errors.displayName}</FieldError>
				)}
			</Field>

			<Field data-invalid={Boolean(errors.upstreamModelId)}>
				<FieldLabel htmlFor={`${idPrefix}-upstream-id`}>Upstream model ID</FieldLabel>
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
					aria-describedby={hasText(errors.upstreamModelId) ? upstreamModelIdErrorId : undefined}
				/>
				{upstreamIdSuggestions && upstreamIdSuggestions.length > 0 && (
					<datalist id={suggestionsId}>
						{upstreamIdSuggestions.map((id) => (
							<option key={id} value={id} />
						))}
					</datalist>
				)}
				<FieldDescription>
					{isEdit ? "Create a new model to use a different upstream ID." : copy.upstreamIdHint}
				</FieldDescription>
				{hasText(errors.upstreamModelId) && (
					<FieldError id={upstreamModelIdErrorId}>{errors.upstreamModelId}</FieldError>
				)}
			</Field>

			<Field>
				<FieldLabel id={`${idPrefix}-brand-label`} htmlFor={`${idPrefix}-brand`}>
					Model maker <span className="font-normal text-muted-foreground">(optional)</span>
				</FieldLabel>
				<Combobox
					items={BRAND_OPTIONS}
					value={value.brand ?? "NONE"}
					onValueChange={(brand) => {
						if (brand !== null) {
							update({ brand: brand === "NONE" ? undefined : brand });
						}
					}}
					itemToStringLabel={brandLabel}
				>
					<ComboboxTrigger id={`${idPrefix}-brand`} className="w-full justify-between">
						<span className="flex min-w-0 items-center gap-2">
							{value.brand && <AiMark brand={value.brand} size="sm" />}
							<span className="truncate">{brandLabel(value.brand ?? "NONE")}</span>
						</span>
						<ComboboxIcon render={<ChevronsUpDownIcon className="size-4 opacity-50" />} />
					</ComboboxTrigger>
					<ComboboxContent align="start">
						<ComboboxSearchInput
							placeholder="Search model makers…"
							aria-label="Search model makers"
						/>
						<ComboboxEmpty>No matching maker. Leave this blank if it is not listed.</ComboboxEmpty>
						<ComboboxList aria-labelledby={`${idPrefix}-brand-label`}>
							{(brand: (typeof BRAND_OPTIONS)[number]) => (
								<ComboboxItem key={brand} value={brand}>
									{brand !== "NONE" && <AiMark brand={brand} size="sm" />}
									<span className="truncate">{brandLabel(brand)}</span>
									<ComboboxItemIndicator />
								</ComboboxItem>
							)}
						</ComboboxList>
					</ComboboxContent>
				</Combobox>
				<FieldDescription>
					Shown beside this model in workspace AI choices. It does not identify the service that
					receives requests or who operates the model.
				</FieldDescription>
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
					Declare who operates the connection. Check the provider agreement for data location,
					retention, and training terms.
				</FieldDescription>

				<FactChoice
					idPrefix={`${idPrefix}-operated-by`}
					labelId={`${idPrefix}-operated-by-label`}
					label="Operated by"
					defs={OPERATED_BY_DEFS}
					value={value.operatedBy}
					onChange={(operatedBy) => update({ operatedBy })}
				/>

				{declared && (
					<Button
						type="button"
						variant="ghost"
						size="sm"
						className="-ml-2 w-fit"
						onClick={() => update({ operatedBy: undefined })}
					>
						Leave undeclared
					</Button>
				)}

				{leftTier && (
					<Alert variant="warning">
						<AlertTriangle aria-hidden />
						<AlertTitle>
							Assignments holding this model as{" "}
							{definitionFor(DATA_HANDLING_DEFS, leftTier, UNKNOWN_DATA_HANDLING_DEF).label} stop
							serving
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
						aria-describedby={
							hasText(errors.dataHandlingNote) ? dataHandlingNoteErrorId : undefined
						}
					/>
					<FieldDescription>
						Region, agreement or renewal date. Only admins see it.
					</FieldDescription>
					{hasText(errors.dataHandlingNote) && (
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
							Choose who operates this model to declare it.
						</p>
					) : (
						<FactList
							facts={
								definitionFor(DATA_HANDLING_DEFS, previewTier, UNKNOWN_DATA_HANDLING_DEF).facts
							}
						/>
					)}
				</div>
			</FieldSet>

			{unbounded && (
				<Alert variant="warning">
					<AlertTriangle aria-hidden />
					<AlertTitle>This model cannot run yet</AlertTitle>
					<AlertDescription>
						Hephaestus does not know its output limit, so it refuses its calls. Set Max output
						tokens or Context window under Limits and capabilities, or test the connection to read
						them from the provider.
					</AlertDescription>
				</Alert>
			)}

			<Collapsible
				open={showAdvanced || advancedHasError || unbounded}
				onOpenChange={setShowAdvanced}
			>
				<CollapsibleTrigger
					render={
						<Button type="button" variant="ghost" size="sm" className="group/adv -ml-2">
							Limits and capabilities
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
							<TokenLimitField
								id={`${idPrefix}-context-window`}
								label="Context window"
								value={value.contextWindow}
								onChange={(contextWindow) => update({ contextWindow })}
								error={errors.contextWindow}
								limit={limits?.contextWindow}
								source={limits === undefined ? null : limits.contextWindowSource}
							/>
							<TokenLimitField
								id={`${idPrefix}-max-output`}
								label="Max output tokens"
								value={value.maxOutputTokens}
								onChange={(maxOutputTokens) => update({ maxOutputTokens })}
								error={errors.maxOutputTokens}
								limit={limits?.maxOutputTokens}
								source={limits === undefined ? null : limits.maxOutputTokensSource}
							/>
						</div>

						<Field>
							<FieldLabel
								id={`${idPrefix}-reasoning-effort-label`}
								htmlFor={`${idPrefix}-reasoning-effort`}
							>
								Reasoning effort
							</FieldLabel>
							<Select
								items={REASONING_EFFORT_CHOICES}
								value={value.reasoningEffort}
								onValueChange={(next) => {
									if (isReasoningEffortChoice(next)) {
										update({ reasoningEffort: next });
									}
								}}
							>
								<SelectTrigger
									id={`${idPrefix}-reasoning-effort`}
									aria-describedby={`${idPrefix}-reasoning-effort-description`}
								>
									<SelectValue />
								</SelectTrigger>
								<SelectContent aria-labelledby={`${idPrefix}-reasoning-effort-label`}>
									{REASONING_EFFORT_CHOICES.map((choice) => (
										<SelectItem key={choice.value} value={choice.value}>
											{choice.label}
										</SelectItem>
									))}
								</SelectContent>
							</Select>
							<FieldDescription id={`${idPrefix}-reasoning-effort-description`}>
								Provider default sends no effort setting. Supported levels and defaults depend on
								the model and provider. Choose only a supported level. None requests no reasoning.
							</FieldDescription>
						</Field>
					</FieldGroup>
				</CollapsibleContent>
			</Collapsible>
		</>
	);
}
