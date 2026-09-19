import { defineRule, type ESTree } from "@oxlint/plugins";

import { memberName } from "../property.ts";

/** Both play signatures reach the element: `({ canvasElement })` and `(context)`. */
const rootName = (root: ESTree.Node): string | undefined => {
	if (root.type === "Identifier") {
		return root.name;
	}
	if (root.type === "MemberExpression") {
		return memberName(root);
	}
	return undefined;
};

export const noWithinCanvasElement = defineRule({
	meta: {
		type: "problem",
		docs: {
			description:
				"Use the `canvas` a play function is handed rather than re-deriving it from `canvasElement`. `within(…)` stays right for scoping into a subtree — `within(canvas.getByRole('group', …))` — or into a portal rendered off the canvas, where `canvas` cannot reach.",
		},
		messages: {
			redundant:
				"The play function is handed `canvas` bound to this element, so this re-derives an argument it already has. Destructure `canvas` in the play signature and use it directly.",
		},
	},
	create(context) {
		return {
			CallExpression(node) {
				if (node.callee.type !== "Identifier" || node.callee.name !== "within") {
					return;
				}

				const [root] = node.arguments;
				if (!root || rootName(root) !== "canvasElement") {
					return;
				}

				context.report({ node: root, messageId: "redundant" });
			},
		};
	},
});
