// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";

import { alignToTitle, COLUMN_PROPERTY, INSET_PROPERTY } from "~/content/align";
import { required } from "~/testing/required";

function rect(left: number): DOMRect {
	return DOMRect.fromRect({ x: left, y: 0, width: 100, height: 20 });
}

afterEach(() => {
	vi.restoreAllMocks();
});

describe("a list row's preview lines up with the row's title", () => {
	it("takes the title's own grid column in a grid row, so it follows the grid at every width", () => {
		document.body.innerHTML = `
			<li id="row" style="display: grid; grid-template-columns: 24px 24px 1fr auto">
				<input type="checkbox" />
				<span>icon</span>
				<div id="title-column" style="grid-column-start: 3">
					<h3><a id="title" href="#">Add admin API</a></h3>
				</div>
				<div>3</div>
				<hephaestus-report id="host"></hephaestus-report>
			</li>`;
		const host = required(document.querySelector<HTMLElement>("#host"), "host");
		alignToTitle(host, required(document.querySelector("#title"), "title"));
		expect(host.style.getPropertyValue(COLUMN_PROPERTY)).toBe("3");
		expect(host.style.getPropertyValue(INSET_PROPERTY)).toBe("");
	});

	it("moves its left edge to the title's where the row is not a grid", () => {
		document.body.innerHTML = `
			<div id="row"><a id="title" href="#">Add admin API</a><hephaestus-report id="host"></hephaestus-report></div>`;
		const host = required(document.querySelector<HTMLElement>("#host"), "host");
		const title = required(document.querySelector("#title"), "title");
		vi.spyOn(title, "getBoundingClientRect").mockReturnValue(rect(135));
		vi.spyOn(host, "getBoundingClientRect").mockReturnValue(rect(20));
		alignToTitle(host, title);
		expect(host.style.getPropertyValue(INSET_PROPERTY)).toBe("115px");
		expect(host.style.getPropertyValue(COLUMN_PROPERTY)).toBe("");
		// Measured again once aligned, it stays where it is rather than moving twice as far.
		vi.spyOn(host, "getBoundingClientRect").mockReturnValue(rect(135));
		alignToTitle(host, title);
		expect(host.style.getPropertyValue(INSET_PROPERTY)).toBe("115px");
	});
});
