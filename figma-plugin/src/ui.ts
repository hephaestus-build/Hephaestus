import type { MainMessage } from "./messages.ts";
import type { SelectionSummary } from "./selection-summary.ts";

function count(value: number, one: string, many: string): string {
	return value === 1 ? `1 ${one}` : `${value.toLocaleString("en")} ${many}`;
}

/** The selection step's words (PLUGIN-DESIGN § 2, screen 1). Each says what happens next. */
function selectionText(summary: SelectionSummary): string {
	switch (summary.kind) {
		case "EMPTY": {
			return "Nothing is selected. Select the work you want feedback on.";
		}
		case "ALL_HIDDEN": {
			return "Everything selected is hidden, and hidden layers are never captured. Select visible work.";
		}
		case "UNSUPPORTED": {
			return `Hephaestus cannot read this selection yet: ${count(summary.notCaptured, "item", "items")} of a kind it does not capture.`;
		}
		case "TOO_LARGE": {
			return `This selection is too large: ${count(summary.elements, "element", "elements")}. Select at most 5,000.`;
		}
		case "READY": {
			const parts = [`${count(summary.elements, "element", "elements")} selected.`];
			if (summary.hidden > 0) {
				parts.push(`${count(summary.hidden, "hidden layer is", "hidden layers are")} left out.`);
			}
			if (summary.notCaptured > 0) {
				parts.push(`${count(summary.notCaptured, "item is", "items are")} not captured.`);
			}
			return parts.join(" ");
		}
	}
}

const RELAUNCH_TEXT: Record<Extract<MainMessage, { type: "relaunch" }>["result"], string> = {
	WRITTEN: "Done.",
	UNSUPPORTED:
		"This kind of node cannot hold a button. Members open Hephaestus from Actions instead.",
	REFUSED: "Figma did not allow the change. Members open Hephaestus from Actions instead.",
	NOT_ONE_TAGGABLE_NODE: "Select exactly one section, frame or table first.",
};

function byId<T extends HTMLElement>(id: string, kind: new () => T): T {
	const found = document.getElementById(id);
	if (!(found instanceof kind)) {
		throw new TypeError(`The panel has no ${kind.name} #${id}.`);
	}
	return found;
}

const selection = byId("selection", HTMLParagraphElement);
const relaunchStatus = byId("relaunch-status", HTMLParagraphElement);

for (const [id, enabled] of [
	["relaunch-add", true],
	["relaunch-remove", false],
] as const) {
	byId(id, HTMLButtonElement).addEventListener("click", () => {
		parent.postMessage({ pluginMessage: { type: "relaunch", enabled } }, "*");
	});
}

globalThis.addEventListener("message", (event: MessageEvent<{ pluginMessage?: MainMessage }>) => {
	const message = event.data.pluginMessage;
	if (message?.type === "selection") {
		selection.textContent = selectionText(message.summary);
		// A result about the previous selection would read as one about this one.
		relaunchStatus.textContent = "";
	} else if (message?.type === "relaunch") {
		relaunchStatus.textContent = RELAUNCH_TEXT[message.result];
	}
});
