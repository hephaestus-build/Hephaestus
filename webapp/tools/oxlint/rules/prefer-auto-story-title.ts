import { relative } from "node:path";

import { defineRule, type ESTree } from "@oxlint/plugins";

import { propertyName } from "../property.ts";

const STORY_SUFFIX = /\.stories\.[cm]?[jt]sx?$/u;
const PATH_SEPARATOR = /[\\/]/u;

/** Storybook ignores case and punctuation when it turns a path or title into an id. */
const normalizedSegments = (segments: string[]) =>
	segments.map((segment) => segment.replaceAll(/[^a-zA-Z0-9]/gu, "").toLowerCase());

/** The title Storybook derives from this file when `meta.title` is absent. */
function automaticTitle(filename: string, cwd: string): string[] | undefined {
	const segments = relative(cwd, filename).split(PATH_SEPARATOR);
	const src = segments.findIndex(
		(segment, index) => segment === "src" && (index === 0 || segments[index - 1] === "webapp"),
	);
	if (src === -1) {
		return undefined;
	}
	const owned = segments.slice(src + 1);
	const file = owned.at(-1);
	if (file === undefined || !STORY_SUFFIX.test(file)) {
		return undefined;
	}
	owned[owned.length - 1] = file.replace(STORY_SUFFIX, "");
	return owned;
}

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
				"Omit a Storybook meta title when it only restates the title Storybook derives from the story file. Explicit titles are reserved for a reader-facing hierarchy the source path cannot express.",
		},
		messages: {
			redundant:
				"This title restates `{{automatic}}`, which Storybook already derives from the file path. Delete `title`; keep an explicit title only when it deliberately relocates the component in the reader-facing tree.",
		},
	},
	create(context) {
		const automatic = automaticTitle(context.filename, context.cwd);
		if (automatic === undefined) {
			return {};
		}
		return {
			VariableDeclarator(node) {
				if (node.id.type !== "Identifier" || node.id.name !== "meta") {
					return;
				}
				const meta = objectExpression(node.init);
				if (meta === undefined) {
					return;
				}
				const title = meta.properties.find(
					(property) => property.type === "Property" && propertyName(property) === "title",
				);
				if (
					title?.type !== "Property" ||
					title.value.type !== "Literal" ||
					typeof title.value.value !== "string"
				) {
					return;
				}
				const declared = title.value.value.split("/");
				if (normalizedSegments(declared).join("/") !== normalizedSegments(automatic).join("/")) {
					return;
				}
				context.report({
					node: title,
					messageId: "redundant",
					data: { automatic: automatic.join("/") },
				});
			},
		};
	},
});
