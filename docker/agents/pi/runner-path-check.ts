import { readFileSync } from "node:fs";

import {
	DefaultResourceLoader,
	ModelRuntime,
	SettingsManager,
} from "@earendil-works/pi-coding-agent";

// Resolves resources the way the runner does: an untrusted project, so the SDK never walks the
// ancestors of the working directory for skills; context-file discovery off; and the staged
// orchestrator handed over by name. The working directory sits below ancestors beside a decoy
// AGENTS.md. The build fails if the SDK discovers the decoy, or if the loader carries anything
// but the staged orchestrator.
const cwd = "/tmp/abi-check/work/nested";
const agentDir = "/tmp/abi-check/agent";
const settingsManager = SettingsManager.create(cwd, agentDir, { projectTrusted: false });
const orchestratorPath = `${agentDir}/AGENTS.md`;
const orchestrator = readFileSync(orchestratorPath, "utf8");
const loader = new DefaultResourceLoader({
	cwd,
	agentDir,
	settingsManager,
	noContextFiles: true,
	agentsFilesOverride: (base) => {
		// The decoy must not be in the base list: an SDK that discovers context files with discovery off is the regression this gate exists for.
		if (base.agentsFiles.length > 0) {
			throw new Error(
				`context files were discovered with discovery off: ${JSON.stringify(base.agentsFiles.map((f) => f.path))}`,
			);
		}
		return { agentsFiles: [{ path: orchestratorPath, content: orchestrator }] };
	},
});
await loader.reload();
const { agentsFiles } = loader.getAgentsFiles();
if (
	agentsFiles.length !== 1 ||
	agentsFiles[0]?.content.includes("SENTINEL-ORCHESTRATOR") !== true
) {
	throw new Error(
		`expected the staged orchestrator alone, got ${JSON.stringify(agentsFiles.map((f) => f.path))}`,
	);
}

// Checking a credential takes a lock directory beside auth.json, which the SDK creates on first
// use along with its models store: writes in the agent dir that the runner grants and that a
// model turn needs. Only settings.json is staged, as the server stages it.
const modelRuntime = await ModelRuntime.create({
	authPath: `${agentDir}/auth.json`,
	modelsPath: `${agentDir}/models.json`,
	allowModelNetwork: false,
});
modelRuntime.registerProvider("gate", {
	name: "gate",
	baseUrl: "http://127.0.0.1:9/v1",
	apiKey: "$GATE_TOKEN",
	authHeader: true,
	api: "openai-responses",
	models: [
		{
			id: "model",
			name: "model",
			reasoning: false,
			input: ["text"],
			cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
			contextWindow: 1000,
			maxTokens: 100,
		},
	],
});
if (!(await modelRuntime.checkAuth("gate"))) {
	throw new Error("the credential of the gate provider did not resolve");
}
