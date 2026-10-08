import DOMPurify from "dompurify";

/**
 * `PracticeGuidanceRules.ELEMENTS` and `ATTRIBUTES`, the server's allowlist and the authority. The
 * browser sanitizes again with the same lists, so a stored picture never draws more than the server
 * accepted. Change both together.
 */
export const ALLOWED_TAGS = [
	"svg",
	"g",
	"path",
	"rect",
	"circle",
	"ellipse",
	"line",
	"polyline",
	"polygon",
	"text",
	"tspan",
	"title",
	"desc",
];
export const ALLOWED_ATTR = [
	"viewBox",
	"width",
	"height",
	"preserveAspectRatio",
	"version",
	"x",
	"y",
	"x1",
	"y1",
	"x2",
	"y2",
	"cx",
	"cy",
	"r",
	"rx",
	"ry",
	"d",
	"points",
	"dx",
	"dy",
	"transform",
	"class",
	"fill",
	"fill-rule",
	"fill-opacity",
	"clip-rule",
	"stroke",
	"stroke-width",
	"stroke-linecap",
	"stroke-linejoin",
	"stroke-miterlimit",
	"stroke-dasharray",
	"stroke-dashoffset",
	"stroke-opacity",
	"opacity",
	"font-size",
	"font-weight",
	"font-style",
	"text-anchor",
	"dominant-baseline",
	"letter-spacing",
	"vector-effect",
	"role",
	"aria-hidden",
	"focusable",
	"xml:space",
];

/** The characters besides letters, digits and spaces that `PracticeGuidanceRules.PLAIN_VALUE` allows. */
export const VALUE_PUNCTUATION = "#.,%+()/-";

/**
 * The server's attribute value rule, with Java's ASCII `\w` and `\s`: a browser reads presentation
 * attributes as CSS, where an escape could spell `url(`.
 */
const PLAIN_VALUE = new RegExp(String.raw`^[\w \t\n\v\f\r${VALUE_PUNCTUATION}]*$`, "u");

// An instance of its own: a hook on the shared default would change every other caller in the bundle.
const purify = DOMPurify();
purify.addHook("uponSanitizeAttribute", (_node, data) => {
	if (!PLAIN_VALUE.test(data.attrValue) || data.attrValue.toLowerCase().includes("url(")) {
		data.keepAttr = false;
	}
});

/** The picture as markup that is safe to draw inline: only what the server's allowlist names. */
export function sanitizePracticeSvg(svg: string): string {
	return purify.sanitize(svg, {
		ALLOWED_TAGS,
		ALLOWED_ATTR,
		// DOMPurify allows every `aria-*` and `data-*` attribute by default; the server does not.
		ALLOW_ARIA_ATTR: false,
		ALLOW_DATA_ATTR: false,
	});
}

/** Whether sanitized markup colors any shape with a `pv-*` class, and so follows the theme. */
export function usesThemeClasses(markup: string): boolean {
	return /\sclass="(?:[^"]*\s)?pv-/u.test(markup);
}

/** How a Markdown guide names one of its own figures; the server accepts no other image source. */
const FIGURE_SOURCE = /^figures\/(?<name>[a-z0-9]+(?:-[a-z0-9]+)*)\.svg$/u;

/**
 * The SVG of the figure an image source names, or `undefined` for any other source: a remote
 * image, a data URL or a figure the guide does not carry.
 */
export function guideFigure(figures: Record<string, string>, source: string): string | undefined {
	const name = FIGURE_SOURCE.exec(source)?.groups?.name;
	return name !== undefined && Object.hasOwn(figures, name) ? figures[name] : undefined;
}
