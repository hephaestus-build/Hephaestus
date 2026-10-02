/** The host's custom properties `report.css` places a list row's preview by. */
export const COLUMN_PROPERTY = "--hephaestus-column";
export const INSET_PROPERTY = "--hephaestus-inset";

/**
 * Lines a list row's preview up with the row's title, whatever the provider's row is built from: in a
 * grid row it takes the title's own grid column, so it follows the grid at every width; anywhere else
 * its left edge moves to the title's. It reads only the layout of the provider's public elements, and
 * writes only two custom properties on the host, which its stylesheet reads — the page's own rules
 * cannot be outranked from here, and none are needed.
 */
export function alignToTitle(host: HTMLElement, title: Element): void {
	const parent = host.parentElement;
	if (parent === null) {
		return;
	}
	let item: Element = title;
	while (item.parentElement !== null && item.parentElement !== parent) {
		item = item.parentElement;
	}
	const grid = getComputedStyle(parent).display.endsWith("grid") && item.parentElement === parent;
	const start = grid ? getComputedStyle(item).gridColumnStart : "auto";
	if (start !== "auto" && start !== "") {
		host.style.setProperty(COLUMN_PROPERTY, start);
		host.style.removeProperty(INSET_PROPERTY);
		return;
	}
	host.style.removeProperty(COLUMN_PROPERTY);
	const applied = Number.parseFloat(host.style.getPropertyValue(INSET_PROPERTY)) || 0;
	const inset = title.getBoundingClientRect().left - (host.getBoundingClientRect().left - applied);
	host.style.setProperty(INSET_PROPERTY, `${Math.max(0, Math.round(inset))}px`);
}
