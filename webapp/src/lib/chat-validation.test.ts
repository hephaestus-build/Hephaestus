import { assert, describe, expect, it } from "vitest";

import type { ChatMessage as ThreadMessage } from "@/api/types.gen";

import { parseThreadMessages } from "./chat-validation";

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
