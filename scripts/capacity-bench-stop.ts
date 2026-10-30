/** Stop only synthetic turns in the isolated release, through the normal owner endpoint. */
import { readFile } from "node:fs/promises";

import { stopSyntheticTurns } from "./lib/capacity-stop.ts";
import { asStringArray, parseJson } from "./lib/json.ts";

const base = "http://127.0.0.1:8080/workspaces/capacity-bench-course/mentor";
const tokens = asStringArray(
	parseJson(await readFile("/bench/auth-tokens.json", "utf8")),
	"sessions",
);
const result = await stopSyntheticTurns(base, tokens);
process.stdout.write(`${JSON.stringify(result)}\n`);
process.exitCode = result.failures === 0 && result.remaining === 0 ? 0 : 1;
