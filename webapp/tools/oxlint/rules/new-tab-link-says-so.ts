import { defineRule, type ESTree } from "@oxlint/plugins";

/** The component that draws the outbound icon and speaks "(opens in a new tab)". */
const MARK = "OutboundMark";

/** Whether `"_blank"` is written anywhere in the value, such as in either branch of a condition. */
function mentionsBlank(node: unknown): boolean {
	if (typeof node !== "object" || node === null) {
		return false;
	}
	if (Array.isArray(node)) {
		return node.some(mentionsBlank);
	}
	const candidate = node as { type?: unknown; value?: unknown };
	if (candidate.type === "Literal" && candidate.value === "_blank") {
		return true;
	}
	if (
		candidate.type === "TemplateElement" &&
		typeof candidate.value === "object" &&
		candidate.value !== null
	) {
		const raw: unknown = Reflect.get(candidate.value, "raw");
		return typeof raw === "string" && raw.includes("_blank");
	}
	return Object.entries(node).some(([key, value]) => key !== "parent" && mentionsBlank(value));
}

function isBlankTarget(attribute: ESTree.JSXAttribute | ESTree.JSXSpreadAttribute): boolean {
	return (
		attribute.type === "JSXAttribute" &&
		attribute.name.type === "JSXIdentifier" &&
		attribute.name.name === "target" &&
		mentionsBlank(attribute.value)
	);
}

/** The words `OutboundMark` speaks. An `aria-label` replaces the link's content, so it must say them itself. */
const SPOKEN = "opens in a new tab";

/**
 * Whether the link's own `aria-label` hides the mark's words: a written label that lacks them. A label
 * this rule cannot read, such as a variable, counts as written by someone who knows.
 */
function labelHidesWarning(opening: ESTree.JSXOpeningElement): boolean {
	const label = opening.attributes.find(
		(attribute): attribute is ESTree.JSXAttribute =>
			attribute.type === "JSXAttribute" &&
			attribute.name.type === "JSXIdentifier" &&
			attribute.name.name === "aria-label",
	);
	if (label === undefined) {
		return false;
	}
	const { value } = label;
	if (value?.type === "Literal" && typeof value.value === "string") {
		return !value.value.includes(SPOKEN);
	}
	if (value?.type === "JSXExpressionContainer") {
		const { expression } = value;
		if (expression.type === "Literal" && typeof expression.value === "string") {
			return !expression.value.includes(SPOKEN);
		}
		if (expression.type === "TemplateLiteral") {
			return !expression.quasis.some((quasi) =>
				(quasi.value.cooked ?? quasi.value.raw).includes(SPOKEN),
			);
		}
	}
	return false;
}

/** Whether the mark is written anywhere below `node`, including inside a condition or a fragment. */
function containsMark(node: unknown): boolean {
	if (typeof node !== "object" || node === null) {
		return false;
	}
	if (Array.isArray(node)) {
		return node.some(containsMark);
	}
	const candidate = node as { type?: unknown; openingElement?: ESTree.JSXOpeningElement };
	if (
		candidate.type === "JSXElement" &&
		candidate.openingElement?.name.type === "JSXIdentifier" &&
		candidate.openingElement.name.name === MARK
	) {
		return true;
	}
	return Object.entries(node).some(([key, value]) => key !== "parent" && containsMark(value));
}

/** The element that passes `element` to its `render=` slot, if any. */
function slotOwner(element: ESTree.JSXElement): ESTree.JSXElement | undefined {
	// `render={href ? <a target="_blank" /> : undefined}` puts a condition between the slot and the link.
	let container = element.parent;
	while (container.type === "ConditionalExpression" || container.type === "LogicalExpression") {
		container = container.parent;
	}
	const attribute = container.type === "JSXExpressionContainer" ? container.parent : undefined;
	if (
		attribute?.type !== "JSXAttribute" ||
		attribute.name.type !== "JSXIdentifier" ||
		attribute.name.name !== "render"
	) {
		return undefined;
	}
	const owner = attribute.parent.parent;
	return owner?.type === "JSXElement" ? owner : undefined;
}

/** Whether the element writes any content of its own between its tags. */
function hasContent(element: ESTree.JSXElement): boolean {
	return element.children.some((child) => child.type !== "JSXText" || child.value.trim() !== "");
}

/**
 * The element whose children the link renders. A link given to a `render=` slot, such as
 * `<Button render={<a target="_blank" />}>Docs</Button>`, renders the slot owner's children, and a
 * slot owner that is itself in a slot renders its own owner's children.
 */
function renderedChildrenOwner(element: ESTree.JSXElement): ESTree.JSXElement {
	let owner = element;
	let next = hasContent(owner) ? undefined : slotOwner(owner);
	while (next !== undefined) {
		owner = next;
		next = hasContent(owner) ? undefined : slotOwner(owner);
	}
	return owner;
}

export const newTabLinkSaysSo = defineRule({
	meta: {
		type: "problem",
		docs: {
			description:
				'A link that opens a new tab says so twice: the outbound icon for the eye and "(opens in a new tab)" for a screen reader (`webapp/DESIGN.md` § Docs links, WCAG technique G201). `OutboundMark` draws both. `InlineLink external` and `DocLink` add it for you. The rule cannot see a `target` passed in a spread, an `aria-label` held in a variable, an `aria-labelledby`, or a label on a `render=` slot\'s owner.',
		},
		messages: {
			silent:
				"This link opens a new tab without saying so. Use `<InlineLink external>` or `<DocLink>`, or put `<OutboundMark />` inside the link.",
			labelHides:
				'This link\'s `aria-label` replaces its content, so a screen reader never hears "(opens in a new tab)". Add those words to the label.',
		},
	},
	create(context) {
		return {
			JSXElement(node) {
				if (!node.openingElement.attributes.some(isBlankTarget)) {
					return;
				}
				if (!containsMark(renderedChildrenOwner(node).children)) {
					context.report({ node: node.openingElement, messageId: "silent" });
				} else if (labelHidesWarning(node.openingElement)) {
					context.report({ node: node.openingElement, messageId: "labelHides" });
				}
			},
		};
	},
});
