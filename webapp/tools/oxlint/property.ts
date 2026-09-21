import type { ESTree } from "@oxlint/plugins";

/**
 * What the `Property` visitor delivers. An object literal's property is the one these rules care
 * about; the two destructuring shapes — `const { a } = x`, `({ a } = x)` — share its `type`.
 */
export type VisitedProperty =
	| ESTree.ObjectProperty
	| ESTree.BindingProperty
	| ESTree.AssignmentTargetProperty;

/**
 * The name written between the brackets, when the brackets hold a name at all. A key or index
 * computed from a literal — `{ ["play"]: … }`, `x["play"]` — states that literal and nothing else,
 * so it reads exactly as the dotted spelling does; one computed from an expression states whatever
 * that evaluates to at runtime, which no rule here can know.
 */
function computedName(key: ESTree.Node): string | undefined {
	if (key.type === "Literal" && typeof key.value === "string") {
		return key.value;
	}
	if (key.type === "TemplateLiteral" && key.expressions.length === 0) {
		return key.quasis[0]?.value.cooked ?? undefined;
	}
	return undefined;
}

/**
 * The name a property is written under, or `undefined` when it has none a rule can read — a numeric
 * key is not the identifier any of these rules are looking for either.
 */
export function propertyName(property: VisitedProperty): string | undefined {
	if (property.computed) {
		return computedName(property.key);
	}
	if (property.key.type === "Identifier") {
		return property.key.name;
	}
	if (property.key.type === "Literal" && typeof property.key.value === "string") {
		return property.key.value;
	}
	return undefined;
}

/** The name a member is read under: the same question `propertyName` asks, of `a.b` and `a["b"]`. */
export function memberName(member: ESTree.MemberExpression): string | undefined {
	return member.computed ? computedName(member.property) : member.property.name;
}
