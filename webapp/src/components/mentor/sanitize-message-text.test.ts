import { describe, expect, it } from "vitest";

import { sanitizeMessageText } from "./sanitize-message-text";

describe("sanitizeMessageText", () => {
	it.each([
		["", ""],
		["Hello world", "Hello world"],
		["<has_function_call>", ""],
		["Hello <has_function_call>world<has_function_call>", "Hello world"],
		["<has_function_call><has_function_call>Hello", "Hello"],
		[
			"Use `<example>` in your code.\nKeep this text.",
			"Use `<example>` in your code.\nKeep this text.",
		],
	])("removes only the function-call sentinel from %j", (input, expected) => {
		expect(sanitizeMessageText(input)).toBe(expected);
	});
});
