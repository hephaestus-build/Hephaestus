/** Stop only the child recorded for this isolated driver's run, including before it starts. */
import { parseArgs } from "node:util";

import { stopCapacityChild } from "./lib/capacity-child.ts";

const { values } = parseArgs({ options: { label: { type: "string" } } });
if (values.label === undefined || !/^[a-z0-9-]+$/u.test(values.label)) {
	throw new Error("Supply --label");
}
await stopCapacityChild("/bench", values.label);
