import { decidePlugin, defineConfig, reportPlugin } from "@hephaestus-build/ichnaea";

import { hephaestus } from "./ichnaea-plugin.ts";

export default defineConfig({
	plugins: [hephaestus, decidePlugin(), reportPlugin()],
	// Ichnaea's decide and report plugins are pre-1.0 (alpha).
	allowStability: ["alpha"],
});
