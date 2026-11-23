import { existsSync, readdirSync, readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

/**
 * The conversion seeds: legacy rows for a database of the previous release, each with the assertion of its expected
 * disposition after the upgrade. `database-migration.mdx` § Conversion seeds owns the format.
 */
export const CUTOVER_SEEDS = fileURLToPath(
	new URL("../../server/application/src/test/resources/db/cutover-seeds/", import.meta.url),
);

export interface CutoverSeed {
	name: string;
	/** SQL that writes the legacy rows on the previous release's schema. */
	seed: string;
	/** SQL that raises an exception unless the upgrade gave each seeded row its expected disposition. */
	expect: string;
}

const SEED = ".seed.sql";
const EXPECT = ".expect.sql";
// A PL/pgSQL assertion that can fail. A bare SELECT would pass whatever it returns.
const DISPOSITION = /\bRAISE\s+EXCEPTION\b/iu;
const COMMENTS = /--[^\n]*|\/\*[\s\S]*?\*\//gu;

/** Reads every seed of a directory, and refuses a seed that names no expected disposition. */
export function readCutoverSeeds(directory = CUTOVER_SEEDS): CutoverSeed[] {
	if (!existsSync(directory)) {
		return [];
	}
	const files = readdirSync(directory).toSorted();
	const problems: string[] = [];
	const seeds: CutoverSeed[] = [];
	for (const file of files) {
		if (!file.endsWith(SEED) && !file.endsWith(EXPECT)) {
			problems.push(`${file} is neither a <name>${SEED} nor a <name>${EXPECT}`);
		} else if (file.endsWith(EXPECT) && !files.includes(file.slice(0, -EXPECT.length) + SEED)) {
			problems.push(`${file} has no seed`);
		}
	}
	for (const file of files.filter((candidate) => candidate.endsWith(SEED))) {
		const name = file.slice(0, -SEED.length);
		const read = (suffix: string) => readFileSync(path.join(directory, name + suffix), "utf8");
		if (!files.includes(name + EXPECT)) {
			problems.push(`${name} names no expected disposition: add ${name}${EXPECT}`);
			continue;
		}
		const expect = read(EXPECT);
		if (!DISPOSITION.test(expect.replaceAll(COMMENTS, ""))) {
			problems.push(`${name}${EXPECT} raises no exception, so it cannot fail a wrong disposition`);
			continue;
		}
		seeds.push({ name, seed: read(SEED), expect });
	}
	if (problems.length > 0) {
		throw new Error(`Cutover seeds are malformed:\n${problems.join("\n")}`);
	}
	return seeds;
}
