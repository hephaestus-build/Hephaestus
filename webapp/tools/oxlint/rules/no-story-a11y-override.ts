import { defineRule } from "@oxlint/plugins";

import { propertyName } from "../property.ts";

/**
 * Every entry a story can reach the accessibility check through, by the object that holds it.
 * `@storybook/addon-a11y` decides whether to run from
 *
 *     !globals.ghostStories && a11yParameter?.disable !== true &&
 *     a11yParameter?.test !== "off" && a11yGlobals?.manual !== true
 *
 * so `parameters.a11y` reconfigures the check, `globals.a11y` defers it and `globals.ghostStories`
 * skips it outright without naming accessibility at all. Any one of them alone takes a component out
 * of the suite. `ghostStories` is listed under `globals` only, because that is the only place the
 * addon reads it.
 */
const A11Y_SWITCHES = new Map<string, ReadonlySet<string>>([
	["parameters", new Set(["a11y"])],
	["globals", new Set(["a11y", "ghostStories"])],
]);

export const noStoryA11yOverride = defineRule({
	meta: {
		type: "problem",
		docs: {
			description:
				'The accessibility check is configured once, in `.storybook/preview.tsx`. It runs there at `test: "error"` for every story, so anything a story adds can only lower that bar — and it lowers it while the suite still reports green, which is how a component stops being checked without anyone noticing. Where a rule is genuinely wrong about a pattern the whole kit uses, `preview.tsx` is where the exemption is visible and has to be argued for once.',
		},
		messages: {
			localOverride:
				'`.storybook/preview.tsx` already runs the accessibility check at `test: "error"`, so this entry can only lower that bar, silently. Fix the violation, or argue the exemption once in `preview.tsx`.',
			indirect:
				"Written this way, what this sets cannot be read here, so it may hold an entry that lowers the bar `.storybook/preview.tsx` sets and nothing would say so. Spell the entries out inline.",
		},
	},
	create(context) {
		return {
			Property(node) {
				// A `Property` is also each half of `const { parameters } = context`, which reads a story
				// rather than writing one.
				if (node.parent.type !== "ObjectExpression") {
					return;
				}
				const host = propertyName(node);
				const switches = host === undefined ? undefined : A11Y_SWITCHES.get(host);
				if (!switches) {
					return;
				}
				if (node.value.type !== "ObjectExpression") {
					context.report({ node: node.value, messageId: "indirect" });
					return;
				}
				for (const entry of node.value.properties) {
					if (entry.type === "SpreadElement") {
						context.report({ node: entry, messageId: "indirect" });
						continue;
					}
					const name = propertyName(entry);
					if (name !== undefined && switches.has(name)) {
						// Two such entries in one object are two edits to make, so each is reported where it
						// stands rather than only the last one, which is the one that would win.
						context.report({ node: entry, messageId: "localOverride" });
					}
				}
			},
		};
	},
});
