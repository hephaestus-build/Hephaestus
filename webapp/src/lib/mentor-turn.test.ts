import { describe, expect, it } from "vitest";

import { mentorTurnBody, retryPlan } from "@/lib/mentor-turn";
import type { ChatMessage } from "@/lib/types";

const prompt: ChatMessage = {
	id: "a1b2c3d4-0000-4000-8000-000000000001",
	role: "user",
	parts: [{ type: "text", text: "Why did this practice fail?" }],
};
const reply: ChatMessage = {
	id: "a1b2c3d4-0000-4000-8000-000000000002",
	role: "assistant",
	parts: [{ type: "text", text: "Partly." }],
};

describe("mentorTurnBody", () => {
	it("sends only the latest message under the thread id", () => {
		expect(
			mentorTurnBody(
				{
					id: "thread",
					messages: [reply, prompt],
					trigger: "submit-message",
					messageId: undefined,
					requestMetadata: undefined,
				},
				"fallback",
			),
		).toStrictEqual({
			id: "thread",
			message: prompt,
			trigger: "submit-message",
			messageId: undefined,
		});
	});

	it("falls back to the client's thread id and carries a refused retry's target", () => {
		expect(
			mentorTurnBody(
				{
					id: "",
					messages: [prompt],
					trigger: "regenerate-message",
					messageId: undefined,
					requestMetadata: { retryOf: reply.id },
				},
				"fallback",
			),
		).toStrictEqual({
			id: "fallback",
			message: prompt,
			trigger: "regenerate-message",
			messageId: reply.id,
		});
	});
});

describe("retryPlan", () => {
	it("replaces the failed reply that is still in the list", () => {
		expect(retryPlan([prompt, reply], undefined)).toStrictEqual({
			replaces: reply.id,
			options: { messageId: reply.id },
		});
	});

	it("names the reply an earlier retry dropped when no new reply started", () => {
		expect(retryPlan([prompt], reply.id)).toStrictEqual({
			replaces: reply.id,
			options: { metadata: { retryOf: reply.id } },
		});
	});

	it("resends a prompt nothing answered as it is", () => {
		expect(retryPlan([prompt], undefined)).toStrictEqual({
			replaces: undefined,
			options: undefined,
		});
	});
});
