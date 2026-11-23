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
// An assertion that runs and can fail: a DO statement whose body raises. A bare SELECT passes whatever it returns.
const DOLLAR_QUOTE = /^\$(?:[A-Za-z_]\w*)?\$/u;
const RAISE = /\bRAISE\s+EXCEPTION\b/iu;

/** A piece of SQL: code, or a quoted or commented span that runs nothing by itself. */
interface Token {
	code: boolean;
	text: string;
}

/** Splits SQL into code and the comments, string literals and dollar-quoted strings between it. */
function tokenize(sql: string): Token[] {
	const tokens: Token[] = [];
	let code = "";
	let index = 0;
	const quoted = (end: number) => {
		tokens.push({ code: true, text: code }, { code: false, text: sql.slice(index, end) });
		code = "";
		index = end;
	};
	while (index < sql.length) {
		const rest = sql.slice(index);
		const dollar = DOLLAR_QUOTE.exec(rest);
		if (rest.startsWith("--")) {
			const end = sql.indexOf("\n", index);
			quoted(end === -1 ? sql.length : end);
		} else if (rest.startsWith("/*")) {
			const end = sql.indexOf("*/", index + 2);
			quoted(end === -1 ? sql.length : end + 2);
		} else if (rest.startsWith("'")) {
			let end = index + 1;
			while (end < sql.length && !(sql[end] === "'" && sql[end + 1] !== "'")) {
				end += sql[end] === "'" ? 2 : 1;
			}
			quoted(Math.min(end + 1, sql.length));
		} else if (dollar) {
			const end = sql.indexOf(dollar[0], index + dollar[0].length);
			quoted(end === -1 ? sql.length : end + dollar[0].length);
		} else {
			code += sql[index];
			index += 1;
		}
	}
	tokens.push({ code: true, text: code });
	return tokens;
}

/** Whether a statement's code raises: RAISE EXCEPTION outside its own comments and strings. */
function raises(body: string): boolean {
	return tokenize(body).some((token) => token.code && RAISE.test(token.text));
}

/** The statements of the SQL: they end at a semicolon in code, never at one inside a quoted span. */
function statementsOf(sql: string): Token[][] {
	let statement: Token[] = [];
	const statements: Token[][] = [];
	for (const token of tokenize(sql)) {
		if (!token.code) {
			statement.push(token);
			continue;
		}
		const parts = token.text.split(";");
		for (const [position, part] of parts.entries()) {
			statement.push({ code: true, text: part });
			if (position < parts.length - 1) {
				statements.push(statement);
				statement = [];
			}
		}
	}
	statements.push(statement);
	return statements;
}

/** Whether the SQL holds a DO statement whose dollar-quoted body raises. */
function assertsDisposition(sql: string): boolean {
	return statementsOf(sql).some((pieces) => {
		const meaningful = pieces.filter((piece) => !piece.code || piece.text.trim() !== "");
		const [head, body] = meaningful;
		if (head?.code !== true || body?.code !== false) {
			return false;
		}
		const quote = DOLLAR_QUOTE.exec(body.text)?.[0];
		return (
			/^\s*DO\s*$/iu.test(head.text) &&
			quote !== undefined &&
			raises(body.text.slice(quote.length, body.text.length - quote.length))
		);
	});
}

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
		if (!assertsDisposition(expect)) {
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
