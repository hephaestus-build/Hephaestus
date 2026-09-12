import { cn } from "cn";
import type { AvailableLlmModel } from "@/api/types.gen";
import {
	DATA_HANDLING_DEFS,
	type DataHandlingTier,
} from "@/components/practice-vocabulary/data-handling-defs";
import { statusToneClass } from "@/components/practice-vocabulary/status-def";
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
	 * Lists only models declared as this tier, for a binding row that may hold nothing else. With
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
	if ((scope !== "SHARED" && scope !== "WORKSPACE") || !Number.isInteger(id)) return null;
	return { scope, id };
}

/** The one rule for what a row may hold: every model, or only those declared as the row's tier. */
export function listedModels<TModel extends Pick<AvailableLlmModel, "dataHandlingTier">>(
	models: TModel[],
	tier: DataHandlingTier | undefined,
): TModel[] {
	return tier === undefined ? models : models.filter((model) => model.dataHandlingTier === tier);
}

function optionLabel(model: AvailableLlmModel): string {
	const tier = DATA_HANDLING_DEFS[model.dataHandlingTier].label;
	return `${model.displayName} · ${model.connectionDisplayName} · ${tier} · ${priceLabel(model, "workspace")}`;
}

function ModelOptions({ models }: { models: AvailableLlmModel[] }) {
	return models.map((model) => {
		const tier = DATA_HANDLING_DEFS[model.dataHandlingTier];
		const TierIcon = tier.icon;
		return (
			<SelectItem
				key={encode(model.scope, model.id)}
				value={encode(model.scope, model.id)}
				aria-label={optionLabel(model)}
			>
				<span className="flex min-w-0 flex-1 flex-col gap-0.5">
					<span className="flex min-w-0 items-center justify-between gap-2">
						<span className="min-w-0 truncate">
							{model.displayName}
							<span className="text-muted-foreground"> · {model.connectionDisplayName}</span>
						</span>
						<span className="shrink-0 text-xs text-muted-foreground">
							{priceLabel(model, "workspace")}
						</span>
					</span>
					<span className="flex items-center gap-1 text-xs text-muted-foreground">
						<TierIcon className={cn("size-3.5", statusToneClass(tier.badgeVariant))} aria-hidden />
						{tier.label}
					</span>
				</span>
			</SelectItem>
		);
	});
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
			// Every model, not only the listed ones: a bound model whose declaration no longer matches
			// the row's tier still needs its name on the trigger, even though it is no longer offered.
			items={availableModels.map((model) => ({
				value: encode(model.scope, model.id),
				label: `${model.displayName} · ${model.connectionDisplayName}`,
			}))}
			value={value ? encode(value.scope, value.id) : null}
			onValueChange={(next) => {
				const selection = next ? decode(next) : null;
				if (selection) onChange(selection);
			}}
			disabled={disabled || listed.length === 0}
		>
			<SelectTrigger
				id={id}
				className="w-full"
				aria-invalid={invalid}
				aria-describedby={ariaDescribedBy}
			>
				<SelectValue placeholder="Select a model…" />
			</SelectTrigger>
			<SelectContent aria-labelledby={ariaLabelledBy}>
				{shared.length > 0 && (
					<SelectGroup>
						<SelectLabel>Shared models</SelectLabel>
						<ModelOptions models={shared} />
					</SelectGroup>
				)}
				{own.length > 0 && (
					<SelectGroup>
						<SelectLabel>Your models</SelectLabel>
						<ModelOptions models={own} />
					</SelectGroup>
				)}
			</SelectContent>
		</Select>
	);
}
