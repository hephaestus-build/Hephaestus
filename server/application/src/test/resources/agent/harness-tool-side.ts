// The Pod's other sides for a runner under test: a real tools program on a real socket, and the change view that the
// workspace initializer derives before the runner starts.

import { once } from "node:events";
import { mkdirSync, mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";

import { startToolsServer } from "../../../main/resources/agent/tools-server.ts";

const NONCE = "harness-nonce";

const PROGRAM_ENTRY = path.resolve(
	import.meta.dirname,
	"../../../../../../docker/agents/practice-scripts/child.ts",
);

/** Call in the runner's process, after its mocks and before it starts. */
export async function startHarnessSides(cwd: string): Promise<void> {
	const { deriveChangeView } = await import("../../../main/resources/agent/pi-change-view.ts");
	deriveChangeView(cwd);
	const home = mkdtempSync(path.join(tmpdir(), "harness-tools-"));
	const workspace = path.join(home, "workspace");
	const scratch = path.join(workspace, ".scratch");
	mkdirSync(scratch, { recursive: true });
	const socket = path.join(home, "tools.sock");
	const server = startToolsServer({
		address: { path: socket },
		nonce: NONCE,
		workspace,
		roots: [cwd, workspace],
		scratch,
		putRoots: [scratch],
		scriptEntry: PROGRAM_ENTRY,
		soleUser: false,
	});
	await once(server, "listening");
	server.unref();
	process.env.TOOLS_SOCKET = socket;
	process.env.PROGRAMS_SOCKET = socket;
	process.env.TOOLS_NONCE = NONCE;
}
