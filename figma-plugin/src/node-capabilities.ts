/**
 * The relaunch and plugin-data methods of a Figma node. Some FigJam table nodes and their cells have none of them, and
 * calling a missing one throws a `TypeError` inside Figma (#5406, proof 1). So every call checks for the method first.
 */
export interface CapabilityNode {
	readonly type: string;
	getRelaunchData?: () => Record<string, string>;
	setRelaunchData?: (data: Record<string, string>) => void;
	getPluginDataKeys?: () => string[];
}

export interface NodeInspection {
	relaunchReadable: boolean;
	relaunchWritable: boolean;
	/** The relaunch commands the node holds. Only `review` is ever ours. */
	relaunchCommands: string[];
	pluginDataKeys: string[];
}

/** What the node can hold and holds now. It reads only, and never throws for a missing method. */
export function inspectNode(node: CapabilityNode): NodeInspection {
	return {
		relaunchReadable: typeof node.getRelaunchData === "function",
		relaunchWritable: typeof node.setRelaunchData === "function",
		relaunchCommands:
			typeof node.getRelaunchData === "function" ? Object.keys(node.getRelaunchData()) : [],
		pluginDataKeys: typeof node.getPluginDataKeys === "function" ? node.getPluginDataKeys() : [],
	};
}

/**
 * `WRITTEN`: the node now holds the command, or no longer holds it. `UNSUPPORTED`: the node has no such method.
 * `REFUSED`: Figma refused the write. The last two tell the member to use Actions instead.
 */
export type RelaunchWrite = "WRITTEN" | "UNSUPPORTED" | "REFUSED";

/**
 * Adds or removes the one relaunch command, **Review with Hephaestus**, on an instructor's act. The node holds only
 * the command name: no description, team, token or review.
 */
export function setReviewRelaunch(node: CapabilityNode, enabled: boolean): RelaunchWrite {
	if (typeof node.setRelaunchData !== "function") {
		return "UNSUPPORTED";
	}
	try {
		node.setRelaunchData(enabled ? { review: "" } : {});
		return "WRITTEN";
	} catch {
		return "REFUSED";
	}
}
