import { cn } from "cn";

import { AGENT_PURPOSE_DEFS, type AgentPurpose } from "./agent-purpose-defs";

const TILE = {
	/** Inline, beside text or in a table cell. */
	sm: "size-5 rounded-sm",
	/** The leading mark of a list row. */
	md: "size-8 rounded-md",
};

/** Pixels, because Heph's mark takes its size from a number, not from a class. */
const GLYPH = { sm: 14, md: 16 };

export interface ModelKindMarkProps {
	purpose: AgentPurpose;
	size?: keyof typeof TILE;
	/**
	 * `visible` writes the kind's name beside the tile, in the surrounding text style. `sr-only`
	 * keeps the name for a screen reader alone, where the tile stands by itself in a cell or beside
	 * a heading that names the kind already.
	 */
	label?: "visible" | "sr-only";
	/**
	 * The words in place of the kind's title, where the mark names a model rather than a purpose,
	 * such as "Review model" in a script's needs.
	 */
	name?: string;
	className?: string;
}

/**
 * Which kind of model something is: the kind's tinted glyph on a neutral tile. The ground stays
 * neutral, because a tinted ground means a practice group and a tinted badge means a status. The
 * name is always there, visible or for a screen reader, so the colour never carries the kind
 * alone.
 */
export function ModelKindMark({
	purpose,
	size = "sm",
	label = "visible",
	name,
	className,
}: ModelKindMarkProps) {
	const { title, icon: Icon, tone } = AGENT_PURPOSE_DEFS[purpose];
	return (
		<span className={cn("inline-flex min-w-0 items-center gap-2", className)}>
			<span
				aria-hidden="true"
				className={cn(
					"inline-flex shrink-0 items-center justify-center bg-muted",
					TILE[size],
					tone,
				)}
			>
				<Icon size={GLYPH[size]} />
			</span>
			<span className={label === "sr-only" ? "sr-only" : "min-w-0 truncate"}>{name ?? title}</span>
		</span>
	);
}
