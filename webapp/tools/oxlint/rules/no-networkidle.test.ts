import { ruleTester } from "../rule-tester.ts";
import { noNetworkidle } from "./no-networkidle.ts";

ruleTester.run("no-networkidle", noNetworkidle, {
	valid: [
		"await settled(page);",
		'await page.waitForLoadState("domcontentloaded");',
		'await page.goto("/login", { waitUntil: "load" });',
		// A sentence that names the state is not a wait for it.
		'const note = "networkidle never settles while a request is open";',
		// An interpolated message only starts with the word.
		// oxlint-disable-next-line no-template-curly-in-string -- the case is source code with a template.
		"console.warn(`networkidle${detail}`);",
	],
	invalid: [
		{
			code: 'await page.waitForLoadState("networkidle");',
			errors: [{ messageId: "wait", line: 1, column: 29 }],
		},
		{
			code: 'await page.goto("/", { waitUntil: "networkidle" });',
			errors: [{ messageId: "wait" }],
		},
		{
			code: "await page.waitForLoadState(`networkidle`);",
			errors: [{ messageId: "wait" }],
		},
	],
});
