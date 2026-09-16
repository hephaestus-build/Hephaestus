/**
 * Every variable a Compose file forwards must carry the same fallback the application does.
 *
 * A Compose file that substitutes its own default does not merely document one — it *sets* the
 * variable on the container, so its fallback wins over `application.yml` whenever the operator leaves
 * the key unset. Two defaults for one setting therefore means the deployed value is whichever file
 * the reader did not check, and nothing anywhere fails.
 *
 * Only the direction that can lie is enforced: a variable Compose forwards must match. A variable the
 * application reads and Compose does not forward is a deployment choice, not a defect, so it is
 * reported at the end rather than failed on.
 *
 * Scoped to the production topology, which is `compose.app.yaml` and `compose.core.yaml` together:
 * they are one deployment split by runtime role, and a variable that only the webhook container can
 * read is only ever forwarded by the second. `docker/preview/` deliberately runs smaller than
 * production — it shares a host — so a difference there is the point rather than a defect; those are
 * listed, not failed. Which container may read what is `check-env-roles.ts`.
 */
import { readFile } from "node:fs/promises";
import path from "node:path";

/** Resolved from this file, so the gate answers the same whatever the working directory is. */
const REPO_ROOT = path.resolve(import.meta.dirname, "..");
const APPLICATION_YML = "server/application/src/main/resources/application.yml";
const COMPOSE_FILES = ["docker/compose.app.yaml", "docker/compose.core.yaml"];
const DELIBERATE_OVERRIDES = ["docker/preview/compose.app.yaml"];

/** `${VAR:default}` — Spring's syntax. Stops at `$` so a nested placeholder is skipped, not misread. */
const SPRING = /\$\{(?<name>[A-Z0-9_]+):(?<fallback>[^}$]*)\}/gu;
/** `${VAR:-default}` — Compose's syntax for the same idea. */
const COMPOSE = /\$\{(?<name>[A-Z0-9_]+):-(?<fallback>[^}$]*)\}/gu;

const collect = (text: string, pattern: RegExp): Map<string, string> => {
	const defaults = new Map<string, string>();
	for (const { groups } of text.matchAll(pattern)) {
		const name = groups?.name;
		const fallback = groups?.fallback;
		if (name !== undefined && fallback !== undefined) {
			defaults.set(name, fallback);
		}
	}
	return defaults;
};

/** A Compose file absent from this checkout is not a disagreement, so it is skipped rather than failed. */
const readIfPresent = async (file: string): Promise<string | undefined> => {
	try {
		return await readFile(path.join(REPO_ROOT, file), "utf8");
	} catch {
		return undefined;
	}
};

const application = collect(await readFile(path.join(REPO_ROOT, APPLICATION_YML), "utf8"), SPRING);

let failed = false;
const forwarded = new Set<string>();
for (const file of COMPOSE_FILES) {
	const text = await readIfPresent(file);
	if (text === undefined) {
		continue;
	}
	for (const [name, fallback] of collect(text, COMPOSE)) {
		forwarded.add(name);
		const expected = application.get(name);
		if (expected !== undefined && expected !== fallback) {
			failed = true;
			console.error(`${file}: ${name}`);
			console.error(`  ${APPLICATION_YML} falls back to "${expected}"`);
			console.error(`  this file falls back to "${fallback}", which is what a container gets\n`);
		}
	}
}

if (failed) {
	console.error(
		"Align the Compose fallback with the application's, or drop it so the application decides.",
	);
	process.exit(1);
}

for (const file of DELIBERATE_OVERRIDES) {
	const text = await readIfPresent(file);
	if (text === undefined) {
		continue;
	}
	for (const [name, fallback] of collect(text, COMPOSE)) {
		const expected = application.get(name);
		if (expected !== undefined && expected !== fallback) {
			console.log(
				`  ${file} runs ${name} at "${fallback}" rather than "${expected}" — on purpose.`,
			);
		}
	}
}

const unforwarded = [...application.keys()].filter((name) => !forwarded.has(name));
console.log(
	`check-env-defaults: ${forwarded.size} forwarded variable(s) agree with ${APPLICATION_YML}` +
		` (${unforwarded.length} read by the application are not forwarded by Compose).`,
);
