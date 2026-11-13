// The composer's own model conversation in one protocol, as review-composer-model.ts writes it: the session's input, a
// reply with one tool call, the tool's answer with the session's nudge, a reply without calls, and the session's
// finish. Each answer is a fixed answer of the protocol.
// Usage: node review-composer-conversations.ts <session JSON>. Prints {"steps": [{"request": …, "answer": …}, …]}.
import {
	type Answer,
	ModelSession,
	type Protocol,
} from "../../../main/resources/agent/review-composer-model.ts";

interface SessionInput {
	model: {
		protocol: Protocol;
		modelId: string;
		reasoningEffort: string | null;
		maxOutputTokens: number;
		temperature: number | null;
	};
	system: string;
	input: string;
	nudge: string;
	finish: string;
	checked: string;
	tools: { name: string; description: string; parameters: Record<string, unknown> }[];
}

const ARGUMENTS = { draftId: "d-1", score: 1 };
const ANSWERS: Record<Protocol, unknown[]> = {
	"anthropic-messages": [
		{
			content: [
				{ type: "text", text: "Checking." },
				{ type: "tool_use", id: "call-1", name: "check_package", input: ARGUMENTS },
			],
			stop_reason: "tool_use",
		},
		{ content: [{ type: "text", text: "Done." }], stop_reason: "end_turn" },
		{ content: [{ type: "text", text: "Final." }], stop_reason: "end_turn" },
	],
	"openai-completions": [
		{
			choices: [
				{
					message: {
						role: "assistant",
						content: null,
						refusal: null,
						reasoning_content: "Thinking.",
						tool_calls: [
							{
								id: "call-1",
								type: "function",
								function: { name: "check_package", arguments: JSON.stringify(ARGUMENTS) },
							},
						],
					},
				},
			],
		},
		{ choices: [{ message: { role: "assistant", content: "Done." } }] },
		{ choices: [{ message: { role: "assistant", content: "Final." } }] },
	],
	"openai-responses": [
		{
			output: [
				{ type: "reasoning", id: "rs-1", encrypted_content: "opaque", summary: [] },
				{
					type: "function_call",
					id: "fc-1",
					call_id: "call-1",
					name: "check_package",
					arguments: JSON.stringify(ARGUMENTS),
				},
			],
		},
		{
			output: [
				{ type: "message", role: "assistant", content: [{ type: "output_text", text: "Done." }] },
			],
		},
		{
			output: [
				{ type: "message", role: "assistant", content: [{ type: "output_text", text: "Final." }] },
			],
		},
	],
};

const [raw] = process.argv.slice(2);
if (raw === undefined) {
	throw new Error("Expected the session JSON");
}
const isRecord = (value: unknown): value is Record<string, unknown> =>
	typeof value === "object" && value !== null && !Array.isArray(value);
const isSession = (value: unknown): value is SessionInput =>
	isRecord(value) &&
	isRecord(value.model) &&
	typeof value.model.protocol === "string" &&
	value.model.protocol in ANSWERS &&
	["system", "input", "nudge", "finish", "checked"].every(
		(field) => typeof value[field] === "string",
	) &&
	Array.isArray(value.tools);
const session: unknown = JSON.parse(raw);
if (!isSession(session)) {
	throw new Error("The session JSON is not a composer session");
}
const answers = ANSWERS[session.model.protocol];
const steps: { request: unknown; answer: unknown }[] = [];
const model = new ModelSession(
	async (_protocol, body): Promise<Answer> => {
		const answer = answers[steps.length];
		const request: unknown = JSON.parse(body);
		steps.push({ request, answer });
		return { status: 200, body: JSON.stringify(answer), refusal: null };
	},
	session.model,
	session.system,
	session.tools,
);
const first = await model.send(session.input);
const call = first.toolCalls[0];
if (call === undefined) {
	throw new Error("The first answer holds no tool call");
}
await model.answer([{ callId: call.id, text: session.checked }], session.nudge);
await model.send(session.finish);
process.stdout.write(JSON.stringify({ steps }));
