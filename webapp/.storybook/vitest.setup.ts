import { clearAllMocks } from "storybook/test";
import { afterEach, beforeEach, type MockInstance, vi } from "vitest";

/**
 * React reports duplicate or missing keys only on `console.error` and renders on. A row with a
 * shared key can be dropped or duplicated, so a story that logs one fails here.
 */
const KEY_ERRORS = [
	/Encountered two children with the same key/u,
	/Each child in a list should have a unique "key" prop/u,
];

let consoleErrors: MockInstance | undefined;

beforeEach(() => {
	clearAllMocks();
	consoleErrors = vi.spyOn(console, "error");
});

afterEach(() => {
	const keyErrors = (consoleErrors?.mock.calls ?? [])
		.map((args) => args.map(String).join(" "))
		.filter((text) => KEY_ERRORS.some((pattern) => pattern.test(text)));
	consoleErrors?.mockRestore();
	if (keyErrors.length > 0) {
		throw new Error(`React reported a key error:\n${keyErrors.join("\n")}`);
	}
});
