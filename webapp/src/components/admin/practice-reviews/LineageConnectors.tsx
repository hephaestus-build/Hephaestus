import { useLayoutEffect, useRef, useState } from "react";

import { cn } from "cn";
import { definitionFor, type BadgeVariant, statusToneClass } from "@/components/common/status-def";
import { OUTCOME_DEFS } from "@/components/practice-vocabulary/outcome-defs";

import type { LineageFeedback, NumberedResult } from "./lineage-chain";
import type { Traced } from "./review-trace";

/**
 * Room per lane in the gutter right of the steps, in pixels: wide enough that each line reads as
 * its own and its bends show. The gutter is on the right so that turning the connections on leaves
 * every step where it was: the words wrap a little earlier, but nothing moves sideways.
 */
export const LANE_PX = 16;

/** The room between the steps' edge and the first lane, so a line leaves a card before it turns. */
const LANE_INSET_PX = 16;

/** More lanes than this would take too much of a narrow sheet: the numbers say the same alone. */
const MOST_LANES = 10;

export function connectorLanes(
	numbered: readonly NumberedResult[],
	feedback: readonly LineageFeedback[],
): string[] {
	const used = new Set(feedback.flatMap((entry) => entry.basis));
	const lanes = numbered
		.filter((entry) => used.has(entry.result.id))
		.map((entry) => entry.result.id);
	return lanes.length > MOST_LANES ? [] : lanes;
}

/** The gutter the lanes need, as a length for `--lane-gutter`. */
export function laneGutter(lanes: readonly string[]): string | undefined {
	return lanes.length === 0 ? undefined : `${LANE_INSET_PX + lanes.length * LANE_PX}px`;
}

interface Connector {
	key: string;
	practiceResultId: string;
	feedbackId: string;
	d: string;
	/** The middle of the lane the line runs down, for a test to read. */
	laneX: number;
	start: { x: number; y: number };
	/** Where the line ends, on the right edge of the piece's card, at its vertical centre, marked with a dot. */
	end: { x: number; y: number };
}

/** How every connector is stroked, on the rail and in the legend. */
const CONNECTOR_STROKE = {
	fill: "none",
	stroke: "currentColor",
	strokeWidth: 1.5,
	strokeLinecap: "round",
	strokeLinejoin: "round",
} as const;

/** The dot a connector ends in, on the piece of feedback. */
const CONNECTOR_END_RADIUS = 2.25;

/**
 * A short connector for the legend, drawn as the rail draws one: out of a card, round a bend into
 * its lane, down and back into a card, ending in a dot. `className` sets its colour.
 */
export function ConnectorSample({ className }: { className?: string }) {
	const start = { x: 1, y: 2 };
	const end = { x: 1, y: 12 };
	return (
		<svg aria-hidden viewBox="0 0 16 14" className={cn("h-3.5 w-4 shrink-0", className)}>
			<path d={connectorPath(start, end, 12)} {...CONNECTOR_STROKE} />
			<circle cx={end.x + 1.25} cy={end.y} r={CONNECTOR_END_RADIUS} fill="currentColor" />
		</svg>
	);
}

export function connectorTone(
	variant: BadgeVariant | undefined,
	state: "strong" | "plain" | "faint",
): string {
	if (state === "faint" || variant === undefined) {
		return "text-border";
	}
	return cn(statusToneClass(variant), state === "plain" && "opacity-60");
}

function lineState(faint: boolean, strong: boolean): "strong" | "plain" | "faint" {
	if (faint) {
		return "faint";
	}
	return strong ? "strong" : "plain";
}

/** The most a bend is rounded, in pixels. */
const BEND_PX = 6;

/**
 * The card a connector starts or ends at: the step's own box around the chip, so the line meets
 * the middle of the box however tall it grows, not the chip inside it.
 */
export function connectorBox(chip: Element): Element {
	return chip.closest("[data-step-control]") ?? chip;
}

/**
 * Right from the card's edge into the lane, down or up the lane, and left into the other card's
 * edge, with each corner rounded alike.
 */
function connectorPath(
	start: { x: number; y: number },
	end: { x: number; y: number },
	laneX: number,
): string {
	const dy = end.y - start.y;
	const down = dy >= 0 ? 1 : -1;
	const bend = Math.max(0, Math.min(BEND_PX, Math.abs(dy) / 2, laneX - start.x, laneX - end.x));
	if (bend === 0) {
		return `M ${start.x} ${start.y} H ${laneX} V ${end.y} H ${end.x}`;
	}
	return [
		`M ${start.x} ${start.y}`,
		`H ${laneX - bend}`,
		`Q ${laneX} ${start.y} ${laneX} ${start.y + bend * down}`,
		`V ${end.y - bend * down}`,
		`Q ${laneX} ${end.y} ${laneX - bend} ${end.y}`,
		`H ${end.x}`,
	].join(" ");
}

interface Measured {
	practiceResultId: string;
	start: { x: number; y: number };
	ends: { feedbackId: string; x: number; y: number }[];
}

function spanOf(entry: Measured): number {
	const ys = [entry.start.y, ...entry.ends.map((end) => end.y)];
	return Math.max(...ys) - Math.min(...ys);
}

function laneOrder(measured: readonly Measured[]): Map<string, number> {
	const order = measured
		.map((entry, index) => ({ entry, index, span: spanOf(entry) }))
		.sort((a, b) => a.span - b.span || b.index - a.index);
	return new Map(order.map(({ entry }, lane) => [entry.practiceResultId, lane]));
}

export function LineageConnectors({
	container,
	lanes,
	numbered,
	traced,
}: {
	container: React.RefObject<HTMLElement | null>;
	lanes: readonly string[];
	numbered: readonly NumberedResult[];
	traced: Traced | undefined;
}) {
	const [lines, setLines] = useState<Connector[]>([]);
	const svg = useRef<SVGSVGElement>(null);
	const laneKey = lanes.join(",");
	useLayoutEffect(() => {
		// The rail is the drawing's own parent: a parent's ref is set only after this effect runs.
		const box = container.current ?? svg.current?.parentElement ?? null;
		if (box === null) {
			return;
		}
		const ids = laneKey.split(",").filter((id) => id.length > 0);
		let drawn = "";
		const resize = new ResizeObserver(() => schedule());
		const observed = new Set<Element>();
		const watch = (element: Element) => {
			if (!observed.has(element)) {
				observed.add(element);
				resize.observe(element);
			}
		};
		watch(box);
		const measure = () => {
			const origin = box.getBoundingClientRect();
			const content = box.querySelector<HTMLElement>("[data-step-content]");
			if (content === null) {
				drawn = "";
				setLines([]);
				return;
			}
			// The steps' right edge, where the gutter the lanes run in starts.
			const gutter = Number.parseFloat(getComputedStyle(content).paddingRight) || 0;
			const edge = content.getBoundingClientRect().right - origin.left - gutter;
			const centreOf = (chip: Element): { x: number; y: number } => {
				const card = connectorBox(chip);
				watch(card);
				const rect = card.getBoundingClientRect();
				return {
					// A card never reaches into the gutter, so a line never starts inside one.
					x: Math.min(rect.right - origin.left, edge),
					y: rect.top - origin.top + rect.height / 2,
				};
			};
			const measured: Measured[] = [];
			for (const practiceResultId of ids) {
				const id = CSS.escape(practiceResultId);
				const source = box.querySelector(`[data-connector="source"][data-result-id="${id}"]`);
				if (source === null) {
					continue;
				}
				const start = centreOf(source);
				const ends: Measured["ends"] = [];
				for (const target of box.querySelectorAll<HTMLElement>(
					`[data-connector^="use:"][data-result-id="${id}"]`,
				)) {
					ends.push({
						feedbackId: (target.dataset.connector ?? "").slice("use:".length),
						...centreOf(target),
					});
				}
				if (ends.length > 0) {
					measured.push({ practiceResultId, start, ends });
				}
			}
			const order = laneOrder(measured);
			const next: Connector[] = measured.flatMap(({ practiceResultId, start, ends }) => {
				const lane = order.get(practiceResultId) ?? 0;
				const laneX = edge + LANE_INSET_PX + lane * LANE_PX + LANE_PX / 2;
				return ends.map(({ feedbackId, x, y }) => ({
					key: `${practiceResultId}:use:${feedbackId}`,
					practiceResultId,
					feedbackId,
					laneX,
					start,
					end: { x, y },
					d: connectorPath(start, { x, y }, laneX),
				}));
			});
			const signature = next.map((line) => `${line.key}=${line.d}`).join("|");
			if (signature !== drawn) {
				drawn = signature;
				setLines(next);
			}
		};
		let frame: number | undefined;
		// A late callback, such as fonts.ready, must not draw this run's lanes over the next run's.
		let disposed = false;
		function schedule() {
			if (!disposed && frame === undefined) {
				frame = requestAnimationFrame(() => {
					frame = undefined;
					measure();
				});
			}
		}
		measure();
		// A card that appears, or words that change, may not resize the rail itself.
		const mutation = new MutationObserver(() => schedule());
		mutation.observe(box, { childList: true, subtree: true, characterData: true });
		// Words that wrap differently once the font loads move the cards; a test DOM has no fonts.
		if ("fonts" in document) {
			void document.fonts.ready.then(() => schedule());
		}
		return () => {
			disposed = true;
			if (frame !== undefined) {
				cancelAnimationFrame(frame);
			}
			resize.disconnect();
			mutation.disconnect();
			observed.clear();
		};
	}, [container, laneKey]);
	const outcomes = new Map(numbered.map((entry) => [entry.result.id, entry.result.outcome]));
	const strong = (line: Connector) =>
		traced !== undefined &&
		traced.results.has(line.practiceResultId) &&
		traced.feedback.has(line.feedbackId);
	return (
		<svg
			ref={svg}
			aria-hidden
			className="pointer-events-none absolute inset-0 size-full overflow-visible"
		>
			{[...lines]
				.sort((a, b) => Number(strong(a)) - Number(strong(b)))
				.map((line) => {
					const outcome = outcomes.get(line.practiceResultId);
					const faint = traced !== undefined && !strong(line);
					const tone = connectorTone(
						outcome === undefined ? undefined : definitionFor(OUTCOME_DEFS, outcome).badgeVariant,
						lineState(faint, strong(line)),
					);
					return (
						<g
							key={line.key}
							className={cn("transition-colors motion-reduce:transition-none", tone)}
						>
							<path
								d={line.d}
								data-connector-line={line.key}
								data-from-result={line.practiceResultId}
								data-to-feedback={line.feedbackId}
								data-lane-x={line.laneX}
								data-strong={strong(line)}
								{...CONNECTOR_STROKE}
							/>
							<circle
								cx={line.end.x}
								cy={line.end.y}
								r={CONNECTOR_END_RADIUS}
								fill="currentColor"
							/>
						</g>
					);
				})}
		</svg>
	);
}
