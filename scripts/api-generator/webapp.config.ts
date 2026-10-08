import { defineConfig } from "@hey-api/openapi-ts";

export default defineConfig({
	input: "../../server/openapi.yaml",
	output: "../../webapp/src/api",
	plugins: [
		"@hey-api/typescript",
		"@hey-api/client-fetch",
		{
			name: "@tanstack/react-query",
			queryKeys: { tags: true },
		},
		{
			dates: true,
			bigInt: false,
			name: "@hey-api/transformers",
		},
		// Apply generated date transformers so SDK values match their Date types.
		{
			name: "@hey-api/sdk",
			transformer: true,
		},
	],
	// SSE operations use the streaming client rather than generated query hooks.
	parser: {
		filters: {
			operations: {
				exclude: ["POST /workspaces/{workspaceSlug}/mentor/chat"],
			},
		},
	},
});
