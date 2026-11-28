import { parseUiMessage, type MainMessage } from "./messages.ts";
import { setReviewRelaunch } from "./node-capabilities.ts";
import { summarizeSelection } from "./selection-summary.ts";

/** Beside the first selected node, in canvas coordinates. The panel does not follow later moves. */
function panelPosition(): { x: number; y: number } | undefined {
	const box = figma.currentPage.selection[0]?.absoluteBoundingBox;
	return box ? { x: box.x + box.width + 16, y: box.y } : undefined;
}

function send(message: MainMessage) {
	figma.ui.postMessage(message, { origin: "*" });
}

function sendSelection() {
	send({ type: "selection", summary: summarizeSelection(figma.currentPage.selection) });
}

/** The node types an instructor can give the relaunch button (PLUGIN-DESIGN § 1). */
const TAGGABLE: ReadonlySet<string> = new Set(["SECTION", "FRAME", "TABLE"]);

// Opening the panel, also from a relaunch button, reads only counts. It captures and sends nothing. With nothing
// selected, the panel opens where Figma puts it, as the proven probe did.
const position = panelPosition();
figma.showUI(__html__, {
	width: 320,
	height: 380,
	title: "Hephaestus",
	...(position === undefined ? {} : { position }),
});
figma.on("selectionchange", sendSelection);
sendSelection();

figma.ui.on("message", (value: unknown) => {
	const message = parseUiMessage(value);
	if (message === undefined) {
		return;
	}
	const { selection } = figma.currentPage;
	const node = selection.length === 1 ? selection[0] : undefined;
	send({
		type: "relaunch",
		result:
			node === undefined || !TAGGABLE.has(node.type)
				? "NOT_ONE_TAGGABLE_NODE"
				: setReviewRelaunch(node, message.enabled),
	});
});
