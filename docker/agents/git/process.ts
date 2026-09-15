import type { ChildProcess } from "node:child_process";

export function exited(child: ChildProcess): Promise<number | null> {
	return new Promise((resolve, reject) => {
		child.once("error", reject);
		child.once("close", resolve);
	});
}

export async function succeeded(child: ChildProcess, failure: string): Promise<void> {
	if ((await exited(child)) !== 0) throw new Error(failure);
}
