import { defineRule, type ESTree } from "@oxlint/plugins";
import { decodeHTMLStrict } from "entities";

import { wordAlerts } from "../../../../scripts/lib/ste-words.ts";

// Names of the JSX props and object keys whose string values are UI text. Object keys cover the
// vocabulary registries, option lists and form schemas.
const textNames = new Set([
	"alt",
	"aria-label",
	"aria-description",
	"title",
	"placeholder",
	"label",
	"description",
	"summary",
	"message",
	"error",
	"helperText",
	"emptyText",
	"loadingText",
	"tooltip",
	"children",
	"confirmText",
	"cancelText",
	"submitText",
	"buttonText",
	"note",
	"content",
	"detail",
	"hint",
	"heading",
	"caption",
	"subtitle",
	"success",
	"loading",
]);
const toastCalls = new Set(["error", "success", "info", "warning", "message", "loading"]);
const sentences = new Intl.Segmenter("en", { granularity: "sentence" });

/** Static text only. Unknown expressions are boundaries, not words that we invent. */
function literalText(node: ESTree.Node): string[] {
	if (node.type === "Literal") {
		return typeof node.value === "string" ? [node.value] : [];
	}
	if (node.type === "TemplateLiteral") {
		return node.quasis.map((part) => part.value.cooked ?? part.value.raw);
	}
	if (node.type === "ConditionalExpression") {
		return [...literalText(node.consequent), ...literalText(node.alternate)];
	}
	if (node.type === "LogicalExpression") {
		if (node.operator === "&&") {
			return literalText(node.right);
		}
		return [...literalText(node.left), ...literalText(node.right)];
	}
	if (node.type === "BinaryExpression" && node.operator === "+") {
		const left = literalText(node.left);
		const right = literalText(node.right);
		return left.length === 1 && right.length === 1
			? [left.join("") + right.join("")]
			: [...left, ...right];
	}
	return [];
}

function isToastCall(callee: ESTree.Node): boolean {
	if (callee.type === "Identifier") {
		return callee.name === "toast";
	}
	return (
		callee.type === "MemberExpression" &&
		callee.object.type === "Identifier" &&
		callee.object.name === "toast" &&
		callee.property.type === "Identifier" &&
		toastCalls.has(callee.property.name)
	);
}

function isCodeElement(node: ESTree.Node): boolean {
	return (
		node.type === "JSXElement" &&
		node.openingElement.name.type === "JSXIdentifier" &&
		(node.openingElement.name.name === "style" || node.openingElement.name.name === "script")
	);
}

function propertyName(key: ESTree.Node): string | undefined {
	if (key.type === "Identifier") {
		return key.name;
	}
	return key.type === "Literal" && typeof key.value === "string" ? key.value : undefined;
}

export const uiTextVoice = defineRule({
	meta: {
		type: "problem",
		docs: {
			description: "Write literal UI text in the user-facing voice.",
		},
		schema: [],
		messages: {
			word: 'Write "{{to}}" instead of "{{from}}". Keep the same meaning.',
			sentence: "Split this sentence. Write no more than 25 words per sentence.",
			semicolon: "Write two sentences instead of a semicolon.",
			apostrophe: "Write the apostrophe as ’ in UI text.",
		},
	},
	create(context) {
		function check(node: ESTree.Node, text: string): void {
			for (const alert of wordAlerts(text, "voice")) {
				context.report({ node, messageId: "word", data: alert });
			}
			if (text.includes(";")) {
				context.report({ node, messageId: "semicolon" });
			}
			if (/\p{L}'\p{L}/u.test(text)) {
				context.report({ node, messageId: "apostrophe" });
			}
			// A line break in JSX source ends a sentence for the segmenter, so join the lines first.
			for (const { segment } of sentences.segment(text.replaceAll(/\s+/gu, " "))) {
				if ([...segment.matchAll(/\b[\p{L}\p{N}]+(?:[-’'][\p{L}\p{N}]+)*\b/gu)].length > 25) {
					context.report({ node, messageId: "sentence" });
				}
			}
		}
		return {
			JSXText(node) {
				check(node, decodeHTMLStrict(node.value));
			},
			JSXAttribute(node) {
				if (
					node.name.type !== "JSXIdentifier" ||
					!textNames.has(node.name.name) ||
					node.value === null
				) {
					return;
				}
				const value =
					node.value.type === "JSXExpressionContainer" ? node.value.expression : node.value;
				for (const text of literalText(value)) {
					check(value, node.value.type === "Literal" ? decodeHTMLStrict(text) : text);
				}
			},
			CallExpression(node) {
				const [message] = node.arguments;
				if (message !== undefined && isToastCall(node.callee)) {
					for (const text of literalText(message)) {
						check(message, text);
					}
				}
			},
			Property(node) {
				const name = node.computed ? undefined : propertyName(node.key);
				if (name !== undefined && textNames.has(name) && node.parent.type === "ObjectExpression") {
					for (const text of literalText(node.value)) {
						check(node.value, text);
					}
				}
			},
			JSXExpressionContainer(node) {
				// Attributes have their own visitor; identifiers and calls need human review. A style
				// or script element holds code, not prose.
				if (node.parent.type === "JSXAttribute" || isCodeElement(node.parent)) {
					return;
				}
				for (const text of literalText(node.expression)) {
					check(node.expression, text);
				}
			},
		};
	},
});
