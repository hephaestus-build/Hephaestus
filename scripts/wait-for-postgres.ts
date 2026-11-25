import process from "node:process";

import { waitForFinalServer } from "./lib/postgres-container.ts";

const [container] = process.argv.slice(2);
if (container === undefined) {
	throw new Error("Usage: node scripts/wait-for-postgres.ts <container>");
}
await waitForFinalServer(container);
