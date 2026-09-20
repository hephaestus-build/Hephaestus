import { renameSync, writeFileSync } from "node:fs";

export type PracticeCoverageOutcome = "EVALUATED" | "NOT_REACHED";

export interface PracticeCoverage {
	eligible: number;
	evaluated: number;
	outcomes: { practiceSlug: string; outcome: PracticeCoverageOutcome }[];
}

export class PracticeCoverageLedger {
	readonly #path: string;
	readonly #eligible: readonly string[];
	readonly #eligibleSet: ReadonlySet<string>;
	readonly #evaluated = new Set<string>();

	constructor(path: string, eligible: readonly string[]) {
		if (new Set(eligible).size !== eligible.length || eligible.some((slug) => !slug.trim())) {
			throw new Error("eligible practice slugs must be unique and non-empty");
		}
		this.#eligible = [...eligible];
		this.#eligibleSet = new Set(eligible);
		this.#path = path;
		this.persist();
	}

	markEvaluated(practiceSlugs: readonly string[]) {
		const unknown = practiceSlugs.find((slug) => !this.#eligibleSet.has(slug));
		if (unknown !== undefined) {
			throw new Error(`evaluated practice is not eligible: ${unknown}`);
		}
		for (const slug of practiceSlugs) {
			this.#evaluated.add(slug);
		}
		return this.persist();
	}

	persist() {
		const coverage = reconcilePracticeCoverage(this.#eligible, [...this.#evaluated]);
		writePracticeCoverage(this.#path, coverage);
		return coverage;
	}
}

export function reconcilePracticeCoverage(
	eligible: readonly string[],
	evaluated: readonly string[],
): PracticeCoverage {
	const evaluatedSet = new Set(evaluated);
	const outcomes = eligible.map((practiceSlug) => ({
		practiceSlug,
		outcome: evaluatedSet.has(practiceSlug) ? ("EVALUATED" as const) : ("NOT_REACHED" as const),
	}));
	return {
		eligible: outcomes.length,
		evaluated: outcomes.filter(({ outcome }) => outcome === "EVALUATED").length,
		outcomes,
	};
}

export function writePracticeCoverage(path: string, coverage: PracticeCoverage) {
	const temporaryPath = `${path}.tmp`;
	writeFileSync(temporaryPath, JSON.stringify(coverage));
	renameSync(temporaryPath, path);
}
