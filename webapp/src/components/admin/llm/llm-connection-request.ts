import type {
	CreateLlmConnectionRequest,
	CreateWorkspaceLlmConnectionRequest,
	UpdateLlmConnectionRequest,
	UpdateWorkspaceLlmConnectionRequest,
} from "@/api/types.gen";
import type { AiConnectionPlatform } from "@/components/icons/ai-connection-platform-logos";

import type { LlmConnectionFieldsValue } from "./LlmConnectionFields";

/** Both consoles send the same connection requests, so one body satisfies both generated types. */
type ConnectionCreateBody = CreateLlmConnectionRequest & CreateWorkspaceLlmConnectionRequest;
type ConnectionUpdateBody = UpdateLlmConnectionRequest & UpdateWorkspaceLlmConnectionRequest;

/** A new connection starts inactive in both consoles. */
export function connectionCreateBodyOf(fields: LlmConnectionFieldsValue): ConnectionCreateBody {
	return {
		displayName: fields.displayName.trim(),
		baseUrl: fields.baseUrl.trim(),
		apiProtocol: fields.apiProtocol,
		authMode: fields.authMode,
		connectionPlatform: fields.connectionPlatform,
		apiKey: fields.apiKey.trim() || undefined,
		enabled: false,
	};
}

/** The endpoint is immutable once created, so an edit never sends it. A blank key field keeps the
 * stored key. */
export function connectionUpdateBodyOf(
	fields: LlmConnectionFieldsValue,
	saved: { connectionPlatform?: AiConnectionPlatform },
): ConnectionUpdateBody {
	const body: ConnectionUpdateBody = { displayName: fields.displayName.trim() };
	if (fields.connectionPlatform) {
		body.connectionPlatform = fields.connectionPlatform;
	} else if (saved.connectionPlatform) {
		body.clearConnectionPlatform = true;
	}
	if (fields.apiKey.trim()) {
		body.apiKey = fields.apiKey.trim();
	}
	if (fields.clearApiKey) {
		body.clearApiKey = true;
	}
	return body;
}
