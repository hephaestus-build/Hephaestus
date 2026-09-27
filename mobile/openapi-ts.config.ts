import { defineConfig } from "@hey-api/openapi-ts";

import operations from "./api-contract/operations.json" with { type: "json" };

// The same generator and plugins as the webapp, from the same spec: two clients, one contract.
export default defineConfig({
	input: "../server/openapi.yaml",
	// Metro resolves an import by the file it names, so the generated files import each other without
	// the `.js` suffix a Node ESM consumer would need.
	output: { path: "src/api", importFileExtension: null },
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
		{
			name: "@hey-api/sdk",
			transformer: true,
		},
	],
	parser: {
		filters: {
			// Only the operations the app calls, so the list an installed app is held to
			// (`gate:mobile-api`) cannot fall behind the code: a call missing from it does not compile.
			// Heph's stream is not among them; the AI SDK transport speaks it.
			operations: { include: operations },
		},
	},
});
