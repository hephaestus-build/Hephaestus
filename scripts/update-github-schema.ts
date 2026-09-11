/**
 * Refreshes the vendored GitHub GraphQL schema, which the Gradle codegen turns into the client the
 * server compiles against. Vendoring makes each refresh a reviewable diff instead of a build that
 * changes under you.
 *
 * Everything downloaded is checked before it reaches disk. An unauthenticated fetch of a public URL
 * can return a login wall, a CDN error page or a truncated body with a 200, and each of those would
 * otherwise land in the tree as the schema and fail much later as a codegen error.
 */

import { renameSync, statSync, unlinkSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";

const SCHEMA_DIR = resolve(
	import.meta.dirname,
	"../server/generated-clients/src/main/resources/graphql/github",
);
const SCHEMA_FILE = join(SCHEMA_DIR, "schema.github.graphql");
const SCHEMA_URL = "https://docs.github.com/public/fpt/schema.docs.graphql";

// An error page or a login wall is orders of magnitude smaller than the schema, so size alone
// rejects most of what can come back instead of it.
const MIN_SIZE_BYTES = 1_000_000;
const MAX_SIZE_BYTES = 50_000_000;

const STARTS_WITH_DOC_COMMENT = /^\s*"""/;
const HAS_DIRECTIVE = /directive\s+@/;
const HAS_TYPE = /^type\s+\w+/m;
const HAS_INPUT = /^input\s+\w+/m;

/** Cheapest checks first: a wrong body is usually the wrong size, and never reaches the regexes. */
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

	if (!STARTS_WITH_DOC_COMMENT.test(content)) {
		return {
			valid: false,
			reason: "Content does not start with expected GraphQL documentation comment",
		};
	}

	const hasDirective = HAS_DIRECTIVE.test(content);
	const hasType = HAS_TYPE.test(content);
	const hasInput = HAS_INPUT.test(content);

	if (!hasDirective) {
		return { valid: false, reason: "Content missing GraphQL directive definitions" };
	}

	if (!hasType) {
		return { valid: false, reason: "Content missing GraphQL type definitions" };
	}

	if (!hasInput) {
		return { valid: false, reason: "Content missing GraphQL input definitions" };
	}

	// Some other GraphQL schema would pass every check above; these two directives are GitHub's.
	if (!content.includes("@preview") && !content.includes("@possibleTypes")) {
		return { valid: false, reason: "Content missing expected GitHub-specific directives" };
	}

	if (content.includes("\0")) {
		return { valid: false, reason: "Content contains null bytes (possible binary data)" };
	}

	return { valid: true };
}

async function main(): Promise<void> {
	console.log("Downloading GitHub GraphQL schema...");
	console.log(`Source: ${SCHEMA_URL}`);

	const response = await fetch(SCHEMA_URL);

	if (!response.ok) {
		console.error(`Failed to fetch schema: ${response.status} ${response.statusText}`);
		process.exit(1);
	}

	const contentType = response.headers.get("content-type") ?? "";
	const acceptableContentTypes = [
		"text/",
		"application/graphql",
		"application/octet-stream", // GitHub's default for file downloads
	];
	if (!acceptableContentTypes.some((type) => contentType.includes(type))) {
		console.error(`Unexpected Content-Type: ${contentType}`);
		console.error(`Expected one of: ${acceptableContentTypes.join(", ")}`);
		process.exit(1);
	}

	const content = await response.text();

	console.log("Validating schema content...");
	const validation = validateGraphQLSchema(content);

	if (!validation.valid) {
		console.error(`Schema validation failed: ${validation.reason}`);
		process.exit(1);
	}

	// Written beside the target and renamed over it, so an interrupted run leaves the committed
	// schema whole rather than half a download.
	const tempFile = `${SCHEMA_FILE}.tmp`;

	try {
		writeFileSync(tempFile, content, "utf8");

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
