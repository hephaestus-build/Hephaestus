import { z } from "zod";

import type { LlmModel } from "@/api/types.gen";
import type { PricingMode } from "@/lib/llm-pricing";

/**
 * The LLM forms set `noValidate`, so the native constraint attributes never fire and this is the
 * only thing enforcing them.
 *
 * Deliberately does not mirror the server's instance-state rules (host resolution, egress allowlist,
 * loopback): guessing at those here would reject setups an operator has legitimately enabled.
 */

export type FieldErrors<TField extends string> = Partial<Record<TField, string>>;

/** `fields` is the form's own field list: an issue against anything else is not one a form can show. */
function firstIssuePerField<TField extends string>(
	error: z.ZodError,
	fields: readonly TField[],
): FieldErrors<TField> {
	const errors: FieldErrors<TField> = {};
	for (const issue of error.issues) {
		const field = fields.find((candidate) => candidate === issue.path[0]);
		if (field !== undefined && errors[field] === undefined) {
			errors[field] = issue.message;
		}
	}
	return errors;
}

const displayNameSchema = z
	.string()
	.trim()
	.min(1, "A display name is required.")
	.max(128, "Use 128 characters or fewer.");

/** Credentials, query and fragment are rejected: that is how a gateway URL smuggles a key into logs. */
const baseUrlSchema = z
	.string()
	.trim()
	.min(1, "A base URL is required.")
	.max(2048, "Use 2048 characters or fewer.")
	.superRefine((value, ctx) => {
		let url: URL;
		try {
			url = new URL(value);
		} catch {
			ctx.addIssue({
				code: "custom",
				message: "Enter a full URL, including https:// — for example https://api.openai.com/v1.",
			});
			return;
		}
		if (url.protocol !== "https:" && url.protocol !== "http:") {
			ctx.addIssue({
				code: "custom",
				message: "Enter a full URL, including https:// — for example https://api.openai.com/v1.",
			});
			return;
		}
		if (url.username !== "" || url.password !== "" || url.search !== "" || url.hash !== "") {
			ctx.addIssue({
				code: "custom",
				message: "Remove any credentials, query string or fragment from the URL.",
			});
		}
	});

/** The server stores these as a Java `int`, which rejects anything larger at deserialisation. */
const MAX_TOKEN_COUNT = 2_147_483_647;

const tokenCountSchema = z
	.string()
	.trim()
	.refine(
		(value) => value === "" || /^\d+$/.test(value),
		"Enter a whole number of tokens, or leave it blank.",
	)
	.refine(
		(value) => value === "" || !/^\d+$/.test(value) || Number(value) <= MAX_TOKEN_COUNT,
		`Enter ${MAX_TOKEN_COUNT.toLocaleString("en-US")} tokens or fewer.`,
	);

const rateSchema = z
	.number()
	.refine(Number.isFinite, "Enter an amount in USD.")
	.min(0, "Rates can't be negative.");

const LLM_CONNECTION_FORM_FIELDS = ["displayName", "baseUrl"] as const;

export type LlmConnectionFormField = (typeof LLM_CONNECTION_FORM_FIELDS)[number];

export interface LlmConnectionFormValue {
	displayName: string;
	/** Omitted when editing: the endpoint is immutable. */
	baseUrl?: string;
}

const llmConnectionFormSchema = z.object({
	displayName: displayNameSchema,
	baseUrl: baseUrlSchema.optional(),
});

export function validateLlmConnectionForm(
	value: LlmConnectionFormValue,
): FieldErrors<LlmConnectionFormField> {
	const result = llmConnectionFormSchema.safeParse(value);
	return result.success ? {} : firstIssuePerField(result.error, LLM_CONNECTION_FORM_FIELDS);
}

const LLM_MODEL_FORM_FIELDS = [
	"displayName",
	"upstreamModelId",
	"contextWindow",
	"maxOutputTokens",
	"pricingMode",
	"per1mInputUsd",
	"per1mOutputUsd",
	"per1mCacheReadUsd",
	"per1mCacheWriteUsd",
	"note",
	"dataHandling",
	"trainingConfirmed",
	"dataHandlingNote",
] as const;

export type LlmModelFormField = (typeof LLM_MODEL_FORM_FIELDS)[number];

export interface LlmModelFormValue {
	displayName: string;
	/** Omitted when editing: the upstream id is immutable. */
	upstreamModelId?: string;
	contextWindow: string;
	maxOutputTokens: string;
	pricingMode: PricingMode;
	per1mInputUsd?: number;
	per1mOutputUsd?: number;
	per1mCacheReadUsd?: number;
	per1mCacheWriteUsd?: number;
	note?: string;
	operatedBy?: LlmModel["operatedBy"];
	keptAfterReply?: LlmModel["keptAfterReply"];
	/** The admin's confirmation that the model's terms rule out training; owed once facts are declared. */
	trainingConfirmed?: boolean;
	dataHandlingNote?: string;
}

const llmModelFormSchema = z
	.object({
		displayName: displayNameSchema,
		upstreamModelId: z
			.string()
			.trim()
			.min(1, "The upstream model id is required.")
			.max(256, "Use 256 characters or fewer.")
			.optional(),
		contextWindow: tokenCountSchema,
		maxOutputTokens: tokenCountSchema,
		pricingMode: z.enum(["PRICED", "NO_CHARGE", "UNPRICED"]),
		per1mInputUsd: rateSchema.optional(),
		per1mOutputUsd: rateSchema.optional(),
		per1mCacheReadUsd: rateSchema.optional(),
		per1mCacheWriteUsd: rateSchema.optional(),
		note: z.string().trim().max(500, "Use 500 characters or fewer.").optional(),
		operatedBy: z.enum(["OWN_ORGANISATION", "PROVIDER"]).optional(),
		keptAfterReply: z.enum(["NONE", "FOR_SAFETY_CHECKS"]).optional(),
		trainingConfirmed: z.boolean().optional(),
		dataHandlingNote: z.string().trim().max(200, "Use 200 characters or fewer.").optional(),
	})
	.superRefine((value, ctx) => {
		// Undeclared stays saveable: models that predate the declaration must keep working.
		if ((value.operatedBy === undefined) !== (value.keptAfterReply === undefined)) {
			ctx.addIssue({
				code: "custom",
				path: ["dataHandling"],
				message: "Declare both facts or leave data handling undeclared.",
			});
		} else if (value.operatedBy !== undefined && value.trainingConfirmed !== true) {
			ctx.addIssue({
				code: "custom",
				path: ["trainingConfirmed"],
				message: "Confirm the training guarantee, or leave data handling undeclared.",
			});
		}
		if (value.pricingMode === "PRICED") {
			const rates = [
				value.per1mInputUsd,
				value.per1mOutputUsd,
				value.per1mCacheReadUsd,
				value.per1mCacheWriteUsd,
			];
			if (value.per1mInputUsd == null) {
				ctx.addIssue({
					code: "custom",
					path: ["per1mInputUsd"],
					message: "Required when the model has a price.",
				});
			}
			if (value.per1mOutputUsd == null) {
				ctx.addIssue({
					code: "custom",
					path: ["per1mOutputUsd"],
					message: "Required when the model has a price.",
				});
			}
			const everyRateIsZero = rates.every((rate) => rate == null || rate === 0);
			const inputRateAlreadyFlagged = value.per1mInputUsd == null;
			if (everyRateIsZero && !inputRateAlreadyFlagged) {
				ctx.addIssue({
					code: "custom",
					path: ["per1mInputUsd"],
					message: "At least one rate must be above zero. For a free model, pick the free option.",
				});
			}
		}
		if (value.pricingMode === "NO_CHARGE" && !value.note?.trim()) {
			ctx.addIssue({
				code: "custom",
				path: ["note"],
				message: "Explain why no metered API rate applies.",
			});
		}
	});

export function validateLlmModelForm(value: LlmModelFormValue): FieldErrors<LlmModelFormField> {
	const result = llmModelFormSchema.safeParse(value);
	return result.success ? {} : firstIssuePerField(result.error, LLM_MODEL_FORM_FIELDS);
}
