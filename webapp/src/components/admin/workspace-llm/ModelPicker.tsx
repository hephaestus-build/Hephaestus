import { definitionFor } from "@/components/common/status-def";

import type { AvailableLlmModel } from "@/api/types.gen";
import { AiMark } from "@/components/icons/AiMark";
import {
	UNKNOWN_DATA_HANDLING_DEF,
	DATA_HANDLING_DEFS,
	type DataHandlingTier,
} from "@/components/practice-vocabulary/data-handling-defs";
import { DataHandlingMark } from "@/components/practice-vocabulary/DataHandlingMark";
import { PURSE_DEFS } from "@/components/practice-vocabulary/purse-defs";
import {
	Select,
	SelectContent,
	SelectGroup,
	SelectItem,
	SelectLabel,
	SelectTrigger,
	SelectValue,
} from "@/components/ui/select";
import { priceLabel } from "@/lib/llm-pricing";
import { hasText } from "@/lib/text";

export interface ModelSelection {
	scope: "SHARED" | "WORKSPACE";
	id: number;
}

export interface ModelPickerProps {
	id?: string;
	availableModels: AvailableLlmModel[];
	value: ModelSelection | null;
	onChange: (selection: ModelSelection) => void;
	/**
	 * Lists only models declared as this tier, for an assignment that may hold nothing else. With
	 * nothing to list the picker disables itself; the caller says what that means, from the same
	 * `listedModels`, because only it knows whether the reader can add a model.
	 */
	tier?: DataHandlingTier;
	disabled?: boolean;
	invalid?: boolean;
	"aria-describedby"?: string;
	/** Points at the caller's visible label; only the caller can say what the listbox is a list of. */
	"aria-labelledby": string;
}

function encode(scope: ModelSelection["scope"], id: number): string {
	return `${scope}:${id}`;
}

function decode(value: string): ModelSelection | null {
	const [scope, rawId] = value.split(":");
	const id = Number(rawId);
	if ((scope !== "SHARED" && scope !== "WORKSPACE") || !Number.isInteger(id)) {
		return null;
	}
	return { scope, id };
}

/** The one rule for what a row may hold: every model, or only those declared as the row's tier. */
export function listedModels<TModel extends Pick<AvailableLlmModel, "dataHandlingTier">>(
	models: TModel[],
	tier: DataHandlingTier | undefined,
): TModel[] {
	return tier === undefined ? models : models.filter((model) => model.dataHandlingTier === tier);
}

/** The option's words in the order it shows them (WCAG 2.5.3). */
function optionLabel(model: AvailableLlmModel): string {
	const tier = definitionFor(
		DATA_HANDLING_DEFS,
		model.dataHandlingTier,
		UNKNOWN_DATA_HANDLING_DEF,
	).label;
	return `${model.displayName} ${priceLabel(model, "workspace")} ${model.connectionDisplayName} ${tier}`;
}

function ModelOptions({ models }: { models: AvailableLlmModel[] }) {
	return models.map((model) => (
		<SelectItem
			key={encode(model.scope, model.id)}
			value={encode(model.scope, model.id)}
			aria-label={optionLabel(model)}
		>
			<span className="flex min-w-0 flex-1 items-start gap-2">
				<AiMark brand={model.brand} size="sm" />
				<span className="flex min-w-0 flex-1 flex-col gap-0.5">
					{/* The price follows the name: an option is as wide as its words, so a column for
					    prices at its far edge would not line up from one option to the next. */}
					<span className="flex min-w-0 items-baseline gap-3">
						<span className="min-w-0 truncate">{model.displayName}</span>{" "}
						<span className="shrink-0 text-xs text-muted-foreground">
							{priceLabel(model, "workspace")}
						</span>
					</span>{" "}
					<span className="flex min-w-0 items-center gap-3 text-xs text-muted-foreground">
						<span className="min-w-0 truncate">{model.connectionDisplayName}</span>{" "}
						<DataHandlingMark tier={model.dataHandlingTier} label="visible" />
					</span>
				</span>
			</span>
		</SelectItem>
	));
}

function TriggerValue({ current, models }: { current: unknown; models: AvailableLlmModel[] }) {
	if (typeof current !== "string") {
		return "Select a model…";
	}
	const model = models.find((candidate) => encode(candidate.scope, candidate.id) === current);
	if (model === undefined) {
		return "A model no longer offered here";
	}
	return (
		<>
			<AiMark brand={model.brand} size="sm" />
			<span className="min-w-0 truncate">{model.displayName}</span>
		</>
	);
}

export function ModelPicker({
	id,
	availableModels,
	value,
	onChange,
	tier,
	disabled = false,
	invalid = false,
	"aria-describedby": ariaDescribedBy,
	"aria-labelledby": ariaLabelledBy,
}: ModelPickerProps) {
	const listed = listedModels(availableModels, tier);
	const shared = listed.filter((model) => model.scope === "SHARED");
	const own = listed.filter((model) => model.scope === "WORKSPACE");

	return (
		<Select
			// A saved model excluded by the current tier filter must still be named in the trigger.
			items={availableModels.map((model) => ({
				value: encode(model.scope, model.id),
				label: model.displayName,
			}))}
			value={value ? encode(value.scope, value.id) : null}
			onValueChange={(next) => {
				const selection = hasText(next) ? decode(next) : null;
				if (selection) {
					onChange(selection);
				}
			}}
			disabled={disabled || listed.length === 0}
		>
			<SelectTrigger
				id={id}
				className="w-full"
				aria-invalid={invalid}
				aria-describedby={ariaDescribedBy}
			>
				<SelectValue>
					{(current: unknown) => <TriggerValue current={current} models={availableModels} />}
				</SelectValue>
			</SelectTrigger>
			<SelectContent aria-labelledby={ariaLabelledBy}>
				{shared.length > 0 && (
					<SelectGroup>
						<SelectLabel>{PURSE_DEFS.SHARED.label}</SelectLabel>
						<ModelOptions models={shared} />
					</SelectGroup>
				)}
				{own.length > 0 && (
					<SelectGroup>
						<SelectLabel>{PURSE_DEFS.OWN_PROVIDER.label}</SelectLabel>
						<ModelOptions models={own} />
					</SelectGroup>
				)}
			</SelectContent>
		</Select>
	);
}
