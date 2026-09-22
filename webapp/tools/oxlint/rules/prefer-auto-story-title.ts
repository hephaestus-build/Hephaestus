import { defineRule, type ESTree } from "@oxlint/plugins";

import { propertyName } from "../property.ts";

const STORY_SUFFIX = /\.stories\.[cm]?[jt]sx?$/u;

function objectExpression(expression: ESTree.Expression | null | undefined) {
	let current = expression;
	while (
		current?.type === "TSSatisfiesExpression" ||
		current?.type === "TSAsExpression" ||
		current?.type === "TSInstantiationExpression"
	) {
		current = current.expression;
	}
	return current?.type === "ObjectExpression" ? current : undefined;
}

export const preferAutoStoryTitle = defineRule({
	meta: {
		type: "suggestion",
		docs: {
			description:
				"Omit the Storybook meta title. Storybook derives one from the story file's path, so the sidebar mirrors the source tree and a title cannot drift from it.",
		},
		messages: {
			explicit:
				"Delete `title`: Storybook derives it from this file's path, so the sidebar mirrors the source tree. To move the story, move the component.",
		},
	},
	create(context) {
		if (!STORY_SUFFIX.test(context.filename)) {
			return {};
		}
		return {
			VariableDeclarator(node) {
				if (node.id.type !== "Identifier" || node.id.name !== "meta") {
					return;
				}
				const title = objectExpression(node.init)?.properties.find(
					(property) => property.type === "Property" && propertyName(property) === "title",
				);
				if (title !== undefined) {
					context.report({ node: title, messageId: "explicit" });
				}
			},
		};
	},
});
