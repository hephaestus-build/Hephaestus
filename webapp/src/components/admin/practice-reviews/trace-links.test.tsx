import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { computeAccessibleName } from "dom-accessibility-api";
import { afterEach, describe, expect, it, type Mock, vi } from "vitest";

import { LINEAGE_STATE_CASES, type LineageStateCase } from "./lineage-state-fixtures";
import type { RecordRef } from "./review-layers";
import type { TraceNode } from "./review-trace";
import { ReviewLineageGraph } from "./ReviewLineageGraph";
import { DEFAULT_TRACE_DISPLAY, FULL_TRACE_DISPLAY, type TraceDisplay } from "./trace-display";

/**
 * The link audit of the trace, over every state the story can meet: each step opens one record and
 * no record opens from two steps, no control sits in another, every control on the rail has a name
 * of its own, and a link out of the trace opens in a new tab.
 */

afterEach(cleanup);

const CASES = Object.values(LINEAGE_STATE_CASES).flat();

const VIEWS: readonly { name: string; display: TraceDisplay; expand: boolean }[] = [
	{ name: "as it opens", display: DEFAULT_TRACE_DISPLAY, expand: false },
	{ name: "with every part shown and expanded", display: FULL_TRACE_DISPLAY, expand: true },
];

function renderTrace(
	entry: LineageStateCase,
	display: TraceDisplay,
	expand: boolean,
	here?: TraceNode,
) {
	const onOpen = vi.fn<(record: RecordRef) => void>();
	const { container } = render(
		<ReviewLineageGraph
			lineage={entry.lineage}
			here={here ?? { kind: "review" }}
			defaultDisplay={display}
			onOpen={onOpen}
		/>,
	);
	if (expand) {
		fireEvent.click(screen.getByRole("button", { name: "Expand all" }));
	}
	const rail = within(container).getByRole("list", { name: "Steps, oldest first" });
	return { container, rail, onOpen };
}

function recordKey(record: RecordRef): string {
	return `${record.kind}:${record.id}`;
}

interface StepAudit {
	/** The step's key, and how many controls of its own it has. */
	controls: Record<string, number>;
	/** How many records a press on each control that opens anything opened. */
	opensPerPress: number[];
	/** Each record a press opened, in rail order. */
	opened: string[];
}

/** Presses every step's own control, and records what each press opened. */
function auditSteps(rail: HTMLElement, onOpen: Mock<(record: RecordRef) => void>): StepAudit {
	const audit: StepAudit = { controls: {}, opensPerPress: [], opened: [] };
	for (const step of rail.querySelectorAll<HTMLElement>("[data-step-key]")) {
		const controls = step.querySelectorAll(":scope > [data-step-control]");
		audit.controls[step.dataset.stepKey ?? ""] = controls.length;
		const [control] = controls;
		if (!(control instanceof HTMLButtonElement)) {
			continue;
		}
		onOpen.mockClear();
		fireEvent.click(control);
		audit.opensPerPress.push(onOpen.mock.calls.length);
		for (const [record] of onOpen.mock.calls) {
			audit.opened.push(recordKey(record));
		}
	}
	return audit;
}

function stateCase(key: string): LineageStateCase {
	const entry = CASES.find((candidate) => candidate.key === key);
	if (entry === undefined) {
		throw new Error(`The fixtures have no case "${key}"`);
	}
	return entry;
}

describe.each(VIEWS)("the trace $name", ({ display, expand }) => {
	it.each(CASES.map((entry) => [entry.key, entry] as const))(
		"%s: names each control, keeps controls separate and opens each record once",
		(_key, entry) => {
			const { container, rail, onOpen } = renderTrace(entry, display, expand);
			expect(container.querySelectorAll("button button, button a, a button, a a")).toHaveLength(0);
			const controls = [...rail.querySelectorAll<HTMLElement>("button, a[href]")];
			// Each control's name computed once, as a reader of the accessibility tree hears it. A role
			// query per control searched the whole rail every time, so the cost grew with its square.
			const names = controls.map((control) => computeAccessibleName(control));
			for (const name of names) {
				expect(name.trim()).not.toBe("");
			}
			const repeated = names.filter((name, index) => names.indexOf(name) !== index);
			expect(repeated).toStrictEqual([]);
			for (const link of container.querySelectorAll<HTMLAnchorElement>("a[href]")) {
				expect(link.getAttribute("href")).toMatch(/^https:\/\//u);
				expect(link.target).toBe("_blank");
				expect(link.rel).toContain("noopener");
			}
			const audit = auditSteps(rail, onOpen);
			for (const count of Object.values(audit.controls)) {
				expect(count).toBe(1);
			}
			expect(audit.opensPerPress.every((count) => count === 1)).toBe(true);
			expect(new Set(audit.opened).size).toBe(audit.opened.length);
		},
	);
});

describe("links out of the trace", () => {
	it("leads to the work on its provider from the work's facts", () => {
		const { container } = renderTrace(stateCase("complete"), FULL_TRACE_DISPLAY, true);
		const links = [...container.querySelectorAll<HTMLAnchorElement>("a[href]")];
		expect(links.map((link) => link.href)).toStrictEqual([
			"https://github.com/ls1intum/Hephaestus/pull/1423",
		]);
	});
});
