import { repositoryCli, run } from "./lib/process.ts";

// Validate against the installed SDK's bundledNativeModules.json. Expo's online recommendation map
// moves independently of the lockfile and can require patches still inside our release-age window.
// Dependency upgrades remain explicit, and native builds verify the chosen runtime on both platforms.
await run(
	process.execPath,
	[repositoryCli(), "-C", "mobile", "exec", "expo", "install", "--check"],
	{
		env: { EXPO_OFFLINE: "1" },
	},
);
