/**
 * Stage precompute scripts outside the sandbox, as `pi-precompute.sh` stages them inside it: each
 * script is `<stage>/practices/<slug>.ts` beside a `lib` link, so its `../lib/…` imports resolve.
 */
import { mkdir, mkdtemp, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";

interface StagedPrecompute {
	/** A new folder under the system's temporary folder that holds the stage. */
	root: string;
	/** The folder that holds `practices/` and the `lib` link. */
	stage: string;
	/** The runner's `--practices` folder. */
	practices: string;
}

/** Stage each script source under its slug. */
export async function stagePrecompute(scripts: Record<string, string>): Promise<StagedPrecompute> {
	const root = await mkdtemp(path.join(tmpdir(), "precompute-"));
	const stage = path.join(root, "stage");
	const practices = path.join(stage, "practices");
	await mkdir(practices, { recursive: true });
	await writeFile(path.join(root, "package.json"), '{"type":"module"}\n');
	await symlink(path.join(import.meta.dirname, "lib"), path.join(stage, "lib"));
	for (const [slug, source] of Object.entries(scripts)) {
		await writeFile(path.join(practices, `${slug}.ts`), source);
	}
	return { root, stage, practices };
}
