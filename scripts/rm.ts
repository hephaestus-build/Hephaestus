import { rm } from "node:fs/promises";

// `rm -rf` for a task command: portable, and typed like every other repository script.
for (const path of process.argv.slice(2)) {
	await rm(path, { recursive: true, force: true });
}
