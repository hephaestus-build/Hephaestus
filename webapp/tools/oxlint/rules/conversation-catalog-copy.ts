import { defineRule, type ESTree } from "@oxlint/plugins";

import { isTextName, literalText } from "./ui-text-voice.ts";

// A sentence stored in an expression can reach a component through a variable or helper.
const prose = /^(?:\p{L}[\p{L}’'-]*(?:\s+\p{L}.*)?[.!?…]|\p{Lu}\p{Ll}+[’'-]*\s+\p{L}.*)$/u;

export const conversationCatalogCopy = defineRule({
	meta: {
		type: "problem",
		docs: { description: "Put conversation copy in the ICU message catalog." },
		messages: { catalog: "Put this text in the conversation message catalog." },
	},
	create(context) {
		const report = (node: ESTree.Node) => context.report({ node, messageId: "catalog" });
		return {
			JSXText(node) {
				if (node.value.trim().length > 0) {
					report(node);
				}
			},
			JSXAttribute(node) {
				if (
					node.name.type !== "JSXIdentifier" ||
					!isTextName(node.name.name) ||
					node.value === null
				) {
					return;
				}
				const value =
					node.value.type === "JSXExpressionContainer" ? node.value.expression : node.value;
				if (literalText(value).some((text) => text.trim().length > 0 && !prose.test(text.trim()))) {
					report(node);
				}
			},
			JSXExpressionContainer(node) {
				if (
					node.parent.type !== "JSXAttribute" &&
					literalText(node.expression).some(
						(text) => text.trim().length > 0 && !prose.test(text.trim()),
					)
				) {
					report(node);
				}
			},
			Literal(node) {
				if (typeof node.value === "string" && prose.test(node.value.trim())) {
					report(node);
				}
			},
			TemplateLiteral(node) {
				if (
					prose.test(
						node.quasis
							.map((part) => part.value.cooked ?? part.value.raw)
							.join("name")
							.trim(),
					)
				) {
					report(node);
				}
			},
		};
	},
});
