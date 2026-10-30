import { ruleTester } from "../rule-tester.ts";
import { focusOutlineInForcedColors } from "./focus-outline-in-forced-colors.ts";

ruleTester.run("focus-outline-in-forced-colors", focusOutlineInForcedColors, {
	valid: [
		'const a = <a className="focus-visible:outline-hidden focus-visible:ring-2" />;',
		'const b = "focus:outline-hidden";',
		// Other utilities that only start with the same words.
		'const c = "outline-none-ish outline-hidden-ish";',
	],
	invalid: [
		{
			code: 'const d = <a className="rounded-sm outline-none focus-visible:ring-2" />;',
			errors: [{ messageId: "outlineNone" }],
		},
		{
			code: 'const e = cn("block", "focus-visible:outline-none");',
			errors: [{ messageId: "outlineNone" }],
		},
		{
			code: "const f = `block outline-none`;",
			errors: [{ messageId: "outlineNone" }],
		},
		{
			code: 'const g = <h2 tabIndex={-1} className="font-semibold outline-hidden" />;',
			errors: [{ messageId: "bareOutlineHidden" }],
		},
		// The important marker, before or after the utility, still removes the fallback.
		{ code: 'const h = "outline-none!";', errors: [{ messageId: "outlineNone" }] },
		{ code: 'const i = "focus-visible:!outline-none";', errors: [{ messageId: "outlineNone" }] },
		{ code: 'const j = "focus-visible:outline-none!";', errors: [{ messageId: "outlineNone" }] },
		{ code: 'const k = "outline-hidden!";', errors: [{ messageId: "bareOutlineHidden" }] },
	],
});
