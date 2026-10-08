import { readFileSync } from "node:fs";

import { describe, expect, it } from "vitest";

import {
	ALLOWED_ATTR,
	ALLOWED_TAGS,
	VALUE_PUNCTUATION,
	guideFigure,
	sanitizePracticeSvg,
	usesThemeClasses,
} from "./practice-svg";

const guidanceRules = readFileSync(
	"../server/application/src/main/java/de/tum/cit/aet/hephaestus/practices/PracticeGuidanceRules.java",
	"utf8",
);

/** The quoted names in one `List.of(…)` or `Set.of(…)` constant of the server's rules. */
function serverNames(constant: string): string[] {
	const names = new RegExp(String.raw`${constant} = (?:List|Set)\.of\(([^)]*)\)`, "u").exec(
		guidanceRules,
	)?.[1];
	return [...(names ?? "").matchAll(/"(?<name>[^"]+)"/gu)].map((match) => match.groups?.name ?? "");
}

const HOSTILE = `<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 100 50" onload="window.pwned = 1">
  <script>window.pwned = 2</script>
  <style>body { display: none }</style>
  <foreignObject width="100" height="50"><div>Phish</div></foreignObject>
  <a href="javascript:window.pwned = 3"><text x="0" y="10">Link</text></a>
  <use xlink:href="https://example.com/sprite.svg#shape" />
  <image href="data:image/svg+xml,<svg/>" width="1" height="1" />
  <filter><feImage href="https://example.com/track.png" /></filter>
  <rect id="shape" class="pv-fill-accent" style="fill: red" x="0" y="0" width="10" height="10" onclick="window.pwned = 4" aria-label="Click me" data-track="1" />
  <circle cx="5" cy="5" r="5" fill="url(https://example.com/paint.svg#p)" stroke="u\\72l(#page-gradient)" />
  <ellipse cx="5" cy="5" rx="1" ry="1" fill="red;opacity:0" class="a:b" opacity="'1'" />
  <animate attributeName="href" to="javascript:alert(1)" />
  <set attributeName="onclick" to="window.pwned = 5" />
</svg>`;

/** Every element and attribute in sanitized markup, in document order. */
function parts(markup: string) {
	const template = document.createElement("template");
	template.innerHTML = markup;
	return [...template.content.querySelectorAll("*")].map((element) => [
		element.localName,
		...element.getAttributeNames(),
	]);
}

describe("sanitizePracticeSvg", () => {
	it("allows exactly what the server allows", () => {
		expect(ALLOWED_TAGS).toStrictEqual(serverNames("ELEMENTS"));
		// The server allows `xml:space` by its namespace, outside the list.
		expect(ALLOWED_ATTR).toStrictEqual([...serverNames("ATTRIBUTES"), "xml:space"]);
	});

	it("allows the same attribute value characters as the server", () => {
		const server = /PLAIN_VALUE = Pattern\.compile\("\[\\\\w\\\\s(?<rest>[^\]]*)\]/u.exec(
			guidanceRules,
		);
		expect(VALUE_PUNCTUATION).toBe(server?.groups?.rest);
	});

	it("keeps only the shapes, words and attribute values the server's allowlist names", () => {
		expect(parts(sanitizePracticeSvg(HOSTILE))).toStrictEqual([
			["svg", "viewBox"],
			["text", "x", "y"],
			["rect", "class", "x", "y", "width", "height"],
			["circle", "cx", "cy", "r"],
			["ellipse", "cx", "cy", "rx", "ry"],
		]);
		expect(sanitizePracticeSvg(HOSTILE)).not.toContain("Phish");
	});

	it("draws no markup that a comment hides from an XML parser", () => {
		const sanitized = sanitizePracticeSvg(
			'<svg viewBox="0 0 1 1"><!--><img src=x onerror="window.pwned = 6">--></svg>',
		);

		expect(parts(sanitized)).toStrictEqual([["svg", "viewBox"]]);
	});

	it("keeps a themed picture as drawn", () => {
		const themed = `<svg viewBox="0 0 100 50"><path class="pv-stroke-accent" d="M44 10h12" fill="none" stroke-width="2"/><text class="pv-fill-ink" font-weight="600" aria-hidden="true" xml:space="preserve">Done</text></svg>`;

		expect(sanitizePracticeSvg(themed)).toBe(themed.replace("/>", "></path>"));
	});
});

describe("usesThemeClasses", () => {
	it("reads a pv-* class anywhere in a class list", () => {
		expect(usesThemeClasses('<svg><rect class="pv-fill-ink"></rect></svg>')).toBe(true);
		expect(usesThemeClasses('<svg><rect class="outline pv-stroke-line"></rect></svg>')).toBe(true);
	});

	it("ignores a class that only contains pv- and markup with no class", () => {
		expect(usesThemeClasses('<svg><rect class="apv-box"></rect></svg>')).toBe(false);
		expect(usesThemeClasses('<svg><rect fill="#0f172a"></rect></svg>')).toBe(false);
	});
});

describe("guideFigure", () => {
	const figures = { "split-order": "<svg/>" };

	it("finds a figure the guide carries by its own path", () => {
		expect(guideFigure(figures, "figures/split-order.svg")).toBe("<svg/>");
	});

	it.each([
		"https://example.com/figures/split-order.svg",
		"figures/split-order.png",
		"figures/constructor.svg",
	])("draws nothing for %s", (source) => {
		expect(guideFigure(figures, source)).toBeUndefined();
	});
});
