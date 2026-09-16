import { readFileSync } from "node:fs";
import { join } from "node:path";

import { describe, expect, it } from "vitest";

import { ruleTester } from "../rule-tester.ts";
import { ASSERT_FUNCTION_NAMES, asRegExp, playMustAssert } from "./play-must-assert.ts";

ruleTester.run("play-must-assert", playMustAssert, {
	valid: [
		"export const Open: Story = { play: async ({ canvas }) => { canvas.getByRole('dialog'); } };",
		"export const Open: Story = { play: async ({ canvas, userEvent }) => { await userEvent.click(canvas.getByRole('button')); await canvas.findByRole('dialog'); } };",
		"export const Open: Story = { play: async ({ canvas }) => { await expect(canvas.getByRole('button').disabled).toBe(true); } };",
		// The shared helpers in `src/test/` all carry the `expect` prefix.
		"export const Wide: Story = { play: async () => { await expectNoPageOverflow(); } };",
		"export const Wide: Story = { play: async ({ canvas }) => { await expectSettledVisible(canvas.getByRole('menu')); } };",
		// Scoping into a subtree still ends in a query.
		"export const Row: Story = { play: async ({ canvas }) => { within(canvas.getByRole('row')).getByText('Zeus'); } };",
		// A step wrapper does not hide what is inside it.
		"export const Flow: Story = { play: async ({ canvas, step }) => { await step('open', async () => { canvas.getByRole('dialog'); }); } };",
		// Delegating to another story's play inherits that story's assertions.
		"export const Then: Story = { play: async (context) => { await Open.play?.(context); await context.canvas.findByText('Saved'); } };",
		// A `play` that is not a function literal is somebody else's body.
		"export const Open: Story = { play: sharedPlay };",
		// A key computed from an expression names whatever that evaluates to, which is unreadable here.
		"export const Open: Story = { [key]: async ({ userEvent }) => { await userEvent.click(trigger); } };",
	],
	invalid: [
		{
			code: "export const Open: Story = { play: async ({ userEvent }) => { await userEvent.click(trigger); } };",
			errors: [{ messageId: "noAssertion", line: 1, column: 30, endColumn: 34 }],
		},
		{
			code: "export const Open: Story = { play: async () => { await new Promise((resolve) => setTimeout(resolve, 100)); } };",
			errors: [{ messageId: "noAssertion" }],
		},
		{
			code: "export const Open: Story = { play({ canvas }) { canvas.querySelector('[data-slot=table]'); } };",
			errors: [{ messageId: "noAssertion" }],
		},
		{
			// A `queryBy*` returns null instead of throwing, so on its own it asserts nothing.
			code: "export const Open: Story = { play: async ({ canvas }) => { canvas.queryByRole('alert'); } };",
			errors: [{ messageId: "noAssertion" }],
		},
		{
			// A key computed from a literal names exactly that literal.
			code: "export const Open: Story = { ['play']: async ({ userEvent }) => { await userEvent.click(trigger); } };",
			errors: [{ messageId: "noAssertion" }],
		},
		{
			// `HTMLMediaElement.play()` is not a story delegating to another story's play function.
			code: "export const Sound: Story = { play: async () => { await audio.play(); } };",
			errors: [{ messageId: "noAssertion" }],
		},
		{
			code: "export const Sound: Story = { play: async () => { await videoRef.current.play(); } };",
			errors: [{ messageId: "noAssertion" }],
		},
		{
			// One story's assertion does not carry over to the next story in the file.
			code: "export const First: Story = { play: async ({ canvas }) => { canvas.getByRole('dialog'); } };\nexport const Second: Story = { play: async ({ userEvent }) => { await userEvent.click(trigger); } };",
			errors: [{ messageId: "noAssertion", line: 2, column: 32, endColumn: 36 }],
		},
		{
			// Nor does an assertion made at module scope, outside any play.
			code: "expect(theme).toBe('dark');\nexport const Open: Story = { play: async ({ userEvent }) => { await userEvent.click(trigger); } };",
			errors: [{ messageId: "noAssertion", line: 2, column: 30, endColumn: 34 }],
		},
	],
});

const isRecord = (value: unknown): value is Record<string, unknown> =>
	typeof value === "object" && value !== null;

const isUnknownArray = (value: unknown): value is readonly unknown[] => Array.isArray(value);

/**
 * The `assertFunctionNames` `.oxlintrc.json` hands `vitest/expect-expect`.
 *
 * The file is JSONC, and every comment in it stands on a line of its own. A trailing one would fail
 * `JSON.parse` here — loudly — rather than silently drop the entry it trails.
 */
function configuredAssertFunctionNames(): readonly string[] {
	// Resolved as a path rather than through `new URL(…, import.meta.url)`: these tests run in jsdom,
	// whose `URL` resolves a relative reference against the document's origin, not the module's.
	const here = import.meta.dirname;
	const source = readFileSync(join(here, "../../../.oxlintrc.json"), "utf8");
	const json = source
		.split("\n")
		.filter((line) => !line.trimStart().startsWith("//"))
		.join("\n");
	const config: unknown = JSON.parse(json);
	const rules = isRecord(config) ? config.rules : undefined;
	const entry = isRecord(rules) ? rules["vitest/expect-expect"] : undefined;
	const [, options] = isUnknownArray(entry) ? entry : [];
	const stated = isRecord(options) ? options.assertFunctionNames : undefined;
	const names = isUnknownArray(stated)
		? stated.filter((name): name is string => typeof name === "string")
		: [];
	if (names.length === 0) {
		throw new Error(
			"`vitest/expect-expect` in `webapp/.oxlintrc.json` states no assert functions.",
		);
	}
	return names;
}

describe("assertFunctionNames", () => {
	// `vitest/expect-expect` and this rule answer the same question about different files, so what the
	// config counts as an assertion must also be one here — otherwise the same call passes inside a
	// `test()` body and fails inside a `play`. The rule's list is the wider one: it adds the `expect*`
	// and `assert*` prefixes that reach the shared helpers in `src/test/`. A literal cannot be shared
	// across JSON and TypeScript, so the relation between the two lists is what gets pinned.
	it("holds every name the config configures `vitest/expect-expect` with", () => {
		const patterns = ASSERT_FUNCTION_NAMES.map(asRegExp);
		for (const name of configuredAssertFunctionNames()) {
			expect(
				patterns.some((pattern) => pattern.test(name)),
				`\`${name}\` is an assertion to \`vitest/expect-expect\` but not to \`play-must-assert\``,
			).toBe(true);
		}
	});
});
