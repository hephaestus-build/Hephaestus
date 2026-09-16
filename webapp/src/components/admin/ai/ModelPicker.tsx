import type { AvailableLlmModel } from "@/api/types.gen";
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

function optionLabel(model: AvailableLlmModel): string {
	return `${model.displayName} · ${model.connectionDisplayName} · ${priceLabel(model, "workspace")}`;
}

function ModelOptions({ models }: { models: AvailableLlmModel[] }) {
	return models.map((model) => (
		<SelectItem
			key={encode(model.scope, model.id)}
			value={encode(model.scope, model.id)}
			aria-label={optionLabel(model)}
		>
			<span className="flex min-w-0 flex-1 items-center justify-between gap-2">
				<span className="min-w-0 truncate">
					{model.displayName}
					<span className="text-muted-foreground"> · {model.connectionDisplayName}</span>
				</span>
				<span className="shrink-0 text-xs text-muted-foreground">
					{priceLabel(model, "workspace")}
				</span>
			</span>
		</SelectItem>
	));
}

export function ModelPicker({
	id,
	availableModels,
	value,
	onChange,
	disabled = false,
	invalid = false,
	"aria-describedby": ariaDescribedBy,
	"aria-labelledby": ariaLabelledBy,
}: ModelPickerProps) {
	const shared = availableModels.filter((model) => model.scope === "SHARED");
	const own = availableModels.filter((model) => model.scope === "WORKSPACE");

	return (
		<Select
			items={availableModels.map((model) => ({
				value: encode(model.scope, model.id),
				label: `${model.displayName} · ${model.connectionDisplayName}`,
			}))}
			value={value ? encode(value.scope, value.id) : null}
			onValueChange={(next) => {
				const selection = next ? decode(next) : null;
				if (selection) {
					onChange(selection);
				}
			}}
			disabled={disabled}
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
