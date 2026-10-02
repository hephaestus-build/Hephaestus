import { defineConfig } from "@hey-api/openapi-ts";

/**
 * The webapp's contract, generated a second time for the extension, which never imports the webapp
 * client. No `@hey-api/transformers`: every response crosses `chrome.runtime` messaging as JSON,
 * which turns a `Date` back into a string, so the worker keeps the wire's ISO strings and the types
 * say so. No TanStack plugin: the views reach the API only through the worker.
 */
export default defineConfig({
	input: "../server/openapi.yaml",
	output: "src/api",
	plugins: ["@hey-api/typescript", "@hey-api/client-fetch", "@hey-api/sdk"],
	parser: {
		filters: {
			operations: {
				exclude: ["POST /workspaces/{workspaceSlug}/mentor/chat"],
			},
		},
	},
});
