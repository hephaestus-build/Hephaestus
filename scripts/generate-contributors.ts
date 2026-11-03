import { mkdir, writeFile } from "node:fs/promises";
import path from "node:path";

import { retryTransient } from "./lib/github.ts";
import { asArray, asRecord, asString, isRecord, parseJson } from "./lib/json.ts";
import { output } from "./lib/process.ts";

const REPOSITORY = "hephaestus-build/Hephaestus";

/** Release automation that GitHub lists as a user, not as a bot. */
const AUTOMATION_LOGINS = new Set(["semantic-release-bot"]);

function isPerson(entry: Record<string, unknown>): boolean {
	return (
		entry.type !== "Bot" && !AUTOMATION_LOGINS.has(asString(entry.login, "login").toLowerCase())
	);
}

/** A GitHub login, so it can stand unquoted in a GraphQL alias and string. */
const LOGIN = /^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$/u;

/** Where the server's packaging build reads the list; Gradle adds the directory to the resources. */
const CONTRIBUTORS_FILE = path.join(
	import.meta.dirname,
	"../server/application/build/contributors/contributors.json",
);

function count(value: unknown, label: string): number {
	if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 0) {
		throw new TypeError(`${label} must be a non-negative integer`);
	}
	return value;
}

export interface Contributor {
	id: number;
	login: string;
	name: string;
	avatarUrl: string;
	htmlUrl: string;
	contributions: number;
}

/** The About page's list: people only, by contributions, each named as their profile names them. */
export function contributors(
	listed: readonly unknown[],
	names: ReadonlyMap<string, string>,
): Contributor[] {
	const people = listed
		.map((entry) => asRecord(entry, "contributor"))
		.filter(isPerson)
		.map((entry) => {
			const login = asString(entry.login, "login");
			return {
				id: count(entry.id, "id"),
				login,
				name: names.get(login) ?? login,
				avatarUrl: asString(entry.avatar_url, "avatar_url"),
				htmlUrl: asString(entry.html_url, "html_url"),
				contributions: count(entry.contributions, "contributions"),
			};
		})
		.toSorted((left, right) => right.contributions - left.contributions);
	if (people.length === 0) {
		throw new Error(`GitHub lists no contributors for ${REPOSITORY}`);
	}
	return people;
}

/** One GraphQL request for every profile name; a login without a name keeps its login. */
function namesQuery(logins: readonly string[]): string {
	return `{ ${logins
		.map((login, index) => {
			if (!LOGIN.test(login)) {
				throw new Error(`GitHub returned a login that is not valid: ${login}`);
			}
			return `u${index}: user(login: "${login}") { login name }`;
		})
		.join(" ")} }`;
}

async function gh(args: string[]): Promise<unknown> {
	return parseJson(await retryTransient(async () => output("gh", ["api", ...args])));
}

if (import.meta.main) {
	const listed = asArray(
		await gh([`repos/${REPOSITORY}/contributors?per_page=100`, "--paginate", "--slurp"]),
		"contributor pages",
	).flatMap((page) => asArray(page, "contributor page"));
	// The list refuses to be empty before any name is asked for.
	const logins = contributors(listed, new Map()).map(({ login }) => login);
	const users = asRecord(
		asRecord(await gh(["graphql", "-f", `query=${namesQuery(logins)}`]), "names").data,
		"names data",
	);
	const names = new Map<string, string>();
	for (const user of Object.values(users)) {
		if (isRecord(user) && typeof user.name === "string" && user.name.trim() !== "") {
			names.set(asString(user.login, "login"), user.name);
		}
	}
	const list = contributors(listed, names);
	await mkdir(path.dirname(CONTRIBUTORS_FILE), { recursive: true });
	await writeFile(CONTRIBUTORS_FILE, `${JSON.stringify(list, null, "\t")}\n`);
	console.log(
		`Wrote ${list.length} contributors to ${path.relative(process.cwd(), CONTRIBUTORS_FILE)}`,
	);
}
