import { defineRule, type ESTree } from "@oxlint/plugins";

/**
 * `outline-none` as a whole class, bare or behind variants such as `focus-visible:`, with or without
 * the important marker (`!outline-none`, `outline-none!`).
 */
const OUTLINE_NONE = /(?:^|[\s:])!?outline-none!?(?=\s|$)/u;

/** `outline-hidden` with no variant in front of it, with or without the important marker. */
const BARE_OUTLINE_HIDDEN = /(?:^|\s)!?outline-hidden!?(?=\s|$)/u;

/**
 * Forced-colors modes such as Windows High Contrast drop `box-shadow`, so a focus ring drawn with
 * `ring-*` disappears there, and the outline is the only focus indicator left (WCAG 2.2 SC 2.4.7).
 * `outline-none` removes that outline. Tailwind's `outline-hidden` keeps it in forced colors, but
 * without a variant it keeps it at all times, so every such control looks focused. Hide the outline
 * only while focused: `focus-visible:outline-hidden`.
 * `src/components/ui/` keeps the registry's classes and is out of scope.
 *
 * The rule reads every string, not only class lists, because classes reach the DOM through `cn()`,
 * `cva()` and constants. A sentence that names one of these classes is reported too; none exists in
 * the app, and a class list is the only place either word belongs.
 */
export const focusOutlineInForcedColors = defineRule({
	meta: {
		type: "problem",
		docs: {
			description:
				"Hide a focus outline with `focus-visible:outline-hidden`, so a control that shows focus with a ring still shows it in forced-colors mode, and only while it has focus.",
		},
		messages: {
			outlineNone:
				"`outline-none` removes the outline that forced-colors mode shows in place of the ring. Write `focus-visible:outline-hidden`.",
			bareOutlineHidden:
				"Without a variant, `outline-hidden` draws an outline in forced-colors mode even when the control has no focus. Write `focus-visible:outline-hidden`.",
		},
	},
	create(context) {
		const check = (node: ESTree.Node, text: string) => {
			if (OUTLINE_NONE.test(text)) {
				context.report({ node, messageId: "outlineNone" });
			} else if (BARE_OUTLINE_HIDDEN.test(text)) {
				context.report({ node, messageId: "bareOutlineHidden" });
			}
		};
		return {
			Literal(node) {
				if (typeof node.value === "string") {
					check(node, node.value);
				}
			},
			TemplateElement(node) {
				check(node, node.value.cooked ?? node.value.raw);
			},
		};
	},
});
