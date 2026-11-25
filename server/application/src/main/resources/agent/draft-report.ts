import { mkdir } from "node:fs/promises";

import { ModelRuntime, SessionManager } from "@earendil-works/pi-coding-agent";

import { isRecord } from "./conversation-host-protocol.ts";
import type { KindRunner } from "./driver.ts";
import { asProviderConfig, reasoningSetting, registerRunProvider } from "./pi-provider.ts";
import { openRunSession } from "./pi-session.ts";
import { AGENT_DIR, WORKSPACE } from "./sandbox-paths.ts";

/** Runs one tool-free draft. The server owns disclosure, model admission and every use of the answer. */
export const draftReport: KindRunner = async ({ assignment, gateway, signal }) => {
	const url = `${gateway.url}/internal/agent/runs/${assignment.runId}`;
	const response = await fetch(`${url}/context`, { headers: gateway.headers, signal });
	if (!response.ok) {
		await response.body?.cancel();
		if ([401, 404, 409].includes(response.status)) {
			return { leaseLost: true };
		}
		return { leaseLost: false, outcome: "handed_on" };
	}
	const context: unknown = await response.json();
	if (
		!isRecord(context) ||
		typeof context.system !== "string" ||
		typeof context.prompt !== "string" ||
		typeof context.instanceModelId !== "string" ||
		!/^[1-9][0-9]*$/u.test(context.instanceModelId)
	) {
		return { leaseLost: false, outcome: "completed", cause: "invalid_context" };
	}
	const config = asProviderConfig(context.model);
	if (
		config === null ||
		(config.apiProtocol !== "anthropic-messages" &&
			config.apiProtocol !== "openai-completions" &&
			config.apiProtocol !== "openai-responses") ||
		config.maxOutputTokens === undefined
	) {
		return { leaseLost: false, outcome: "completed", cause: "invalid_context" };
	}

	await Promise.all([mkdir(WORKSPACE, { recursive: true }), mkdir(AGENT_DIR, { recursive: true })]);
	const modelRuntime = await ModelRuntime.create({
		authPath: `${AGENT_DIR}/auth.json`,
		modelsPath: null,
		allowModelNetwork: false,
	});
	if (
		!registerRunProvider(modelRuntime, config, {
			...gateway,
			headers: { ...gateway.headers, "x-draft-model": context.instanceModelId },
		})
	) {
		return { leaseLost: false, outcome: "completed", cause: "invalid_context" };
	}
	const model = modelRuntime.getModel("hephaestus", config.modelId);
	if (model === undefined) {
		return { leaseLost: false, outcome: "completed", cause: "invalid_context" };
	}

	const manager = SessionManager.inMemory(WORKSPACE);
	const { session } = await openRunSession(
		{ cwd: WORKSPACE, agentDir: AGENT_DIR },
		{
			modelRuntime,
			model,
			thinkingLevel: reasoningSetting(config.reasoningEffort).thinkingLevel,
			systemPrompt: context.system,
			tools: [],
			customTools: [],
			extensions: [],
			// Each lease can bill one call. A retry needs a fresh lease under the same run deadline.
			settings: {
				compaction: { enabled: false },
				retry: { enabled: false, provider: { maxRetries: 0 } },
			},
			sessionManager: manager,
		},
	);
	const stopped = () => signal.aborted;
	async function stopSession() {
		try {
			await session.abort();
		} catch {
			// The claim no longer holds, so this session cannot submit a result.
		}
	}
	let stopping: Promise<void> | undefined;
	const abort = () => {
		stopping = stopSession();
	};
	signal.addEventListener("abort", abort, { once: true });
	try {
		if (stopped()) {
			return { leaseLost: true };
		}
		await session.prompt(context.prompt);
		await session.waitForIdle();
		if (stopped()) {
			return { leaseLost: true };
		}
		const entry = manager.getEntries().at(-1);
		const answer =
			entry?.type === "message" && entry.message.role === "assistant" ? entry.message : null;
		if (answer?.stopReason !== "stop") {
			const error = answer?.errorMessage;
			if (error?.includes("draft_capacity:") === true) {
				return { leaseLost: false, outcome: "handed_on", cause: "model_capacity" };
			}
			if (error?.includes("draft_deadline:") === true) {
				return { leaseLost: false, outcome: "completed", cause: "model_timeout" };
			}
			if (error?.includes("draft_quota:") === true) {
				return { leaseLost: false, outcome: "completed", cause: "quota_exhausted" };
			}
			if (
				error?.includes("draft_unavailable:") === true ||
				error?.includes("upstream_stalled:") === true ||
				error?.includes("upstream_paused:") === true
			) {
				return { leaseLost: false, outcome: "handed_on", cause: "model_unavailable" };
			}
			return { leaseLost: false, outcome: "completed", cause: "model_failed" };
		}
		const text = answer.content
			.flatMap((part) => (part.type === "text" ? [part.text] : []))
			.join("");
		if (text.trim() === "") {
			return { leaseLost: false, outcome: "handed_on", cause: "model_unavailable" };
		}
		return { leaseLost: false, outcome: "completed", answer: text };
	} catch (error) {
		if (stopped()) {
			return { leaseLost: true };
		}
		throw error;
	} finally {
		signal.removeEventListener("abort", abort);
		await stopping;
		session.dispose();
	}
};
