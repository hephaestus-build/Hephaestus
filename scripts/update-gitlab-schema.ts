/**
 * Refreshes the vendored GitLab GraphQL schema. GitLab publishes no schema file, so it is asked for
 * one by introspection and the JSON that comes back is printed as SDL — which is the form the Gradle
 * codegen reads, and the only form a person can review as a diff.
 *
 * The default instance answers introspection without a token; `--token` exists for one that does not.
 */

import { mkdirSync, renameSync, statSync, unlinkSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";

import {
	buildClientSchema,
	getIntrospectionQuery,
	type IntrospectionQuery,
	printSchema,
} from "graphql";

import { isRecord, parseJson } from "./lib/json.ts";

const SCHEMA_DIR = resolve(
	import.meta.dirname,
	"../server/generated-clients/src/main/resources/graphql/gitlab",
);
const SCHEMA_FILE = join(SCHEMA_DIR, "schema.gitlab.graphql");
const DEFAULT_GITLAB_URL = "https://gitlab.lrz.de";

// The printed schema runs to megabytes, so size alone rejects an error page or a login redirect.
const MIN_SIZE_BYTES = 500_000;
const MAX_SIZE_BYTES = 50_000_000;
// `fetch` waits indefinitely on a server that accepts the connection and never answers, and
// introspecting a whole GitLab instance is slow enough that a person would keep waiting with it.
const REQUEST_TIMEOUT_MS = 60_000;

const HAS_TYPE = /^type\s+\w+/m;
const HAS_INPUT = /^input\s+\w+/m;
const HAS_QUERY = /^type\s+Query\s*\{/m;

interface IntrospectionResponse {
	data?: IntrospectionQuery;
	errors?: { message: string; locations?: unknown }[];
}

/**
 * An introspection payload is a whole GraphQL schema, far too large to check field by field. This
 * establishes only that a JSON object came back; `buildClientSchema` below is what rejects a payload
 * that is not really a schema, and the caller reports that.
 */
const isIntrospectionResponse = (value: unknown): value is IntrospectionResponse => isRecord(value);

function parseArgs(): { url: string; token?: string } {
	const args = process.argv.slice(2);

	if (args.includes("--help") || args.includes("-h")) {
		console.log(`Update GitLab GraphQL schema via introspection

Usage: vp run schema:gitlab [--url <gitlab-url>] [--token <pat>]

Default: ${DEFAULT_GITLAB_URL} (public introspection)

Options:
  --url <url>    GitLab instance URL (default: ${DEFAULT_GITLAB_URL})
  --token <pat>  Personal Access Token (rarely needed)
`);
		process.exit(0);
	}

	let url = DEFAULT_GITLAB_URL;
	const environmentToken = process.env.GITLAB_TOKEN?.trim();
	// An empty GITLAB_TOKEN reads as "not set" rather than as a token that will be rejected.
	let token: string | undefined = environmentToken === "" ? undefined : environmentToken;

	for (let i = 0; i < args.length; i++) {
		const nextArg = args[i + 1];
		if (args[i] === "--url" && nextArg?.trim()) {
			url = nextArg.trim();
			i++;
		} else if (args[i] === "--token" && nextArg?.trim()) {
			token = nextArg.trim();
			i++;
		}
	}

	return { url, token };
}

function validateGraphQLSchema(content: string): { valid: boolean; reason?: string } {
	if (content.length < MIN_SIZE_BYTES) {
		return {
			valid: false,
			reason: `Content too small (${content.length} bytes, minimum ${MIN_SIZE_BYTES})`,
		};
	}

	if (content.length > MAX_SIZE_BYTES) {
		return {
			valid: false,
			reason: `Content too large (${content.length} bytes, maximum ${MAX_SIZE_BYTES})`,
		};
	}

	if (!HAS_TYPE.test(content)) {
		return { valid: false, reason: "Content missing GraphQL type definitions" };
	}

	if (!HAS_INPUT.test(content)) {
		return { valid: false, reason: "Content missing GraphQL input definitions" };
	}

	if (!HAS_QUERY.test(content)) {
		return { valid: false, reason: "Content missing Query type definition" };
	}

	if (content.includes("\0")) {
		return { valid: false, reason: "Content contains null bytes (possible binary data)" };
	}

	return { valid: true };
}

async function main(): Promise<void> {
	const { url, token } = parseArgs();

	// `--url` is given as an instance, so the endpoint is appended — unless the caller already did.
	let graphqlEndpoint = url.replace(/\/+$/, "");
	if (!graphqlEndpoint.endsWith("/api/graphql")) {
		graphqlEndpoint = `${graphqlEndpoint}/api/graphql`;
	}

	console.log("Downloading GitLab GraphQL schema...");
	console.log(`Source: ${graphqlEndpoint}`);

	const headers: Record<string, string> = {
		"Content-Type": "application/json",
	};

	if (token) {
		// GitLab takes a Personal Access Token here, not as a bearer.
		headers["PRIVATE-TOKEN"] = token;
	}

	const controller = new AbortController();
	const timeout = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);

	let response: Response;
	try {
		response = await fetch(graphqlEndpoint, {
			method: "POST",
			headers,
			body: JSON.stringify({ query: getIntrospectionQuery() }),
			signal: controller.signal,
		});
	} catch (error) {
		if (error instanceof Error && error.name === "AbortError") {
			console.error(`Request timed out after ${REQUEST_TIMEOUT_MS / 1000} seconds`);
		} else {
			console.error("Failed to connect:", error instanceof Error ? error.message : error);
		}
		process.exit(1);
	} finally {
		clearTimeout(timeout);
	}

	if (!response.ok) {
		console.error(`Failed to fetch schema: ${response.status} ${response.statusText}`);
		process.exit(1);
	}

	const responseText = await response.text();
	let parsed: unknown;
	try {
		parsed = parseJson(responseText);
	} catch {
		console.error("Failed to parse response as JSON");
		console.error(`Response preview: ${responseText.substring(0, 200)}`);
		process.exit(1);
	}
	if (!isIntrospectionResponse(parsed)) {
		console.error("Invalid response: expected a JSON object");
		console.error(`Response preview: ${responseText.substring(0, 200)}`);
		process.exit(1);
	}
	const result = parsed;

	if (result.errors) {
		console.error("GraphQL errors:", JSON.stringify(result.errors, null, 2));
		process.exit(1);
	}

	if (!result.data?.__schema) {
		console.error("Invalid response: missing __schema");
		process.exit(1);
	}

	console.log("Converting to SDL...");

	let sdlContent: string;
	try {
		const schema = buildClientSchema(result.data);
		sdlContent = printSchema(schema);
	} catch (error) {
		console.error("Failed to convert to SDL:", error);
		process.exit(1);
	}

	console.log("Validating schema...");
	const validation = validateGraphQLSchema(sdlContent);
	if (!validation.valid) {
		console.error(`Validation failed: ${validation.reason}`);
		process.exit(1);
	}

	mkdirSync(SCHEMA_DIR, { recursive: true });

	const tempFile = `${SCHEMA_FILE}.tmp`;
	try {
		writeFileSync(tempFile, sdlContent, "utf8");
		const stats = statSync(tempFile);
		console.log(`Downloaded ${Math.round(stats.size / 1_048_576)}MB`);
		renameSync(tempFile, SCHEMA_FILE);
		console.log(`Schema updated successfully: ${SCHEMA_FILE}`);
		console.log("\nTo regenerate types: cd server && ./gradlew :generated-clients:classes");
	} catch (error) {
		try {
			unlinkSync(tempFile);
		} catch {
			// The write is the failure worth reporting; a failed cleanup must not replace it.
		}
		throw error;
	}
}

main().catch((error) => {
	console.error("Error updating schema:", error);
	process.exit(1);
});
