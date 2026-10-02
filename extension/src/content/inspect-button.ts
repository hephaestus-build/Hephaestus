import markSvg from "@/brand/hephaestus-mark.svg?raw";

import { INSPECT_TAG } from "~/content/anchors";
import { type WorkPage, workPageLabel } from "~/shared/work-url";
import inspectCss from "~/content/inspect.css?inline";

/** The row's own work number, which the page already shows: how a control is matched to its row. */
export const WORK_NUMBER = "data-work-number";

/**
 * Events the row's own handlers must not see. A provider's list handles clicks and keys on the whole
 * row (GitHub's roving focus selects the row on Space; GitLab's overlay link opens the work), and an
 * event from inside a shadow root reaches them retargeted to the host, which they do not know is a
 * button.
 */
const ROW_EVENTS = [
	"click",
	"auxclick",
	"dblclick",
	"mousedown",
	"mouseup",
	"pointerdown",
	"pointerup",
	"keydown",
	"keyup",
	"keypress",
] as const;

/**
 * The inspect button beside a list row's title. It is the same on every row — the mark and a name
 * made of the row's own number, which the page already shows — and nothing is looked up until the
 * reader presses it. Pressing it is not an authorization: the page could open a preview itself. What
 * the preview may read is decided by the worker against the tab's own list, and the preview opens
 * closed, at the height every state has.
 */
export function createInspectButton(
	work: WorkPage,
	provider: "github" | "gitlab",
	onToggle: (work: WorkPage) => void,
): HTMLElement {
	const host = document.createElement(INSPECT_TAG);
	host.setAttribute(WORK_NUMBER, String(work.number));
	host.dataset.provider = provider;
	const shadow = host.attachShadow({ mode: "open" });
	const style = document.createElement("style");
	style.textContent = inspectCss;
	const button = document.createElement("button");
	button.type = "button";
	const name = `Hephaestus review of ${workPageLabel(work)}`;
	button.setAttribute("aria-label", name);
	button.title = name;
	button.setAttribute("aria-expanded", "false");
	button.innerHTML = `<span class="mark" aria-hidden="true">${markSvg}</span>`;
	button.addEventListener("click", (event) => {
		event.preventDefault();
		onToggle(work);
	});
	for (const type of ROW_EVENTS) {
		host.addEventListener(type, (event) => {
			event.stopPropagation();
		});
	}
	shadow.append(style, button);
	return host;
}

/** Marks which row's preview is open, so the button reads as a pressed disclosure. */
export function setInspectExpanded(host: Element, expanded: boolean): void {
	host.shadowRoot?.querySelector("button")?.setAttribute("aria-expanded", String(expanded));
}
