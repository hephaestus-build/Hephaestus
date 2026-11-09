// driver-kinds.ts — the runner of each run kind that a sandbox drives, by the server's AgentRunKind name. A kind joins
// here together with its server side (`SandboxRunKind`), so the gateway never offers a kind that no runner takes.

import type { KindRunner } from "./driver.ts";

export const KINDS: Readonly<Record<string, KindRunner>> = {};
