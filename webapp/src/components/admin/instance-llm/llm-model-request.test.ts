import { describe, expect, it } from "vitest";

import {
	modelCreateBodyOf,
	modelPriceBodyOf,
	modelUpdateBodyOf,
	workspaceModelCreateBodyOf,
	workspaceModelUpdateBodyOf,
} from "./llm-model-request";
import type { LlmModelFieldsValue } from "./LlmModelFields";

const priced = {
	pricingMode: "PRICED",
	per1mInputUsd: 1.25,
	per1mOutputUsd: 10,
	per1mCacheReadUsd: 0.125,
	per1mCacheWriteUsd: 1.5,
	note: "ignored while priced",
} as const;

function fieldsOf(overrides: Partial<LlmModelFieldsValue> = {}): LlmModelFieldsValue {
	return {
		displayName: "  GPT-5  ",
		upstreamModelId: "  openai/gpt-5  ",
		contextWindow: "400000",
		maxOutputTokens: "128000",
		reasoningEffort: "HIGH",
		operatedBy: "PROVIDER",
		brand: "OPENAI",
		dataHandlingNote: "  No retention.  ",
		enabled: true,
		price: priced,
		...overrides,
	};
}

describe("modelCreateBodyOf", () => {
	it("maps each field, trimmed and with limits as numbers", () => {
		expect(modelCreateBodyOf(fieldsOf())).toStrictEqual({
			displayName: "GPT-5",
			contextWindow: 400_000,
			maxOutputTokens: 128_000,
			operatedBy: "PROVIDER",
			brand: "OPENAI",
			dataHandlingNote: "No retention.",
			enabled: true,
			reasoningEffort: "HIGH",
			upstreamModelId: "openai/gpt-5",
		});
	});

	it("leaves out what the admin left blank", () => {
		const body = modelCreateBodyOf(
			fieldsOf({
				contextWindow: " ",
				maxOutputTokens: "",
				reasoningEffort: "PROVIDER_DEFAULT",
				operatedBy: undefined,
				brand: undefined,
				dataHandlingNote: "   ",
			}),
		);
		expect(body.contextWindow).toBeUndefined();
		expect(body.maxOutputTokens).toBeUndefined();
		expect(body.reasoningEffort).toBeUndefined();
		expect(body.operatedBy).toBeUndefined();
		expect(body.brand).toBeUndefined();
		expect(body.dataHandlingNote).toBeUndefined();
	});
});

describe("modelUpdateBodyOf", () => {
	it("sends the values and clears nothing when every field is set", () => {
		expect(modelUpdateBodyOf(fieldsOf())).toStrictEqual({
			displayName: "GPT-5",
			contextWindow: 400_000,
			maxOutputTokens: 128_000,
			operatedBy: "PROVIDER",
			brand: "OPENAI",
			dataHandlingNote: "No retention.",
			enabled: true,
			clearBrand: false,
			clearContextWindow: false,
			clearMaxOutputTokens: false,
			clearDataHandling: false,
			reasoningEffort: "HIGH",
		});
	});

	it("clears each saved value that the admin emptied", () => {
		expect(
			modelUpdateBodyOf(
				fieldsOf({
					contextWindow: "",
					maxOutputTokens: "  ",
					reasoningEffort: "PROVIDER_DEFAULT",
					operatedBy: undefined,
					brand: undefined,
				}),
			),
		).toMatchObject({
			clearBrand: true,
			clearContextWindow: true,
			clearMaxOutputTokens: true,
			clearDataHandling: true,
			clearReasoningEffort: true,
		});
	});

	it("never sends the upstream model ID, which is immutable", () => {
		expect(modelUpdateBodyOf(fieldsOf())).not.toHaveProperty("upstreamModelId");
	});
});

describe("modelPriceBodyOf", () => {
	it("sends every rate of a priced model and no note", () => {
		expect(modelPriceBodyOf(priced)).toStrictEqual({
			pricingMode: "PRICED",
			per1mInputUsd: 1.25,
			per1mOutputUsd: 10,
			per1mCacheReadUsd: 0.125,
			per1mCacheWriteUsd: 1.5,
			note: undefined,
		});
	});

	it("sends only the trimmed note of a no-charge model", () => {
		expect(
			modelPriceBodyOf({ ...priced, pricingMode: "NO_CHARGE", note: "  Free tier  " }),
		).toStrictEqual({
			pricingMode: "NO_CHARGE",
			per1mInputUsd: undefined,
			per1mOutputUsd: undefined,
			per1mCacheReadUsd: undefined,
			per1mCacheWriteUsd: undefined,
			note: "Free tier",
		});
	});

	it("sends only the mode of an unpriced model", () => {
		expect(modelPriceBodyOf({ ...priced, pricingMode: "UNPRICED" })).toEqual({
			pricingMode: "UNPRICED",
		});
	});
});

describe("workspace model requests", () => {
	it("carry the price inline, with the note named priceNote", () => {
		const fields = fieldsOf({ price: { pricingMode: "NO_CHARGE", note: " Local " } });
		expect(workspaceModelUpdateBodyOf(fields)).toEqual({
			...modelUpdateBodyOf(fields),
			pricingMode: "NO_CHARGE",
			priceNote: "Local",
		});
		expect(workspaceModelUpdateBodyOf(fields)).not.toHaveProperty("note");
	});

	it("create the model inactive even when the form says active", () => {
		const body = workspaceModelCreateBodyOf(fieldsOf({ enabled: true }));
		expect(body).toEqual({
			...modelCreateBodyOf(fieldsOf()),
			enabled: false,
			pricingMode: "PRICED",
			per1mInputUsd: 1.25,
			per1mOutputUsd: 10,
			per1mCacheReadUsd: 0.125,
			per1mCacheWriteUsd: 1.5,
		});
	});

	it("keep the form's active state on an update", () => {
		expect(workspaceModelUpdateBodyOf(fieldsOf({ enabled: true })).enabled).toBe(true);
		expect(workspaceModelUpdateBodyOf(fieldsOf({ enabled: false })).enabled).toBe(false);
	});
});
