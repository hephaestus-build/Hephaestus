/** The node fields the summary reads. Nothing else of a node is read, and no text leaves this module. */
export interface SummaryNode {
	readonly type: string;
	readonly visible?: boolean;
	readonly children?: readonly SummaryNode[];
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
	"INSTANCE",
	"RECTANGLE",
	"ELLIPSE",
	"POLYGON",
	"STAR",
	"LINE",
	"VECTOR",
]);

/** The January limit on elements in one version (CONTRACT § 3). */
export const ELEMENT_LIMIT = 5000;

export type SelectionSummary =
	| { kind: "EMPTY" }
	| { kind: "UNSUPPORTED"; notCaptured: number }
	| { kind: "TOO_LARGE"; elements: number }
	| { kind: "READY"; selected: number; elements: number; notCaptured: number; hidden: number };

/**
 * What the selection holds, as counts only. Hidden nodes and everything below them are counted and left out; a node
 * type the capture does not read is counted as not captured.
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
		if (READ.has(node.type)) {
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
		return { kind: "UNSUPPORTED", notCaptured };
	}
	if (elements > ELEMENT_LIMIT) {
		return { kind: "TOO_LARGE", elements };
	}
	return { kind: "READY", selected: selection.length, elements, notCaptured, hidden };
}
