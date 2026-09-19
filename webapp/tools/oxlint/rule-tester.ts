import type { Rule } from "@oxlint/plugins";
import { RuleTester } from "oxlint/plugins-dev";

import registeredPlugin from "./index.ts";

// `eslintCompat` gives the 1-based columns the cases are written in. Every file here is TSX unless
// a case names a file with another extension, which then wins.
const tester = new RuleTester({
	eslintCompat: true,
	languageOptions: { parserOptions: { lang: "tsx" } },
});

export const ruleTester = {
	run(ruleName: string, rule: Rule, tests: RuleTester.TestCases): void {
		// A rule the plugin does not export lints nothing, however green its suite is.
		if (registeredPlugin.rules[ruleName] !== rule) {
			throw new Error(`Rule ${ruleName} is not registered by the Hephaestus plugin`);
		}
		tester.run(ruleName, rule, tests);
	},
};
