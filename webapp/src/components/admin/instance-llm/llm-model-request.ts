import type {
	CreateLlmModelRequest,
	CreateWorkspaceLlmModelRequest,
	UpdateLlmModelPriceRequest,
	UpdateLlmModelRequest,
	UpdateWorkspaceLlmModelRequest,
} from "@/api/types.gen";
import { reasoningEffortOf, reasoningEffortUpdateOf } from "@/lib/reasoning-effort";

import type { LlmModelFieldsValue } from "./LlmModelFields";
import type { PriceModeValue } from "./PriceModeEditor";

function tokenLimitOf(text: string): number | undefined {
	return text.trim() ? Number(text) : undefined;
}

/** What a create and an update carry the same way, in both consoles. */
function modelMetadataOf(fields: LlmModelFieldsValue) {
	return {
		displayName: fields.displayName.trim(),
		contextWindow: tokenLimitOf(fields.contextWindow),
		maxOutputTokens: tokenLimitOf(fields.maxOutputTokens),
		operatedBy: fields.operatedBy,
		brand: fields.brand,
		dataHandlingNote: fields.dataHandlingNote.trim() || undefined,
		enabled: fields.enabled,
	} satisfies CreateLlmModelRequest & UpdateLlmModelRequest;
}

export function modelCreateBodyOf(fields: LlmModelFieldsValue): CreateLlmModelRequest {
	return {
		...modelMetadataOf(fields),
		reasoningEffort: reasoningEffortOf(fields.reasoningEffort),
		upstreamModelId: fields.upstreamModelId.trim(),
	};
}

/** An update names what it clears: an absent field leaves the saved value as it is. */
export function modelUpdateBodyOf(fields: LlmModelFieldsValue): UpdateLlmModelRequest {
	return {
		...modelMetadataOf(fields),
		clearBrand: fields.brand === undefined,
		// A blank limit field hands the limit back to the provider's metadata.
		clearContextWindow: !fields.contextWindow.trim(),
		clearMaxOutputTokens: !fields.maxOutputTokens.trim(),
		clearDataHandling: fields.operatedBy === undefined,
		...reasoningEffortUpdateOf(fields.reasoningEffort),
	};
}

/** Only the figures of the chosen mode are sent, so a mode switch never keeps stale rates. */
export function modelPriceBodyOf(price: PriceModeValue): UpdateLlmModelPriceRequest {
	const priced = price.pricingMode === "PRICED";
	return {
		pricingMode: price.pricingMode,
		per1mInputUsd: priced ? price.per1mInputUsd : undefined,
		per1mOutputUsd: priced ? price.per1mOutputUsd : undefined,
		per1mCacheReadUsd: priced ? price.per1mCacheReadUsd : undefined,
		per1mCacheWriteUsd: priced ? price.per1mCacheWriteUsd : undefined,
		note: price.pricingMode === "NO_CHARGE" ? price.note?.trim() : undefined,
	};
}

/** The workspace catalog has no price endpoint, so its model requests carry the price, with the note
 * named `priceNote`. */
function workspaceModelPriceOf(price: PriceModeValue) {
	const { note, ...figures } = modelPriceBodyOf(price);
	return { ...figures, priceNote: note } satisfies Partial<CreateWorkspaceLlmModelRequest> &
		Partial<UpdateWorkspaceLlmModelRequest>;
}

/** A workspace model is always created inactive. */
export function workspaceModelCreateBodyOf(
	fields: LlmModelFieldsValue,
): CreateWorkspaceLlmModelRequest {
	return { ...modelCreateBodyOf(fields), ...workspaceModelPriceOf(fields.price), enabled: false };
}

export function workspaceModelUpdateBodyOf(
	fields: LlmModelFieldsValue,
): UpdateWorkspaceLlmModelRequest {
	return { ...modelUpdateBodyOf(fields), ...workspaceModelPriceOf(fields.price) };
}
