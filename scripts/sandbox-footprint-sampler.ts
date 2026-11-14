import { existsSync, readFileSync } from "node:fs";

// Runs as root in the host's PID and cgroup namespaces; reads only. One JSON line per container per tick.
// Argument: the tick in milliseconds.
const read = (file: string) => {
	try {
		return readFileSync(file, "utf8");
	} catch {
		return null;
	}
};
const paths = new Map<string, string>();
function find(id: string) {
	const known = paths.get(id);
	if (known !== undefined) {
		return known;
	}
	const roots = [`/sys/fs/cgroup/docker/${id}`, `/sys/fs/cgroup/system.slice/docker-${id}.scope`];
	const found = roots.find((candidate) => existsSync(candidate)) ?? null;
	if (found !== null) {
		paths.set(id, found);
	}
	return found;
}
const counters = (text: string) => {
	const values = new Map<string, number>();
	for (const line of text.trim().split("\n")) {
		const [key, value] = line.split(" ");
		if (key !== undefined) {
			values.set(key, Number(value));
		}
	}
	return values;
};

setInterval(() => {
	const t = Date.now();
	const ids = (read("/bench/ids") ?? "").split("\n").filter(Boolean);
	for (const line of ids) {
		const [label, id] = line.split(" ");
		const cg = find(id ?? "");
		if (cg === null) {
			continue;
		}
		// A container that exits between these reads takes its cgroup with it: skip that tick.
		const current = read(`${cg}/memory.current`);
		const memoryStat = read(`${cg}/memory.stat`);
		const cpuStat = read(`${cg}/cpu.stat`);
		if (current === null || memoryStat === null || cpuStat === null) {
			continue;
		}
		const stat = counters(memoryStat);
		const cpu = counters(cpuStat);
		const procs = (read(`${cg}/cgroup.procs`) ?? "")
			.split("\n")
			.filter(Boolean)
			.map((pid) => {
				const status = read(`/proc/${pid}/status`) ?? "";
				const kb = (key: string) =>
					Number(new RegExp(String.raw`^${key}:\s+(\d+)`, "mu").exec(status)?.[1] ?? 0) * 1024;
				const cmd = (read(`/proc/${pid}/cmdline`) ?? "")
					.split("\0")
					.filter(Boolean)
					.filter((arg) => !arg.startsWith("--") && arg !== "/usr/bin/prlimit")
					.map((arg) => arg.split("/").pop())
					.slice(0, 2)
					.join(" ");
				return {
					pid: Number(pid),
					cmd,
					rss: kb("VmRSS"),
					hwm: kb("VmHWM"),
					anon: kb("RssAnon"),
					file: kb("RssFile"),
					shmem: kb("RssShmem"),
				};
			});
		process.stdout.write(
			`${JSON.stringify({
				t,
				label,
				current: Number(current),
				peak: Number(read(`${cg}/memory.peak`) ?? 0),
				anon: stat.get("anon"),
				file: stat.get("file"),
				shmem: stat.get("shmem"),
				kernel: stat.get("kernel"),
				sock: stat.get("sock"),
				activeFile: stat.get("active_file"),
				inactiveFile: stat.get("inactive_file"),
				cpuUsec: cpu.get("usage_usec"),
				throttledUsec: cpu.get("throttled_usec"),
				pids: Number(read(`${cg}/pids.current`) ?? 0),
				procs,
			})}\n`,
		);
	}
}, Number(process.argv[2]));
