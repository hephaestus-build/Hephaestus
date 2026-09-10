import { AlertTriangle } from "lucide-react";
import { useId } from "react";
import type { LlmModel } from "@/api/types.gen";
import {
	Select,
	SelectContent,
	SelectItem,
	SelectTrigger,
	SelectValue,
} from "@/components/ui/select";
import { LLM_PROCESSING_LOCATIONS } from "@/lib/llm-processing-location";

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Checkbox } from "@/components/ui/checkbox";
import {
	Field,
	FieldContent,
	FieldDescription,
	FieldError,
	FieldLabel,
} from "@/components/ui/field";
import { Input } from "@/components/ui/input";
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
	processingLocation: LlmModel["processingLocation"];
	enabled: boolean;
	price: PriceModeValue;
}

type EditedModel = {
	displayName: string;
	upstreamModelId: string;
	contextWindow?: number;
	maxOutputTokens?: number;
	supportsReasoning?: boolean;
	processingLocation?: LlmModel["processingLocation"];
	enabled?: boolean;
};

export function modelFieldsValueOf(
	model: EditedModel | null,
	price: PriceModeValue,
): LlmModelFieldsValue {
	return {
		displayName: model?.displayName ?? "",
		upstreamModelId: model?.upstreamModelId ?? "",
		contextWindow: model?.contextWindow != null ? String(model.contextWindow) : "",
		maxOutputTokens: model?.maxOutputTokens != null ? String(model.maxOutputTokens) : "",
		supportsReasoning: model?.supportsReasoning ?? false,
		processingLocation: model?.processingLocation ?? "UNCLASSIFIED",
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
		...value.price,
	});
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
			"Practice reviews and Mentor can't run on it until you reactivate it, or until each workspace picks another model.",
	},
	workspace: {
		displayNamePlaceholder: "e.g. GPT-5 mini",
		upstreamIdPlaceholder: "e.g. openai/gpt-5-mini",
		upstreamIdHint: "The exact id your provider expects. Slashes are part of the id.",
		activeHintEdit: "Only active models with a declared price can be selected.",
		activeHintCreate: "Starts inactive. Add a price, then activate.",
		deactivationTitle: "Work on this model stops immediately",
		deactivationBody:
			"Practice reviews and the mentor can't run until you reactivate this model or pick another.",
	},
} satisfies Record<LlmAudience, Record<string, string>>;

export interface LlmModelFieldsProps {
	audience: LlmAudience;
	idPrefix: string;
	isEdit: boolean;
	wasEnabled: boolean;
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
	const suggestionsId = `${idPrefix}-upstream-id-options`;

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

			<div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
				<Field data-invalid={Boolean(errors.contextWindow)}>
					<FieldLabel htmlFor={`${idPrefix}-context-window`}>
						Context window <span className="font-normal text-muted-foreground">(optional)</span>
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
						Max output tokens <span className="font-normal text-muted-foreground">(optional)</span>
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

			<Field>
				<FieldLabel htmlFor={`${idPrefix}-processing-location`}>Processing location</FieldLabel>
				<Select
					items={LLM_PROCESSING_LOCATIONS}
					value={value.processingLocation}
					onValueChange={(location) => {
						if (location) update({ processingLocation: location });
					}}
				>
					<SelectTrigger
						id={`${idPrefix}-processing-location`}
						aria-describedby={`${idPrefix}-processing-location-help`}
					>
						<SelectValue />
					</SelectTrigger>
					<SelectContent aria-label="Processing location">
						{LLM_PROCESSING_LOCATIONS.map((location) => (
							<SelectItem key={location.value} value={location.value}>
								{location.label}
							</SelectItem>
						))}
					</SelectContent>
				</Select>
				<FieldDescription id={`${idPrefix}-processing-location-help`}>
					Declare where this model actually processes data, including any gateway routing.
					Unclassified models cannot serve a member's location choice. Changing this can stop active
					work.
				</FieldDescription>
			</Field>

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

			<PriceModeEditor
				audience={audience}
				idPrefix={`${idPrefix}-price`}
				value={value.price}
				onChange={(price) => onChange(withPrice(value, price))}
				errors={errors}
			/>
		</>
	);
}
