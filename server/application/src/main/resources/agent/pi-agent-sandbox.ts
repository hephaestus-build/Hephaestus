// pi-agent-sandbox.ts — the sandbox trust posture every Pi session in this image runs under.
// Shared by the practice runner (pi-runner.ts) and the mentor runner (pi-mentor-runner.ts): both
// execute the same vendored SDK inside the same container at the same working directory, so a
// trust or discovery option set correctly in one and left at its default in the other reopens the
// crash the fix removed for the other.

import type { SettingsManagerCreateOptions } from "@earendil-works/pi-coding-agent";

/**
 * Untrusted on purpose: a trusted project makes the SDK look for `.agents/skills` in every ancestor
 * of the working directory, up to `/`, and the sandbox lets Node read only /workspace and the SDK
 * itself (PiRunnerProfile), so that walk dies on the first ancestor with a permission error before
 * the model is ever called. Nothing project-local is wanted here anyway: every resource a session
 * uses is injected by its runner, never discovered from a checkout.
 */
export const SANDBOX_SETTINGS_MANAGER_OPTIONS: SettingsManagerCreateOptions = {
	projectTrusted: false,
};

/**
 * The SDK's own context-file discovery climbs from the working directory to `/` looking for
 * AGENTS.md and dies on the first ancestor the sandbox's read allowlist forbids; and the SDK's
 * discovery is not the channel a session's instructions should arrive on — each runner hands its
 * own instructions over by name instead.
 */
export const SANDBOX_RESOURCE_LOADER_OPTIONS = {
	noContextFiles: true,
};
