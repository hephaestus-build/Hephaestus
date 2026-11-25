import { defineRule, type ESTree } from "@oxlint/plugins";

export const noNetworkidle = defineRule({
	meta: {
		type: "problem",
		docs: {
			description:
				"A browser test waits for what proves the page is ready, never for `networkidle`. That state needs 500 ms without a request, which a poll or a held request can deny for the whole test, and then the timeout names no cause. Playwright discourages it.",
		},
		messages: {
			wait: "Wait for the element or response that proves the page is ready, or for `settled(page)` from `webapp/e2e/screenshots/settled.ts`, not for `networkidle`.",
		},
	},
	create(context) {
		const check = (node: ESTree.Node, text: unknown) => {
			// oxlint-disable-next-line hephaestus/no-networkidle -- the state this rule refuses.
			if (text === "networkidle") {
				context.report({ node, messageId: "wait" });
			}
		};
		return {
			Literal(node) {
				check(node, node.value);
			},
			// Only a template without interpolation is the whole string.
			TemplateLiteral(node) {
				if (node.expressions.length === 0) {
					check(node, node.quasis[0]?.value.cooked ?? node.quasis[0]?.value.raw);
				}
			},
		};
	},
});
