import { type MouseEvent, type ReactNode, useId } from "react";

import type { AvailableLlmModel } from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import { AiMark } from "@/components/icons/AiMark";
import {
	AGENT_PURPOSE_DEFS,
	type AgentPurpose,
	isPrecomputePurpose,
} from "@/components/practice-vocabulary/agent-purpose-defs";
import {
	DATA_HANDLING_DEFS,
	type DataHandlingTier,
} from "@/components/practice-vocabulary/data-handling-defs";
import { DataHandlingMark } from "@/components/practice-vocabulary/DataHandlingMark";
import { PURSE_DEFS } from "@/components/practice-vocabulary/purse-defs";
import { FieldDescription } from "@/components/ui/field";
import {
	Select,
	SelectContent,
	SelectGroup,
	SelectItem,
	SelectLabel,
	SelectTrigger,
	SelectValue,
} from "@/components/ui/select";
import { LLM_API_PROTOCOL_LABELS, precomputeApiFor } from "@/lib/llm-api-protocol-labels";
import { priceLabel } from "@/lib/llm-pricing";
import { hasText } from "@/lib/text";

/** The anchor of the workspace's own providers on AI models, where a picker with nothing to offer sends the admin. */
export const PROVIDER_PANEL_ID = "provider-panel";

/**
 * Scrolls to the providers and moves focus there. The router owns the URL, so a bare fragment jump
 * would go through it; with no panel on the page, the link falls back to that jump.
 */
function revealProviderPanel(event: MouseEvent<HTMLAnchorElement>) {
	const panel = event.currentTarget.ownerDocument.getElementById(PROVIDER_PANEL_ID);
	if (!panel) {
		return;
	}
	event.preventDefault();
	panel.scrollIntoView({ block: "start" });
	panel.focus({ preventScroll: true });
}

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
	/**
	 * The purpose the model is assigned to. A precompute purpose with no model at all says what to
	 * connect, and a chosen model that will not serve it well says why, under the picker.
	 */
	purpose?: AgentPurpose;
	/**
	 * Whether this workspace may add its own providers and models. It decides who the hints send the
	 * admin to: the connection form below, or an instance admin.
	 */
	ownProviderAllowed: boolean;
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

/** What a screen reader hears for an option: everything the two lines show, in reading order. */
function optionLabel(model: AvailableLlmModel): string {
	const tier = DATA_HANDLING_DEFS[model.dataHandlingTier].label;
	return `${model.displayName}, ${model.connectionDisplayName}, ${tier}, ${priceLabel(model, "workspace")}`;
}

const REASONING_HINT =
	"This model reasons before it answers. Decisions need a model that answers at once with token probabilities.";
/**
 * The host's limit is a budget and the workspace's own is a cap. Only an instance admin prices a
 * shared model; the workspace prices its own.
 */
const RERANK_UNPRICED_HINT = {
	SHARED:
		"If this provider reports no tokens, its calls count as unpriced, which pauses reviews while their spend has a monthly budget. Ask an instance admin to set No metered API cost for a self-hosted reranker.",
	WORKSPACE:
		"If this provider reports no tokens, its calls count as unpriced, which pauses reviews while their spend has a monthly cap. Set No metered API cost on this model below for a self-hosted reranker.",
} satisfies Record<ModelSelection["scope"], string>;

/** What the picker says under itself for `purpose`, from the models it was given and the one chosen. */
function purposeHint(
	purpose: AgentPurpose | undefined,
	availableModels: AvailableLlmModel[],
	selected: AvailableLlmModel | undefined,
	ownProviderAllowed: boolean,
): ReactNode {
	if (purpose === undefined) {
		return undefined;
	}
	if (!isPrecomputePurpose(purpose)) {
		return undefined;
	}
	const { noun } = AGENT_PURPOSE_DEFS[purpose];
	if (availableModels.length === 0) {
		if (!ownProviderAllowed) {
			return `No ${noun} yet. Ask an instance admin to share one.`;
		}
		const api = precomputeApiFor(purpose);
		return (
			<>
				No {noun} yet. Connect a provider
				{api === undefined ? "" : ` with the ${LLM_API_PROTOCOL_LABELS[api].label}`} under{" "}
				<InlineLink render={<a href={`#${PROVIDER_PANEL_ID}`} onClick={revealProviderPanel} />}>
					Your providers
				</InlineLink>
				, then add its model.
			</>
		);
	}
	// `NONE` is an effort that asks for no reasoning, which is what a decision needs.
	const reasons = selected?.reasoningEffort !== undefined && selected.reasoningEffort !== "NONE";
	if (purpose === "PRACTICE_DECISION" && reasons) {
		return REASONING_HINT;
	}
	if (
		purpose === "PRACTICE_RERANKING" &&
		selected !== undefined &&
		selected.pricingMode !== "NO_CHARGE"
	) {
		return RERANK_UNPRICED_HINT[selected.scope];
	}
	return undefined;
}

/**
 * Each model on two lines: its mark, name and price, then where it runs, muted: the connection and
 * the tier's icon and name, as every other surface draws a tier.
 */
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
						<span className="min-w-0 truncate">{model.displayName}</span>
						<span className="shrink-0 text-xs text-muted-foreground">
							{priceLabel(model, "workspace")}
						</span>
					</span>
					<span className="flex min-w-0 items-center gap-3 text-xs text-muted-foreground">
						<span className="min-w-0 truncate">{model.connectionDisplayName}</span>
						<DataHandlingMark tier={model.dataHandlingTier} label="visible" />
					</span>
				</span>
			</span>
		</SelectItem>
	));
}

/** The closed picker: the chosen model's mark and name, as the cells on AI models draw it. */
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
	purpose,
	ownProviderAllowed,
	disabled = false,
	invalid = false,
	"aria-describedby": ariaDescribedBy,
	"aria-labelledby": ariaLabelledBy,
}: ModelPickerProps) {
	const hintId = useId();
	const listed = listedModels(availableModels, tier);
	const shared = listed.filter((model) => model.scope === "SHARED");
	const own = listed.filter((model) => model.scope === "WORKSPACE");
	const selected = availableModels.find(
		(model) => model.scope === value?.scope && model.id === value.id,
	);
	const hint = purposeHint(purpose, availableModels, selected, ownProviderAllowed);
	let describedBy = ariaDescribedBy;
	if (hint !== undefined) {
		describedBy = hasText(ariaDescribedBy) ? `${ariaDescribedBy} ${hintId}` : hintId;
	}

	return (
		<>
			<Select
				// Every model, not only the listed ones: a bound model whose declaration no longer matches
				// the row's tier still needs its name on the trigger, even though it is no longer offered.
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
					aria-describedby={describedBy}
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
			{hint !== undefined && <FieldDescription id={hintId}>{hint}</FieldDescription>}
		</>
	);
}
