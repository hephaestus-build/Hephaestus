import { ruleTester } from "../rule-tester.ts";
import { typedStoryMeta } from "./typed-story-meta.ts";

ruleTester.run("typed-story-meta", typedStoryMeta, {
	valid: [
		"const meta = { component: Button } satisfies Meta<typeof Button>;",
		"const meta = { parameters: { layout: 'centered' } } satisfies Meta;",
		"const meta = { parameters: { docs: { description: { component: 'All icons.' } } } } satisfies Meta;",
		"const meta = { ...base } satisfies Meta;",
		"const story = { component: Button } satisfies StoryObj;",
		// The annotation spelling.
		"const meta: Meta<typeof Button> = { component: Button };",
		"const meta: Meta = { parameters: { layout: 'centered' } };",
		"const meta: StoryObj = { component: Button };",
		// An untyped object that is not a `meta` belongs to whoever declared it.
		"const preset = { component: Button };",
		"const meta = { parameters: { layout: 'centered' } };",
		"const widths = [40, 80] as const;",
		// No object literal is stated here, so there is nothing to check the type argument against.
		"let meta: Meta;",
		"const meta: Meta = base;",
		"const meta = makeMeta();",
		"const { meta } = presets;",
		"export default meta;",
		// A key computed from an expression names whatever that evaluates to, which is unreadable here.
		"const meta = { [key]: Button } satisfies Meta;",
		// A type argument is stated, which is all this rule asks; `typescript/no-explicit-any` owns
		// the question of what was put in it.
		"const meta = { component: Button } satisfies Meta<any>;",
	],
	invalid: [
		{
			code: "const meta = { parameters: { layout: 'centered' }, component: Button } satisfies Meta;",
			errors: [{ messageId: "untyped", line: 1, column: 14, endColumn: 71 }],
		},
		{
			code: "const meta = { 'component': Button } satisfies Meta;",
			errors: [{ messageId: "untyped" }],
		},
		{
			// A key computed from a literal names exactly that literal.
			code: "const meta = { ['component']: Button } satisfies Meta;",
			errors: [{ messageId: "untyped" }],
		},
		{
			// `import type * as SB from "@storybook/react-vite"` states the same bare `Meta`.
			code: "const meta = { component: Button } satisfies SB.Meta;",
			errors: [{ messageId: "untyped" }],
		},
		{
			code: "const meta: Meta = { parameters: { layout: 'centered' }, component: Button };",
			errors: [{ messageId: "untyped" }],
		},
		{
			code: "const meta = { component: Button } as Meta<typeof Button>;",
			errors: [{ messageId: "asserted", line: 1, column: 39, endColumn: 58 }],
		},
		{
			// Even without a `component`, the assertion is what stops the check.
			code: "const meta = { parameters: { layout: 'centered' } } as Meta;",
			errors: [{ messageId: "asserted" }],
		},
		{
			code: "const meta = { component: Button };",
			errors: [{ messageId: "unchecked", line: 1, column: 14, endColumn: 35 }],
		},
		{
			// CSF3 lets the meta go straight out of the default export, under no name at all.
			code: "export default { component: Button };",
			errors: [{ messageId: "unchecked", line: 1, column: 16, endColumn: 37 }],
		},
	],
});
