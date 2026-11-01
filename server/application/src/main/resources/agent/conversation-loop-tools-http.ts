import { isIP } from "node:net";

import { responseJson } from "./conversation-loop-http.ts";
import { asLoadAnswer, isRecord } from "./conversation-loop-protocol.ts";
import type { ConversationToolGateway, ToolsArchive } from "./conversation-loop-tools.ts";
import { ToolClient, type PutArchive } from "./tool-client.ts";
import { LIMITS } from "./tool-protocol.ts";

/** The source is a credentialed gateway response. The tools side receives its bytes and digest only. */
function archive(response: Response): PutArchive {
	const size = Number(response.headers.get("Content-Length"));
	const sha256 = response.headers.get("X-Archive-Sha256");
	if (
		!Number.isSafeInteger(size) ||
		size < 0 ||
		size > LIMITS.putBytes ||
		sha256 === null ||
		!/^[a-f0-9]{64}$/u.test(sha256) ||
		response.body === null
	) {
		void response.body?.cancel();
		throw new Error("The gateway sent malformed archive metadata");
	}
	const { body } = response;
	return {
		size,
		sha256,
		bytes: (async function* () {
			let received = 0;
			const reader = body.getReader();
			try {
				for (;;) {
					const part = await reader.read();
					if (part.done) {
						break;
					}
					received += part.value.byteLength;
					if (received > size) {
						throw new Error("The gateway archive exceeds its announced size");
					}
					yield Buffer.from(part.value);
				}
				if (received !== size) {
					throw new Error("The gateway archive ended before its announced size");
				}
			} finally {
				await reader.cancel().catch(() => undefined);
				reader.releaseLock();
			}
		})(),
	};
}

export function toolsGateway(
	request: (method: string, path: string, body: unknown, signal: AbortSignal) => Promise<Response>,
): ConversationToolGateway {
	return {
		async document(path, signal) {
			const response = await request("POST", "documents/read", { path }, signal);
			if (response.status === 404) {
				await response.body?.cancel();
				return null;
			}
			const generation = response.headers.get("X-Context-Generation");
			const value = await responseJson(response);
			if (!isRecord(value) || typeof value.text !== "string" || generation === null) {
				throw new Error("The gateway sent a malformed context document");
			}
			return { text: value.text, generation };
		},
		async connect(signal) {
			const response = await request("POST", "tools", undefined, signal);
			const value = await responseJson(response, 8192);
			if (
				!isRecord(value) ||
				typeof value.host !== "string" ||
				isIP(value.host) === 0 ||
				typeof value.port !== "number" ||
				!Number.isSafeInteger(value.port) ||
				value.port < 1 ||
				value.port > 65_535 ||
				typeof value.nonce !== "string" ||
				value.nonce.length < 32 ||
				typeof value.generation !== "string"
			) {
				throw new Error("The gateway sent a malformed tools endpoint");
			}
			return {
				client: await ToolClient.open({ host: value.host, port: value.port }, value.nonce),
				generation: value.generation,
			};
		},
		async documents(signal) {
			const response = await request("GET", "documents/archive", undefined, signal);
			const generation = response.headers.get("X-Context-Generation");
			if (generation === null) {
				await response.body?.cancel();
				throw new Error("The gateway sent no context generation");
			}
			return { archive: archive(response), generation };
		},
		async load(path, signal) {
			const response = await request("POST", "documents/load", { path }, signal);
			if (response.headers.get("Content-Type")?.startsWith("application/json") === true) {
				const refusal = asLoadAnswer(await responseJson(response));
				if (refusal === null || refusal.ok) {
					throw new Error("The gateway sent a malformed load refusal");
				}
				return refusal;
			}
			const metadata = response.headers.get("X-Tools-Load");
			if (metadata === null || Buffer.byteLength(metadata) > 12_000) {
				await response.body?.cancel();
				throw new Error("The gateway sent no bounded load metadata");
			}
			let value: unknown;
			try {
				value = JSON.parse(Buffer.from(metadata, "base64url").toString("utf8"));
			} catch {
				await response.body?.cancel();
				throw new Error("The gateway sent invalid load metadata");
			}
			const result = asLoadAnswer(value);
			if (result === null || !result.ok || !isRecord(value) || !Array.isArray(value.summary)) {
				await response.body?.cancel();
				throw new Error("The gateway sent malformed load metadata");
			}
			const summary: string[] = [];
			for (const item of value.summary) {
				if (typeof item !== "string" || item.length > 4096 || summary.length >= 32) {
					await response.body?.cancel();
					throw new Error("The gateway sent a malformed summary command");
				}
				summary.push(item);
			}
			return {
				archive: archive(response),
				directory: result.path,
				result,
				summary,
			} satisfies ToolsArchive;
		},
	};
}
