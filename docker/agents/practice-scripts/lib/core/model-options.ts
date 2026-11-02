import { z } from "zod";

export const MAX_OUTPUT_TOKENS = 4096;

const jsonObject = z.record(z.string(), z.json());
const text = z.object({ type: z.literal("text"), text: z.string() });
const inlineFileData = z.discriminatedUnion("type", [
	z.object({ type: z.literal("data"), data: z.union([z.string(), z.instanceof(Uint8Array)]) }),
	z.object({
		type: z.literal("url"),
		url: z.union([z.instanceof(URL), z.url().transform((value) => new URL(value))]),
		originalUrl: z.string().optional(),
	}),
]);
const fileData = z.discriminatedUnion("type", [
	...inlineFileData.options,
	z.object({ type: z.literal("reference"), reference: z.record(z.string(), z.string()) }),
	z.object({ type: z.literal("text"), text: z.string() }),
]);
const file = z.object({
	type: z.literal("file"),
	data: fileData,
	mediaType: z.string(),
	filename: z.string().optional(),
});
const custom = z.object({ type: z.literal("custom") });
const toolResultOutput = z.discriminatedUnion("type", [
	z.object({ type: z.enum(["text", "error-text"]), value: z.string() }),
	z.object({ type: z.enum(["json", "error-json"]), value: z.json() }),
	z.object({ type: z.literal("execution-denied"), reason: z.string().optional() }),
	z.object({ type: z.literal("content"), value: z.array(z.union([text, file, custom])) }),
]);
const toolResult = z.object({
	type: z.literal("tool-result"),
	toolCallId: z.string(),
	toolName: z.string(),
	output: toolResultOutput,
});
const toolCall = z.object({
	type: z.literal("tool-call"),
	toolCallId: z.string(),
	toolName: z.string(),
	input: z.json(),
	providerExecuted: z.boolean().optional(),
});
const prompt = z.array(
	z.discriminatedUnion("role", [
		z.object({ role: z.literal("system"), content: z.string() }),
		z.object({ role: z.literal("user"), content: z.array(z.union([text, file])) }),
		z.object({
			role: z.literal("assistant"),
			content: z.array(
				z.union([
					text,
					file,
					custom.extend({ kind: z.templateLiteral([z.string(), ".", z.string()]) }),
					z.object({ type: z.literal("reasoning"), text: z.string() }),
					z.object({
						type: z.literal("reasoning-file"),
						data: inlineFileData,
						mediaType: z.string(),
					}),
					toolCall,
					toolResult,
				]),
			),
		}),
		z.object({
			role: z.literal("tool"),
			content: z.array(
				z.union([
					toolResult,
					z.object({
						type: z.literal("tool-approval-response"),
						approvalId: z.string(),
						approved: z.boolean(),
						reason: z.string().optional(),
					}),
				]),
			),
		}),
	]),
);
const functionTool = z.object({
	type: z.literal("function"),
	name: z.string(),
	description: z.string().optional(),
	inputSchema: jsonObject,
	inputExamples: z.array(z.object({ input: jsonObject })).optional(),
	strict: z.boolean().optional(),
});

// Object schemas strip host-only options, including providerOptions nested in messages and tools.
export const chatOptionsSchema = z.object({
	prompt,
	tools: z
		.array(z.union([functionTool, z.object({ type: z.literal("provider") })]))
		.transform((tools) => tools.flatMap((tool) => (tool.type === "function" ? [tool] : [])))
		.optional(),
	toolChoice: z
		.union([
			z.object({ type: z.enum(["auto", "none", "required"]) }),
			z.object({ type: z.literal("tool"), toolName: z.string() }),
		])
		.optional(),
	responseFormat: z
		.discriminatedUnion("type", [
			z.object({ type: z.literal("text") }),
			z.object({
				type: z.literal("json"),
				schema: jsonObject.optional(),
				name: z.string().optional(),
				description: z.string().optional(),
			}),
		])
		.optional(),
	temperature: z.number().default(0),
	topP: z.number().optional(),
	seed: z.int().optional(),
	stopSequences: z.array(z.string()).optional(),
	maxOutputTokens: z
		.int()
		.positive()
		.transform((value) => Math.min(value, MAX_OUTPUT_TOKENS))
		.default(MAX_OUTPUT_TOKENS),
});
const decisionInput = z.union([z.string(), jsonObject, z.array(z.json())]);
const described = decisionInput.nullable();
const instructions = described.optional();
// No decision model that the host binds answers a `box` question, so the script's `decide` refuses one before IPC.
export const decisionOptionsSchema = z.object({
	state: z.array(z.union([text, file, z.object({ type: z.literal("json"), value: z.json() })])),
	questions: z.record(
		z.string(),
		z.discriminatedUnion("type", [
			z
				.object({
					type: z.literal("boolean"),
					instructions,
					criteria: z
						.object({ true: described.optional(), false: described.optional() })
						.optional(),
				})
				.refine(
					(question) =>
						question.instructions != null ||
						question.criteria?.true != null ||
						question.criteria?.false != null,
					"a boolean question needs instructions or a criterion",
				),
			z.object({
				type: z.literal("choice"),
				instructions,
				criteria: z.record(z.string(), described),
			}),
			z.object({
				type: z.literal("score"),
				instructions,
				criteria: z.array(described),
			}),
		]),
	),
});
export const embeddingOptionsSchema = z.object({ values: z.array(z.string()) });
export const rerankingOptionsSchema = z.object({
	query: z.string(),
	documents: z.discriminatedUnion("type", [
		z.object({ type: z.literal("text"), values: z.array(z.string()) }),
		z.object({ type: z.literal("object"), values: z.array(jsonObject) }),
	]),
	topN: z.int().positive().optional(),
});
