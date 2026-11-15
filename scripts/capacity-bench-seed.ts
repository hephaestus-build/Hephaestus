/** TEST ONLY. Provision synthetic sessions from the driver in the isolated server Pod. */
import { readFile, writeFile } from "node:fs/promises";

import { bindReplayModel, syntheticApi, syntheticSession } from "./lib/capacity-seed.ts";
import { asStringArray, parseJson } from "./lib/json.ts";

const base = "http://127.0.0.1:8080/api";
const workspace = "capacity-bench-course";
const sessions: string[] = [];
let admin = "";
const users = Number(process.env.CAPACITY_USERS ?? "40");
if (!Number.isSafeInteger(users) || users < 1 || users > 120) {
	throw new Error("CAPACITY_USERS must be 1..120");
}

for (let index = 0; process.env.CAPACITY_SEED_MODE !== "models" && index < users; index += 1) {
	const token = await syntheticSession(
		base,
		`capacity-bench-${String(index).padStart(3, "0")}`,
		index === 0,
	);
	sessions.push(token);
	if (index === 0) {
		admin = token;
	}
}

if (process.env.CAPACITY_SEED_MODE === "models") {
	sessions.push(
		...asStringArray(parseJson(await readFile("/bench/auth-tokens.json", "utf8")), "sessions"),
	);
	admin = sessions[0] ?? "";
}

await writeFile("/bench/auth-tokens.json", JSON.stringify(sessions), { mode: 0o600 });
await writeFile(
	"/bench/seed-count.json",
	JSON.stringify({ syntheticSessions: sessions.length, workspace }),
);
if (process.env.CAPACITY_SEED_MODE === "sessions") {
	process.stdout.write(
		`${users} synthetic sessions provisioned; credentials stored only in /bench\n`,
	);
} else {
	await bindReplayModel(
		syntheticApi(base, admin),
		workspace,
		"http://replay.capacity-bench-20261030.svc.cluster.local:8080/v1",
		300,
	);
	process.stdout.write("Synthetic replay model bound to both purposes in the isolated workspace\n");
}
