import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { defineRule, type ESTree } from "@oxlint/plugins";
import { decodeHTMLStrict } from "entities";

import { asStringArray, isRecord, parseJson } from "../../../../scripts/lib/json.ts";
import {
	approvedWords,
	steRoot,
	withoutTechnicalNames,
	wordAlerts,
} from "../../../../scripts/lib/ste-words.ts";

const enforced = new Set(
	asStringArray(
		parseJson(readFileSync(new URL(".vale/enforced-paths.json", steRoot), "utf8")),
		"STE paths",
	),
);
const root = fileURLToPath(steRoot);
const textProps = new Set([
	"alt",
	"aria-label",
	"aria-description",
	"title",
	"placeholder",
	"label",
	"description",
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
]);
// Object keys whose string values are UI text: the vocabulary registries, option lists and form schemas.
const textKeys = new Set([
	"label",
	"description",
	"title",
	"message",
	"summary",
	"placeholder",
	"tooltip",
	"helperText",
	"emptyText",
	"note",
	"content",
	"detail",
	"hint",
	"heading",
	"caption",
	"subtitle",
]);
const toastCalls = new Set(["error", "success", "info", "warning", "message", "loading"]);
const dictionary = new Set(approvedWords);
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

export const steUiText = defineRule({
	meta: {
		type: "problem",
		docs: {
			description: "Use the STE writing standard for literal UI text on the enforced paths.",
		},
		schema: [
			{
				type: "object",
				properties: { allPaths: { type: "boolean" }, vocabulary: { type: "boolean" } },
				additionalProperties: false,
			},
		],
		messages: {
			word: 'Write "{{to}}" instead of "{{from}}". Keep the same meaning.',
			sentence: "Split this sentence. Write no more than 25 words per sentence.",
			semicolon: "Write two sentences instead of a semicolon.",
			vocabulary:
				'Write an approved word instead of "{{word}}", or use a technical name or verb from the product vocabulary.',
		},
	},
	create(context) {
		const options = context.options[0];
		const allPaths = isRecord(options) && options.allPaths === true;
		const vocabulary = isRecord(options) && options.vocabulary === true;
		const filename = path.relative(root, context.filename).split(path.sep).join("/");
		if (!allPaths && !enforced.has(filename)) {
			return {};
		}
		function check(node: ESTree.Node, text: string): void {
			if (vocabulary) {
				for (const match of withoutTechnicalNames(text).matchAll(/\b[a-z]+\b/giu)) {
					if (!dictionary.has(match[0].toLowerCase())) {
						context.report({ node, messageId: "vocabulary", data: { word: match[0] } });
					}
				}
				return;
			}
			for (const alert of wordAlerts(text)) {
				context.report({ node, messageId: "word", data: alert });
			}
			if (text.includes(";")) {
				context.report({ node, messageId: "semicolon" });
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
					!textProps.has(node.name.name) ||
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
				// The `description` option of a toast is an object property, which its own visitor checks.
				const [message] = node.arguments;
				if (message !== undefined && isToastCall(node.callee)) {
					for (const text of literalText(message)) {
						check(message, text);
					}
				}
			},
			Property(node) {
				const name = propertyName(node.key);
				if (name !== undefined && textKeys.has(name) && node.parent.type === "ObjectExpression") {
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
