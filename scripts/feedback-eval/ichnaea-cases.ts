/**
 * The composition set as Ichnaea cases (`ichnaea.case/1`). A case's input is everything the composer reads; its hidden
 * references are the decisions we expect, which never reach the composer.
 */
import type { CompositionCase, CompositionSet, Disposition } from "./compositions.ts";

/** The slice that marks a case with a settled expected withholding: the cases of the withhold measure. */
export const WITHHOLD_SLICE = { name: "expected", value: "withhold" } as const;

/** The expected decisions that count: every result except those whose decision waits for the practice owner. */
export function settled(
	item: Pick<CompositionCase, "expected" | "unsettled">,
): [string, Disposition][] {
	return Object.entries(item.expected).filter(
		([resultId]) => !(item.unsettled ?? []).includes(resultId),
	);
}

/** The settled results a case expects the composer to raise. */
export const expectedRaises = (item: Pick<CompositionCase, "expected" | "unsettled">): string[] =>
	settled(item)
		.filter(([, disposition]) => disposition === "RAISE")
		.map(([resultId]) => resultId);

/** The settled results a case expects the composer to withhold (any disposition but RAISE). */
export const expectedWithholds = (
	item: Pick<CompositionCase, "expected" | "unsettled">,
): string[] =>
	settled(item)
		.filter(([, disposition]) => disposition !== "RAISE")
		.map(([resultId]) => resultId);

/** One composition case as an Ichnaea case record. */
export function toIchnaeaCase(item: CompositionCase): Record<string, unknown> {
	const { id, provider, theme, expected, unsettled, expectedWhy, compositions, ...input } = item;
	// The labelled compositions and the reason for the expected decisions are for labellers, not the composer.
	void expectedWhy;
	void compositions;
	return {
		schema: "ichnaea.case/1",
		id,
		caseSet: "composition-set",
		unit: "turn",
		dataClass: "none",
		input: { provider, theme, ...input },
		references: { expected, unsettled: unsettled ?? [] },
		slices: {
			provider,
			theme,
			...(expectedWithholds(item).length > 0
				? { [WITHHOLD_SLICE.name]: WITHHOLD_SLICE.value }
				: {}),
		},
		provenance: {
			source: "authored",
			selection: "curated",
			created: "2026-11-01",
			licence: "MIT",
			exposure: "public",
		},
		pool: "development",
	};
}

/** The cases of a set, in the set's order. */
export const toIchnaeaCases = (set: CompositionSet): Record<string, unknown>[] =>
	set.cases.map(toIchnaeaCase);

/** The trials per arm a suite gives a case: its default, or the largest count of a `replicatesFor` slice it is in. */
export function trialsFor(
	suite: {
		replicates: number;
		replicatesFor?: readonly { slice: string; value: string; replicates: number }[];
	},
	slices: Readonly<Record<string, string>>,
): number {
	return Math.max(
		suite.replicates,
		...(suite.replicatesFor ?? [])
			.filter((entry) => slices[entry.slice] === entry.value)
			.map((entry) => entry.replicates),
	);
}
