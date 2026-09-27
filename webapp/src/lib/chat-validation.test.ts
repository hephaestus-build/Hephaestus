import { readUIMessageStream, type UIMessageChunk } from "ai";
import { assert, describe, expect, it } from "vitest";

import type { ChatMessage as ThreadMessage } from "@/api/types.gen";
import type { ChatMessage } from "@/lib/types";

import { parseThreadMessages, shownFeedbackText } from "./chat-validation";

const UUID = "f47ac10b-58cc-4372-a567-0e02b2c3d479";
const UUID2 = "c9bf9e57-1685-4c89-bafb-ff5af830be8a";

function msg(id: string, role: string) {
	return { id, role, parts: [{ type: "text", text: "hi" }] };
}

describe("parseThreadMessages", () => {
	it("accepts well-formed messages and preserves part fields the schema does not name", () => {
		const result = parseThreadMessages([
			{ id: UUID, role: "user", parts: [{ type: "text", text: "hi", extra: 1 }] },
			msg(UUID2, "assistant"),
		]);
		assert(result);
		expect(result).toHaveLength(2);
		const [first] = result;
		assert(first);
		expect(first.id).toBe(UUID);
		expect(first.parts[0]).toMatchObject({ extra: 1 });
	});

	it("accepts a stored message as the generated client hands it over, createdAt already a Date", () => {
		const stored = {
			...msg(UUID, "assistant"),
			metadata: { status: "completed" },
			createdAt: new Date("2026-09-24T09:15:04.512Z"),
		} satisfies ThreadMessage;

		expect(parseThreadMessages([stored])).toStrictEqual([stored]);
	});

	it.each([
		["an ISO string", "2026-09-24T09:15:04.512Z"],
		["an Invalid Date", new Date("not a timestamp")],
	])("rejects a createdAt that is %s", (_name, createdAt) => {
		expect(parseThreadMessages([{ ...msg(UUID, "user"), createdAt }])).toBeUndefined();
	});

	it("rejects a non-UUID message id", () => {
		expect(parseThreadMessages([msg("msg-1", "user")])).toBeUndefined();
	});

	it("rejects an unknown role", () => {
		expect(parseThreadMessages([msg(UUID, "robot")])).toBeUndefined();
	});

	it("rejects a non-array payload", () => {
		expect(parseThreadMessages({ id: UUID })).toBeUndefined();
		expect(parseThreadMessages(null)).toBeUndefined();
	});
});

async function streamed(chunks: UIMessageChunk[]): Promise<ChatMessage> {
	let last: ChatMessage | undefined;
	const stream = new ReadableStream<UIMessageChunk>({
		start(controller) {
			for (const chunk of chunks) {
				controller.enqueue(chunk);
			}
			controller.close();
		},
	});
	for await (const message of readUIMessageStream<ChatMessage>({ stream })) {
		last = message;
	}
	assert(last, "The stream produced no message");
	return last;
}

function shown(parts: ChatMessage["parts"]): string[] {
	return parts.map(shownFeedbackText).filter((text) => text !== undefined);
}

describe("shownFeedbackText", () => {
	const OBSERVATION = "3f0c2b4e-8a1d-4c6e-9b7f-2d5e8a1c4b6f";

	it("reads a streamed reply's feedback exactly as it reads the stored reply", async () => {
		const message = await streamed([
			{ type: "start", messageId: UUID },
			{ type: "text-start", id: "text-0" },
			// oxlint-disable-next-line shadcn/no-raw-colors -- an AI SDK chunk type, not a Tailwind colour class
			{ type: "text-delta", id: "text-0", delta: "Let me look." },
			{ type: "text-end", id: "text-0" },
			{
				type: "data-observation",
				id: "a1",
				data: { observationId: OBSERVATION, text: "Name the trade-off." },
			},
			{
				type: "data-observation",
				id: "a2",
				data: { observationId: OBSERVATION, text: "Link the issue it closes." },
			},
			{ type: "data-observation", id: "a3", data: { observationId: OBSERVATION } },
			{ type: "finish" },
		]);
		const stored = parseThreadMessages([structuredClone(message)]);
		assert(stored?.[0]);

		expect(shown(message.parts)).toStrictEqual([
			"Name the trade-off.",
			"Link the issue it closes.",
		]);
		expect(shown(stored[0].parts)).toStrictEqual(shown(message.parts));
	});

	it.each([
		["a link stored before links carried feedback", { observationId: UUID }],
		["blank feedback", { observationId: UUID, text: "  " }],
		["a malformed payload", { observationId: 7, text: "Name the trade-off." }],
	])("shows nothing for %s", (_name, data) => {
		const part = parseThreadMessages([
			{ id: UUID, role: "assistant", parts: [{ type: "data-observation", id: "p", data }] },
		])?.[0]?.parts[0];
		assert(part, "A stored link must stay readable");
		expect(shownFeedbackText(part)).toBeUndefined();
	});

	it("shows nothing for prose, which the message renders as text", () => {
		expect(shownFeedbackText({ type: "text", text: "Name the trade-off." })).toBeUndefined();
	});
});
