import { defineRule, type ESTree } from "@oxlint/plugins";

/** The two attributes that give the graphic a name, as opposed to hiding it. */
const NAMING_ATTRIBUTES = new Set(["aria-label", "aria-labelledby"]);

/**
 * The value as written, when it is a string this rule can read. A bare attribute (`aria-hidden`) is
 * JSX shorthand for `{true}` and has no string; an expression is decided at runtime.
 */
function writtenValue(value: ESTree.JSXAttribute["value"]): string | boolean | undefined {
	if (value === null) {
		return true;
	}
	if (value.type === "Literal") {
		return typeof value.value === "string" ? value.value : undefined;
	}
	if (value.type === "JSXExpressionContainer" && value.expression.type === "Literal") {
		const { value: literal } = value.expression;
		if (typeof literal === "string" || typeof literal === "boolean") {
			return literal;
		}
	}
	return undefined;
}

/**
 * Whether the attribute, as written, settles the question. Anything this rule cannot evaluate counts:
 * the author wrote the attribute, so they answered — a component choosing between a name and
 * `aria-hidden` at runtime is doing the right thing, not omitting it.
 *
 * The two spellings that do not settle it are the two that look like they do. `aria-hidden="false"`
 * is the one value of that attribute which puts the graphic back into the accessibility tree, and a
 * blank `aria-label` computes to no name at all while reading, in the source, exactly like one.
 */
function answers(attribute: ESTree.JSXAttribute, name: string): boolean {
	const value = writtenValue(attribute.value);
	if (name === "aria-hidden") {
		return value !== "false" && value !== false;
	}
	return typeof value === "string" ? value.trim() !== "" : true;
}

/** `<title>` names the element it is a direct child of; inside a `<g>` it names the `<g>`. */
function hasOwnTitle(children: readonly ESTree.JSXChild[]): boolean {
	return children.some(
		(child) =>
			child.type === "JSXElement" &&
			child.openingElement.name.type === "JSXIdentifier" &&
			child.openingElement.name.name === "title",
	);
}

export const svgNeedsAccessibleName = defineRule({
	meta: {
		type: "problem",
		docs: {
			description:
				'An inline `<svg>` is either named or explicitly hidden. Left as neither it reaches the accessibility tree as an unlabelled graphic, which a screen reader announces as "image" and nothing more — worse than absent, because it interrupts. `role="img"` alone does not help: it supplies the role the graphic already had and no name. No shipped `jsx-a11y` rule asks this of `<svg>`.',
		},
		messages: {
			unnamed:
				'This `<svg>` has no accessible name, so a screen reader announces "image" and stops. Add `aria-hidden="true"` if it is decorative, or `aria-label`, `aria-labelledby` or a `<title>` child if it means something.',
		},
	},
	create(context) {
		return {
			JSXElement(node) {
				const opening = node.openingElement;
				// A capitalised or dotted name is a component, which answers this question in its own
				// body; only the intrinsic element renders an actual `<svg>` here.
				if (opening.name.type !== "JSXIdentifier" || opening.name.name !== "svg") {
					return;
				}

				for (const attribute of opening.attributes) {
					// A spread can carry any of the four and nothing here can see through it. Every icon
					// in this kit forwards its props, so reporting through a spread would be a false
					// positive on all of them — and the one it would catch, a caller who forgot, is
					// caught at the caller instead. Passing is the honest answer, not the lenient one.
					if (attribute.type === "JSXSpreadAttribute") {
						return;
					}
					if (attribute.name.type !== "JSXIdentifier") {
						continue;
					}
					const { name } = attribute.name;
					if ((name === "aria-hidden" || NAMING_ATTRIBUTES.has(name)) && answers(attribute, name)) {
						return;
					}
				}

				if (hasOwnTitle(node.children)) {
					return;
				}
				// The opening tag, not the whole element: the fix is an attribute, and an icon's path
				// data would otherwise fill the terminal.
				context.report({ node: opening, messageId: "unnamed" });
			},
		};
	},
});
