import { describe, expect, it } from "vitest";

import { messageSubject } from "./ConversationMessage";

describe("messageSubject", () => {
	it("names no author for a message kind that this build does not know", () => {
		const createdAt = new Date("2026-10-08T10:42:00");

		expect(messageSubject({ kind: "SYSTEM_NOTE", createdAt })).toMatch(/^Message, /u);
		expect(messageSubject({ kind: "HEPH", createdAt })).toMatch(/^Hephaestus, /u);
		expect(messageSubject({ kind: "USER", createdAt })).not.toMatch(/^(?:Hephaestus|Message), /u);
	});
});
