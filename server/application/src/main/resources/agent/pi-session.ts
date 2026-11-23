// pi-session.ts — the one factory that builds every Pi session in this image (openRunSession).
//
// A run hands it a run descriptor: the model and its runtime, the system prompt, the tools, the kind's extensions and
// the settings. The factory adds nothing that a run did not name. It loads no context file, skill, prompt template,
// theme or extension, it reads no settings file, and the system prompt is the descriptor's alone. Only this file calls
// `createAgentSession` (pi-session.spec.ts).

import type { Api, Model } from "@earendil-works/pi-ai";
import {
	createAgentSession,
	type CreateAgentSessionResult,
	DefaultResourceLoader,
	type ExtensionFactory,
	type ModelRuntime,
	type SessionManager,
	SettingsManager,
	type ToolDefinition,
} from "@earendil-works/pi-coding-agent";

import {
	SANDBOX_RESOURCE_LOADER_OPTIONS,
	SANDBOX_SETTINGS_MANAGER_OPTIONS,
} from "./pi-agent-sandbox.ts";

type SessionOptions = NonNullable<Parameters<typeof createAgentSession>[0]>;

/** What one session of a run is built from. Its model runtime holds the run's claim, so no other run shares it. */
export interface RunDescriptor {
	modelRuntime: ModelRuntime;
	model: Model<Api>;
	thinkingLevel: SessionOptions["thinkingLevel"];
	systemPrompt: string;
	/** The tools the session enables, by name: the built-in tools it keeps and its own tools. */
	tools: string[];
	customTools: ToolDefinition[];
	/** The run kind's extensions, such as its compaction hook. */
	extensions: ExtensionFactory[];
	/** Compaction, retry and transport. The factory keeps them in memory and turns cache warming off. */
	settings: NonNullable<Parameters<typeof SettingsManager.inMemory>[0]>;
	/** Where the session's entries go. The run kind sends them to the server. */
	sessionManager: SessionManager;
}

/** The sandbox directories a session works in. */
export interface RunPlace {
	cwd: string;
	agentDir: string;
}

/** Builds a run's session from its descriptor. */
export async function openRunSession(
	place: RunPlace,
	descriptor: RunDescriptor,
): Promise<CreateAgentSessionResult> {
	// Pi warms a prompt cache only for a model with a cache lifetime and a price, which no proxied model declares. Off
	// keeps a later model setting from starting model calls that no run asked for.
	const settingsManager = SettingsManager.inMemory(
		{ ...descriptor.settings, cacheWarming: "off" },
		SANDBOX_SETTINGS_MANAGER_OPTIONS,
	);
	const resourceLoader = new DefaultResourceLoader({
		...place,
		settingsManager,
		...SANDBOX_RESOURCE_LOADER_OPTIONS,
		agentsFilesOverride: () => ({ agentsFiles: [] }),
		systemPrompt: descriptor.systemPrompt,
		systemPromptOverride: () => descriptor.systemPrompt,
		appendSystemPrompt: [],
		extensionFactories: descriptor.extensions,
	});
	await resourceLoader.reload();
	return createAgentSession({
		...place,
		modelRuntime: descriptor.modelRuntime,
		model: descriptor.model,
		thinkingLevel: descriptor.thinkingLevel,
		tools: descriptor.tools,
		customTools: descriptor.customTools,
		resourceLoader,
		sessionManager: descriptor.sessionManager,
		settingsManager,
	});
}
