import { ruleTester } from "../rule-tester.ts";
import { noContextProvider } from "./no-context-provider.ts";

ruleTester.run("no-context-provider", noContextProvider, {
	valid: [
		"const a = <ThemeContext value={theme}>{children}</ThemeContext>;",
		// A module namespace that exports a component named `Provider` is not a context.
		"const a = <TanstackQuery.Provider>{children}</TanstackQuery.Provider>;",
		"const a = <Tooltip.Provider delay={0} />;",
	],
	invalid: [
		{
			code: "const a = <ThemeContext.Provider value={theme}>{children}</ThemeContext.Provider>;",
			errors: [{ messageId: "provider", line: 1, column: 12, endColumn: 33 }],
		},
		{
			code: "const a = <AuthContext.Provider value={value} />;",
			errors: [{ messageId: "provider", line: 1, column: 12, endColumn: 32 }],
		},
	],
});
