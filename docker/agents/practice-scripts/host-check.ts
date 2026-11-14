import { createAgentSession } from "@earendil-works/pi-coding-agent";

import { parseCandidate } from "./lib/core/contract.js";
import { BUILTIN_SCRIPT, runPracticeProgram } from "./lib/core/program-host.js";

// The Pi agent image runs this file as `.js` with Node's TypeScript loader off, as the runner loads
// the compiled toolkit: keep it free of type syntax. Loading the host beside the Pi SDK proves that
// the toolkit and its own dependencies resolve together.
if (typeof createAgentSession !== "function" || typeof runPracticeProgram !== "function") {
	throw new Error("the host cannot load the Pi SDK and the toolkit together");
}
if (typeof BUILTIN_SCRIPT.run !== "function" || parseCandidate({}).ok) {
	throw new Error("the toolkit contract does not load");
}
