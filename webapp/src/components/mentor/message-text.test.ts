import { describe, expect, it } from "vitest";

import type { ChatMessage } from "@/lib/types";

import { visibleTexts } from "./message-text";

describe("visibleTexts", () => {
	it("keeps the reply's text and the feedback it links, in order, and nothing else", () => {
		const message: ChatMessage = {
			id: "reply",
			role: "assistant",
			parts: [
				{ type: "reasoning", text: "The reader asked about the description.", state: "done" },
				{ type: "text", text: "Let me look at your pull request." },
				{
					type: "data-observation",
					id: "link-1",
					data: {
						observationId: "3f0c2b4e-8a1d-4c6e-9b7f-2d5e8a1c4b6f",
						text: "Your description names the decision but not why it beat the alternative.",
					},
				},
				// Stored before links carried their feedback: it shows nothing.
				{
					type: "data-observation",
					id: "link-2",
					data: { observationId: "c9bf9e57-1685-4c89-bafb-ff5af830be8a" },
				},
				{ type: "text", text: "  " },
				{ type: "text", text: "What made you pick it?" },
			],
		};

		expect(visibleTexts(message)).toStrictEqual([
			"Let me look at your pull request.",
			"Your description names the decision but not why it beat the alternative.",
			"What made you pick it?",
		]);
	});
});
