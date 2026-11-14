import { defineRule, type ESTree } from "@oxlint/plugins";

/**
 * Whether the name is a button: `Button`, a component whose name ends in `Button`, or
 * `AlertDialogAction`, which renders one. Each forwards its props to `Button`, so each takes `pending`.
 */
function isButtonName(name: ESTree.JSXElementName): boolean {
	return (
		name.type === "JSXIdentifier" &&
		(name.name.endsWith("Button") || name.name === "AlertDialogAction")
	);
}

/**
 * Whether the element renders a button: it is one, or a trigger that renders one through
 * `render={<Button … />}` and passes its own children into it.
 */
function rendersButton(opening: ESTree.JSXOpeningElement): boolean {
	if (isButtonName(opening.name)) {
		return true;
	}
	return opening.attributes.some(
		(attribute) =>
			attribute.type === "JSXAttribute" &&
			attribute.name.type === "JSXIdentifier" &&
			attribute.name.name === "render" &&
			attribute.value?.type === "JSXExpressionContainer" &&
			attribute.value.expression.type === "JSXElement" &&
			isButtonName(attribute.value.expression.openingElement.name),
	);
}

/** The nodes between a JSX child and the element it renders in, in a conditional spinner's shapes. */
const PASS_THROUGH = new Set<ESTree.Node["type"]>([
	"JSXFragment",
	"JSXExpressionContainer",
	"LogicalExpression",
	"ConditionalExpression",
	"ParenthesizedExpression",
]);

/**
 * The element that renders the spinner as its content. The walk passes the shapes a conditional
 * spinner takes, `{pending && <Spinner />}`, `{pending ? <Spinner /> : <Icon />}` and a fragment, and
 * stops at anything else, so a spinner in a nested `<span>` or in a callback is not the button's.
 */
function contentOwner(node: ESTree.Node): ESTree.JSXElement | undefined {
	let current = node.parent;
	while (current && PASS_THROUGH.has(current.type)) {
		current = current.parent;
	}
	return current?.type === "JSXElement" ? current : undefined;
}

export const noSpinnerInButton = defineRule({
	meta: {
		type: "problem",
		docs: {
			description:
				"A button whose action is in flight takes `pending`, not a `<Spinner>` among its children. `pending` blocks a second press at once, keeps the focus, and shows the spinner only after a second (`webapp/DESIGN.md` § Loading and errors). A spinner that a call site renders itself flashes for a quick action.",
		},
		messages: {
			spinner:
				"Pass `pending={…}` to the button instead of rendering a `<Spinner>` in it. The button delays its own spinner and blocks repeat presses at once. Keep the label change (“Saving…”) in the children.",
		},
	},
	create(context) {
		return {
			JSXElement(node) {
				const { name } = node.openingElement;
				if (name.type !== "JSXIdentifier" || name.name !== "Spinner") {
					return;
				}
				const owner = contentOwner(node);
				if (owner && rendersButton(owner.openingElement)) {
					context.report({ node: node.openingElement, messageId: "spinner" });
				}
			},
		};
	},
});
