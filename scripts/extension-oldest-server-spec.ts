/**
 * Copies what the browser extension calls from a tagged server release, for
 * `extension/src/background/older-server.test.ts`. Run it with `v1.0.0` once that tag exists, and
 * again when `OLDEST_SERVER_VERSION` moves.
 *
 * Usage: `node scripts/extension-oldest-server-spec.ts <release tag>`.
 */
import { execFileSync } from "node:child_process";
import { readFileSync, writeFileSync } from "node:fs";

import { parse } from "yaml";

type Node = Record<string, unknown>;

function isNode(value: unknown): value is Node {
	return typeof value === "object" && value !== null;
}

function node(value: unknown): Node {
	return isNode(value) ? value : {};
}

const tag = process.argv[2];
if (tag === undefined) {
	throw new Error("Name the release tag, such as v1.0.0");
}
const spec: unknown = parse(
	execFileSync("git", ["show", `${tag}:server/openapi.yaml`], {
		encoding: "utf8",
		maxBuffer: 1024 * 1024 * 64,
	}),
);
const client = readFileSync("extension/src/background/api.ts", "utf8");
const called = new Set(
	/import \{(?<names>[^}]*)\} from "~\/api\/sdk\.gen"/u
		.exec(client)
		?.groups?.names?.split(",")
		.map((name) => name.trim())
		.filter(Boolean),
);

const paths: Record<string, Node> = {};
for (const [path, item] of Object.entries(node(node(spec).paths))) {
	for (const [method, operation] of Object.entries(node(item))) {
		const id = node(operation).operationId;
		if (typeof id === "string" && called.has(id)) {
			paths[path] = { ...paths[path], [method]: operation };
		}
	}
}

const all = node(node(node(spec).components).schemas);
const schemas: Node = {};
function visit(value: unknown): void {
	if (!isNode(value)) {
		return;
	}
	for (const [key, inner] of Object.entries(value)) {
		const name = key === "$ref" && typeof inner === "string" ? inner.split("/").at(-1) : undefined;
		if (name === undefined) {
			visit(inner);
		} else if (!(name in schemas)) {
			schemas[name] = all[name];
			visit(all[name]);
		}
	}
}
visit(paths);

writeFileSync(
	"extension/src/testing/oldest-server-api.json",
	`${JSON.stringify({ source: tag, paths, components: { schemas } }, null, "\t")}\n`,
);
