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

/** A piece of SQL: code, a comment, or a quoted span (a string or an identifier) that runs nothing by itself. */
interface Token {
	kind: "code" | "comment" | "quoted";
	text: string;
}

/** The end of a block comment that starts at `start`. PostgreSQL nests block comments. */
function blockCommentEnd(sql: string, start: number): number {
	let depth = 0;
	let index = start;
	while (index < sql.length) {
		if (sql.startsWith("/*", index)) {
			depth += 1;
			index += 2;
		} else if (sql.startsWith("*/", index)) {
			depth -= 1;
			index += 2;
			if (depth === 0) {
				return index;
			}
		} else {
			index += 1;
		}
	}
	return sql.length;
}

/** The end of a span quoted by `quote`, where a doubled quote stands for itself. */
function quotedEnd(sql: string, start: number, quote: string): number {
	let index = start + 1;
	while (index < sql.length) {
		if (sql[index] === quote) {
			if (sql[index + 1] !== quote) {
				return index + 1;
			}
			index += 2;
		} else {
			index += 1;
		}
	}
	return sql.length;
}

/** Splits SQL into code, comments, and quoted spans: strings, dollar-quoted strings and quoted identifiers. */
function tokenize(sql: string): Token[] {
	const tokens: Token[] = [];
	let code = "";
	let index = 0;
	const span = (kind: Token["kind"], end: number) => {
		tokens.push({ kind: "code", text: code }, { kind, text: sql.slice(index, end) });
		code = "";
		index = end;
	};
	while (index < sql.length) {
		const rest = sql.slice(index);
		const dollar = DOLLAR_QUOTE.exec(rest);
		if (rest.startsWith("--")) {
			const end = sql.indexOf("\n", index);
			span("comment", end === -1 ? sql.length : end);
		} else if (rest.startsWith("/*")) {
			span("comment", blockCommentEnd(sql, index));
		} else if (rest.startsWith("'") || rest.startsWith('"')) {
			span("quoted", quotedEnd(sql, index, rest.charAt(0)));
		} else if (dollar) {
			const end = sql.indexOf(dollar[0], index + dollar[0].length);
			span("quoted", end === -1 ? sql.length : end + dollar[0].length);
		} else {
			code += sql.charAt(index);
			index += 1;
		}
	}
	tokens.push({ kind: "code", text: code });
	return tokens;
}

/** Whether a body's code raises: RAISE EXCEPTION outside its own comments and quoted spans. */
function raises(body: string): boolean {
	return tokenize(body).some((token) => token.kind === "code" && RAISE.test(token.text));
}

/** The statements of the SQL: they end at a semicolon in code, never at one inside a comment or a quoted span. */
function statementsOf(sql: string): Token[][] {
	let statement: Token[] = [];
	const statements: Token[][] = [];
	for (const token of tokenize(sql)) {
		if (token.kind !== "code") {
			statement.push(token);
			continue;
		}
		const parts = token.text.split(";");
		for (const [position, part] of parts.entries()) {
			statement.push({ kind: "code", text: part });
			if (position < parts.length - 1) {
				statements.push(statement);
				statement = [];
			}
		}
	}
	statements.push(statement);
	return statements;
}

/** Whether the SQL holds a DO statement whose dollar-quoted body raises. Comments around the statement do not count. */
function assertsDisposition(sql: string): boolean {
	return statementsOf(sql).some((pieces) => {
		const meaningful = pieces.filter(
			(piece) => piece.kind === "quoted" || (piece.kind === "code" && piece.text.trim() !== ""),
		);
		const [head, body] = meaningful;
		if (head?.kind !== "code" || body?.kind !== "quoted") {
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
