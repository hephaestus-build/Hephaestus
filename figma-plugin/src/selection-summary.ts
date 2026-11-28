/** The node fields the summary reads. Nothing else of a node is read, and no text leaves this module. */
export interface SummaryNode {
	readonly type: string;
	readonly visible?: boolean;
	readonly children?: readonly SummaryNode[];
	/** A FigJam table has no `children`: its cells are counted from its rows and columns. */
	readonly numRows?: number;
	readonly numColumns?: number;
}

/** The node types that the FigJam and Design capture reads (PLUGIN-DESIGN § 3). */
const READ: ReadonlySet<string> = new Set([
	"STICKY",
	"SHAPE_WITH_TEXT",
	"TEXT",
	"SECTION",
	"TABLE",
	"TABLE_CELL",
	"CONNECTOR",
	"FRAME",
	"GROUP",
	"COMPONENT",
	"COMPONENT_SET",
	"INSTANCE",
	"BOOLEAN_OPERATION",
	"RECTANGLE",
	"ELLIPSE",
	"POLYGON",
	"STAR",
	"LINE",
]);
// Not read, so counted as not captured: images, media, embeds, stamps, widgets, and vector art or freehand drawing
// (`VECTOR`, `HIGHLIGHT`), which yield no element (PLUGIN-DESIGN § 3).

/** The January limit on elements in one version (CONTRACT § 3). */
export const ELEMENT_LIMIT = 5000;

export type SelectionSummary =
	| { kind: "EMPTY" }
	| { kind: "ALL_HIDDEN"; hidden: number }
	| { kind: "UNSUPPORTED"; notCaptured: number }
	| { kind: "TOO_LARGE"; elements: number }
	| { kind: "READY"; selected: number; elements: number; notCaptured: number; hidden: number };

/**
 * What the selection holds, as counts only. Hidden nodes and everything below them are counted and left out; a node
 * type the capture does not read is counted as not captured. A table counts itself and each cell.
 */
export function summarizeSelection(selection: readonly SummaryNode[]): SelectionSummary {
	if (selection.length === 0) {
		return { kind: "EMPTY" };
	}
	let elements = 0;
	let notCaptured = 0;
	let hidden = 0;
	const visit = (node: SummaryNode) => {
		if (node.visible === false) {
			hidden += 1;
			return;
		}
		if (node.type === "TABLE") {
			elements += 1 + (node.numRows ?? 0) * (node.numColumns ?? 0);
		} else if (READ.has(node.type)) {
			elements += 1;
		} else {
			notCaptured += 1;
		}
		for (const child of node.children ?? []) {
			visit(child);
		}
	};
	for (const node of selection) {
		visit(node);
	}
	if (elements === 0) {
		return notCaptured === 0
			? { kind: "ALL_HIDDEN", hidden }
			: { kind: "UNSUPPORTED", notCaptured };
	}
	if (elements > ELEMENT_LIMIT) {
		return { kind: "TOO_LARGE", elements };
	}
	return { kind: "READY", selected: selection.length, elements, notCaptured, hidden };
}
