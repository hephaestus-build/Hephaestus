import { createHash } from "node:crypto";

import type { ExtensionFactory, ModelRuntime } from "@earendil-works/pi-coding-agent";

import { isRecord } from "./pi-observation-normalize.ts";
import type { ProviderConfig } from "./pi-provider.ts";

/** Keep review-local cache accounting separate from native conversation identity. */
export function assessmentCacheExtension(
	config: ProviderConfig,
	runtime: Pick<ModelRuntime, "getModel">,
	prefix: string,
	workspaceId: unknown,
	jobId: unknown,
): ExtensionFactory {
	const known = runtime.getModel("openai", config.modelId ?? "");
	const compat = known?.compat;
	const supported =
		config.apiProtocol === "openai-responses" &&
		known?.api === "openai-responses" &&
		compat !== undefined &&
		"supportsExplicitPromptCacheMode" in compat &&
		compat.supportsExplicitPromptCacheMode === true;
	const identified =
		typeof workspaceId === "number" &&
		Number.isSafeInteger(workspaceId) &&
		workspaceId > 0 &&
		typeof jobId === "string" &&
		/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/iu.test(jobId);
	const key = identified
		? createHash("sha256")
				.update(JSON.stringify([workspaceId, jobId]))
				.digest("hex")
		: null;
	return (pi) => {
		if (!supported || key === null || prefix.length === 0) {
			return;
		}
		pi.on("before_provider_request", ({ payload }) => {
			if (
				!isRecord(payload) ||
				payload.model !== config.modelId ||
				typeof payload.prompt_cache_key !== "string" ||
				!Array.isArray(payload.input)
			) {
				return;
			}
			const index = payload.input.findIndex(
				(item: unknown) => isRecord(item) && item.role === "user",
			);
			const message: unknown = payload.input[index];
			if (!isRecord(message) || !Array.isArray(message.content) || message.content.length !== 1) {
				return;
			}
			const block: unknown = message.content[0];
			if (
				!isRecord(block) ||
				block.type !== "input_text" ||
				typeof block.text !== "string" ||
				!block.text.startsWith(prefix) ||
				block.text.length <= prefix.length ||
				"prompt_cache_breakpoint" in block
			) {
				return;
			}
			// Split the constructed user text after shared work, preserving its role and exact text.
			const content = [
				{ ...block, text: prefix, prompt_cache_breakpoint: { mode: "explicit" } },
				{ ...block, text: block.text.slice(prefix.length) },
			];
			return {
				...payload,
				prompt_cache_key: key,
				input: payload.input.map((item: unknown, at: number) =>
					at === index ? { ...message, content } : item,
				),
			};
		});
	};
}
