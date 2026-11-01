import assert from "node:assert/strict";
import { describe, it } from "node:test";

import {
	conversationTools,
	type ConversationToolGateway,
} from "../../../main/resources/agent/conversation-loop-tools.ts";

function gateway(generations: string[], text = "# Index\nline\n"): ConversationToolGateway {
	return {
		async document() {
			return { text, generation: generations.shift() ?? "unknown" };
		},
		async connect() {
			throw new Error("no tools Job in this test");
		},
		async documents() {
			throw new Error("no tools Job in this test");
		},
		async load() {
			throw new Error("no load in this test");
		},
	};
}

void describe("conversationTools", () => {
	void it("refuses a document it read before once the context changed, instead of serving it from memory", async () => {
		const tools = conversationTools(gateway(["first", "first", "rotated"]));
		const { signal } = new AbortController();

		assert.deepEqual(await tools.read({ path: "INDEX.md" }, signal), {
			ok: true,
			text: "# Index\nline\n",
		});
		const first = await tools.read({ path: "INDEX.md" }, signal);
		assert.equal(first.ok, true);
		const changed = await tools.read({ path: "INDEX.md" }, signal);

		assert.ok(!changed.ok);
		assert.equal(changed.error, "CONTEXT_CHANGED");
	});

	void it("asks the gateway on every read", async () => {
		let asked = 0;
		const counting: ConversationToolGateway = {
			...gateway([]),
			async document() {
				asked += 1;
				return { text: "text", generation: "same" };
			},
		};
		const tools = conversationTools(counting);
		const { signal } = new AbortController();

		await tools.read({ path: "FIELDS.md" }, signal);
		await tools.read({ path: "FIELDS.md" }, signal);

		assert.equal(asked, 2);
	});
});
