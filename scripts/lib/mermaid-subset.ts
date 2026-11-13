/**
 * The Mermaid features that the docs may use: the subset that Mermaid 12 renders today and that the
 * Mermaid 13 rewrite keeps (https://mermaid.js.org/intro/v13-alpha.html). A page that uses anything
 * else would break on the upgrade, so `check-mermaid-diagrams.ts` refuses it now.
 */

/** Diagram types that Mermaid 13 does not port. They fail there with `UNSUPPORTED_DIAGRAM`. */
const UNPORTED_TYPE =
	/^(?<type>C4Context|C4Container|C4Component|C4Dynamic|C4Deployment|architecture-beta|architecture|block-beta|block|requirementDiagram)\b/u;

/** A label that holds an HTML element. Mermaid 13 draws labels as SVG text. */
const HTML_LABEL = /<\/?(?:br|b|i|u|em|strong|span|div|font|sub|sup|p|a|img)\b/iu;

/** Leading YAML front matter, which configures a diagram and is not part of its body. */
const FRONT_MATTER = /^---\n[\s\S]*?\n---\n/u;

export function subsetProblems(source: string): string[] {
	const problems: string[] = [];
	const body = source.replace(FRONT_MATTER, "");
	const declaration = body
		.split("\n")
		.map((line) => line.trim())
		.find((line) => line.length > 0 && !line.startsWith("%%"));
	const unported = declaration === undefined ? null : UNPORTED_TYPE.exec(declaration);
	if (unported?.groups?.type !== undefined) {
		problems.push(
			`\`${unported.groups.type}\` is not in Mermaid 13. Draw a flowchart, or a HyLiMo view for a structural map.`,
		);
	}
	if (/^\s*click\s/mu.test(body)) {
		problems.push("A click callback needs a plugin in Mermaid 13. Link from the text instead.");
	}
	if (HTML_LABEL.test(body)) {
		problems.push("Mermaid 13 has no HTML labels. Write the label as plain text.");
	}
	if (/^\s*style\s+\S/mu.test(body)) {
		problems.push("Mermaid 13 drops CSS on an element id. Use a `classDef` and `:::class`.");
	}
	if (/htmlLabels|securityLevel|themeCSS/u.test(source)) {
		problems.push(
			"`htmlLabels`, `securityLevel` and `themeCSS` are gone in Mermaid 13. Remove the setting.",
		);
	}
	return problems;
}
