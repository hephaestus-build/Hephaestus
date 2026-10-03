import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { defineRule, type ESTree } from "@oxlint/plugins";

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
			for (const { segment } of sentences.segment(text)) {
				if ([...segment.matchAll(/\b[\p{L}\p{N}]+(?:[-’'][\p{L}\p{N}]+)*\b/gu)].length > 25) {
					context.report({ node, messageId: "sentence" });
				}
			}
		}
		return {
			JSXText(node) {
				check(node, node.value);
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
					check(value, text);
				}
			},
			JSXExpressionContainer(node) {
				// Attributes have their own visitor; identifiers and calls need human review.
				if (node.parent.type !== "JSXAttribute") {
					for (const text of literalText(node.expression)) {
						check(node.expression, text);
					}
				}
			},
		};
	},
});
