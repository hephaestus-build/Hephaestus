import { ruleTester } from "../rule-tester.ts";
import { typedStoryMeta } from "./typed-story-meta.ts";

const componentMeta = "const meta = { component: Button } satisfies Meta<typeof Button>;";

ruleTester.run("typed-story-meta", typedStoryMeta, {
	valid: [
		componentMeta,
		"const meta = { parameters: { layout: 'centered' } } satisfies Meta;",
		"const meta = { parameters: { docs: { description: { component: 'All icons.' } } } } satisfies Meta;",
		"const meta = { ...base } satisfies Meta;",
		"const story = { component: Button } satisfies StoryObj;",
		// The annotation spelling is fine where no component is named.
		"const meta: Meta = { parameters: { layout: 'centered' } };",
		`${componentMeta} type Story = StoryObj<typeof meta>;`,
		`${componentMeta} type Story = SB.StoryObj<typeof meta>;`,
		`${componentMeta} type Story = StoryObj;`,
		`${componentMeta} export const Primary: StoryObj<typeof meta> = {};`,
		// The meta goes by whatever name its declaration gives it.
		"export const buttonMeta = { component: Button } satisfies Meta<typeof Button>; type Story = StoryObj<typeof buttonMeta>;",
		// A gallery meta names no component, so there is no `typeof meta` for a story to subtract from.
		"const meta = { args: { size: 'sm' } } satisfies Meta<typeof BronzeIcon>; export const Bronze: StoryObj<typeof BronzeIcon> = {};",
		"type Story = StoryObj<typeof Button>;",
		// A story whose `render` takes no `args` passes a sibling its props where `tsc` checks them.
		`${componentMeta} export const Loading: StoryObj<typeof ThinkingMessage> = { render: () => <ThinkingMessage /> };`,
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
			// Annotated, so `typeof meta` is `Meta`: the stories lose the args it supplies.
			code: "const meta: Meta<typeof Button> = { component: Button };",
			errors: [{ messageId: "annotated", line: 1, column: 13, endColumn: 32 }],
		},
		{
			code: "const meta: Meta = { parameters: { layout: 'centered' }, component: Button };",
			errors: [{ messageId: "annotated" }],
		},
		{
			// The `satisfies` checks the object, but the annotation still widens `typeof meta`.
			code: "const meta: Meta<typeof Button> = { component: Button } satisfies Meta<typeof Button>;",
			errors: [{ messageId: "annotated", line: 1, column: 13, endColumn: 32 }],
		},
		{
			code: `${componentMeta}\ntype Story = StoryObj<typeof Button>;`,
			errors: [{ messageId: "storyOfComponent", line: 2, column: 23, endColumn: 36 }],
		},
		{
			code: `${componentMeta} type Story = StoryObj<ButtonProps>;`,
			errors: [{ messageId: "storyOfComponent" }],
		},
		{
			code: `${componentMeta} export const Primary: StoryObj<typeof Button> = { args: {} };`,
			errors: [{ messageId: "storyOfComponent" }],
		},
		{
			// `args` beside a `render` reach the sibling as all-optional props, so the `render` earns
			// no exemption.
			code: `${componentMeta} export const Loading: StoryObj<typeof ThinkingMessage> = { render: (args) => <ThinkingMessage {...args} />, args: {} };`,
			errors: [{ messageId: "storyOfComponent" }],
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
