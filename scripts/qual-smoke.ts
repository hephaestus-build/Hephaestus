/**
 * The review smoke of a release candidate on staging: start reviews of fixture pull requests with the session-free
 * trigger (lib/qual-trigger.ts) and exit 0 when each review started. The caller then checks the delivered feedback.
 *
 *   node scripts/qual-staging-hold.ts -- node scripts/qual-smoke.ts --target staging --fixture <file> [--count 1] [--label <text>]
 */
import { parseArgs } from "node:util";

import { COMMON, commonFrom } from "./lib/qual-args.ts";
import { Evidence } from "./lib/qual-evidence.ts";
import { readFixture, startReviews } from "./lib/qual-trigger.ts";

const USAGE =
	"node scripts/qual-smoke.ts --target staging --fixture <file> [--count 1] [--label <text>]";
const { values } = parseArgs({
	options: {
		...COMMON,
		fixture: { type: "string" },
		count: { type: "string", default: "1" },
		label: { type: "string", default: "smoke" },
	},
});
const common = commonFrom(values, USAGE);
if (values.fixture === undefined) {
	throw new Error(`--fixture is required\n${USAGE}`);
}
const count = Number(values.count);
if (!Number.isSafeInteger(count) || count < 1) {
	throw new Error("--count must be a positive integer");
}

const evidence = new Evidence(common.out, `smoke-${common.target}`);
const started = await startReviews(
	common.cluster,
	evidence,
	readFixture(values.fixture),
	count,
	values.label,
);
for (const review of started) {
	evidence.log(`${review.repo}#${review.pr} commit ${review.commit} started=${review.started}`);
}
process.exitCode = started.length > 0 && started.every((review) => review.started) ? 0 : 1;
