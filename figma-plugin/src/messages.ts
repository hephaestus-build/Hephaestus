import type { RelaunchWrite } from "./node-capabilities.ts";
import type { SelectionSummary } from "./selection-summary.ts";

/** What the plugin's main thread tells the panel. */
export type MainMessage =
	| { type: "selection"; summary: SelectionSummary }
	| { type: "relaunch"; result: RelaunchWrite | "NO_SINGLE_NODE" };

/** What the panel asks the main thread. Only an explicit press in the panel sends one. */
export interface UiMessage {
	type: "relaunch";
	enabled: boolean;
}

/**
 * The panel is a separate frame, so its messages cross a trust boundary: anything that is not exactly a known message is
 * ignored.
 */
export function parseUiMessage(value: unknown): UiMessage | undefined {
	if (typeof value !== "object" || value === null || !("type" in value) || !("enabled" in value)) {
		return undefined;
	}
	const { type, enabled } = value;
	if (type === "relaunch" && typeof enabled === "boolean" && Object.keys(value).length === 2) {
		return { type, enabled };
	}
	return undefined;
}
