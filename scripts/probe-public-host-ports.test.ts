import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath, pathToFileURL } from "node:url";

import { parse } from "yaml";

import { asArray, asRecord, asString } from "./lib/json.ts";
import { CAPTURE_LIMIT_BYTES } from "./lib/process.ts";
import { assessScan, probeTargets } from "./probe-public-host-ports.ts";

function report(ports: string[] = [], aggregateState = "filtered") {
	return `<?xml version="1.0"?>
<nmaprun scanner="nmap">
<scaninfo type="syn" protocol="tcp" numservices="65535" services="1-65535"/>
<scaninfo type="udp" protocol="udp" numservices="65535" services="1-65535"/>
<host>
<ports><extraports state="${aggregateState}" count="${131_070 - ports.length}">
</extraports>
${ports.join("\n")}
</ports>
</host>
<runstats><finished time="1" exit="success"/><hosts up="1" down="0" total="1"/></runstats>
</nmaprun>`;
}

function port(protocol: "tcp" | "udp", number: number, state = "open", reason = "response") {
	return `<port protocol="${protocol}" portid="${number}"><state state="${state}" reason="${reason}"/></port>`;
}

const online = { expectedOnline: true, http3: false };
const down = { ...online, expectedOnline: false };

void test("probes both address families and validates optional host settings", () => {
	const host = { ipv4: "192.0.2.3", ipv6: "2001:0db8::3" };
	assert.deepEqual(probeTargets([host]), [{ addresses: ["192.0.2.3", "2001:db8::3"], ...online }]);
	assert.equal(probeTargets([{ ...host, expectedOnline: false }])[0]?.expectedOnline, false);
	assert.equal(probeTargets([{ ...host, http3: true }])[0]?.http3, true);
	assert.throws(() => probeTargets([]));
	assert.throws(() => probeTargets([{ ...host, expectedOnline: "false" }]));
	assert.throws(() => probeTargets([{ ...host, http3: "false" }]));
	assert.throws(() => probeTargets([{ ...host, ipv6: "example.test" }]));
	assert.throws(() => probeTargets([host, { ...host, ipv4: "192.0.2.4", ipv6: "2001:db8::3" }]));
});

void test("fails on unexpected responses, including TCP resets and UDP port unreachable", () => {
	assert.deepEqual(assessScan(report([port("tcp", 80), port("tcp", 443, "closed")]), online), {
		status: "reachable",
		hiddenReplies: false,
		unexpected: [],
	});
	assert.deepEqual(
		assessScan(report([port("tcp", 10_250), port("udp", 8472, "closed"), port("udp", 80)]), online)
			.unexpected,
		["10250/tcp", "8472/udp", "80/udp"],
	);
	assert.deepEqual(
		assessScan(report([port("tcp", 443, "closed"), port("udp", 8472, "open|filtered")]), online)
			.unexpected,
		[],
	);
	assert.equal(assessScan(report([], "closed"), online).hiddenReplies, true);
});

void test("accepts UDP 443 only for an HTTP/3 target", () => {
	const scan = report([port("tcp", 443), port("udp", 443)]);
	assert.deepEqual(assessScan(scan, online).unexpected, ["443/udp"]);
	assert.deepEqual(assessScan(scan, down).unexpected, ["443/udp"]);
	assert.deepEqual(assessScan(scan, { ...online, http3: true }).unexpected, []);
});

void test("ICMP prohibition is a reply even when Nmap reports the port as filtered", () => {
	const denied = port("udp", 8472, "filtered", "admin-prohibited");
	const silent = port("tcp", 6443, "filtered", "no-response");
	const deniedV6 = port("tcp", 10_250, "filtered", "icmp6-adm-prohibited");
	assert.deepEqual(assessScan(report([denied, silent, deniedV6]), down).unexpected, [
		"8472/udp",
		"10250/tcp",
	]);
	const grouped = report([]).replace(
		"</extraports>",
		'<extrareasons reason="no-response" count="131069"/><extrareasons reason="admin-prohibited" count="1"/></extraports>',
	);
	assert.equal(assessScan(grouped, down).hiddenReplies, true);
	assert.equal(
		assessScan(
			report([]).replace(
				"</extraports>",
				'<extrareasons reason="no-response" count="131070"/></extraports>',
			),
			down,
		).hiddenReplies,
		false,
	);
});

void test("a complete silent scan contains no responding ports", () => {
	assert.deepEqual(assessScan(report([], "open|filtered"), down), {
		status: "unreachable",
		hiddenReplies: false,
		unexpected: [],
	});
	assert.deepEqual(assessScan(report([port("tcp", 22)]), down).unexpected, ["22/tcp"]);
});

for (const family of ["ipv4", "ipv6"]) {
	void test(`an ICMP routing failure in the captured ${family} scan is neither silence nor a port reply`, () => {
		const capture = readFileSync(
			new URL(`fixtures/public-port-probe-routing-${family}.xml`, import.meta.url),
			"utf8",
		);
		for (const target of [online, down]) {
			assert.deepEqual(assessScan(capture, target), {
				status: "routing-failure",
				hiddenReplies: false,
				unexpected: [],
			});
		}
	});
}

void test("the CLI and workflow fail for both routing captures even during an expected outage", (t) => {
	const directory = mkdtempSync(path.join(tmpdir(), "public-port-probe-"));
	t.after(() => rmSync(directory, { recursive: true, force: true }));
	const config = path.join(directory, "hosts.json");
	const preload = path.join(directory, "nmap.mjs");
	const entry = fileURLToPath(new URL("probe-public-host-ports.ts", import.meta.url));
	writeFileSync(config, JSON.stringify([{ ipv4: "192.0.2.3", ipv6: "2001:db8::3", ...down }]));
	writeFileSync(
		preload,
		`import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { mock } from "node:test";
mock.module(${JSON.stringify(new URL("lib/process.ts", import.meta.url).href)}, {
  exports: {
    output(executable, args) {
      assert.equal(executable, "/usr/local/libexec/hephaestus-probe-nmap");
      const family = args.includes("-6") ? "ipv6" : "ipv4";
      const mode = process.env.PROBE_TEST_SCAN;
      if (mode === "cannot-start" || (mode === "ipv6-unavailable" && family === "ipv6")) {
        throw new Error("Nmap could not start.");
      }
      const capture = readFileSync(new URL("public-port-probe-routing-" + family + ".xml",
        ${JSON.stringify(new URL("fixtures/", import.meta.url).href)}), "utf8");
      if (mode === "incomplete") return capture.replace('exit="success"', 'exit="error"');
      return mode === "silent" ? capture.replaceAll("host-unreach", "no-response") : capture;
    },
  },
});
`,
	);
	const preloadUrl = pathToFileURL(preload).href;
	const options = { encoding: "utf8", timeout: 15_000, maxBuffer: CAPTURE_LIMIT_BYTES } as const;
	const cli = spawnSync(
		process.execPath,
		["--experimental-test-module-mocks", "--import", preloadUrl, entry, config],
		options,
	);
	const routeLines = ["Host 1, IPv4: routing failure.", "Host 1, IPv6: routing failure."];
	assert.equal(cli.status, 1, cli.stderr);
	assert.deepEqual(cli.stdout.trim().split("\n"), [
		...routeLines,
		"Public port verification failed. Run the check on the probe host for details.",
	]);

	if (process.platform === "win32") {
		// The workflow runs on Linux and the SSH transport fixture is a POSIX executable.
		return;
	}
	const ssh = path.join(directory, "ssh");
	writeFileSync(
		ssh,
		`#!/bin/sh
exec "$PROBE_TEST_NODE" --experimental-test-module-mocks --import "$PROBE_TEST_PRELOAD" "$PROBE_TEST_ENTRY" "$PROBE_TEST_CONFIG"
`,
		{ mode: 0o700 },
	);
	const workflow = asRecord(
		parse(
			readFileSync(new URL("../.github/workflows/public-host-ports.yml", import.meta.url), "utf8"),
		),
		"workflow",
	);
	const job = asRecord(asRecord(workflow.jobs, "jobs").probe, "probe");
	const step = asRecord(asArray(job.steps, "steps")[0], "probe step");
	const runWorkflow = (overrides: NodeJS.ProcessEnv = {}) =>
		spawnSync("bash", ["-eo", "pipefail", "-c", asString(step.run, "probe script")], {
			...options,
			env: {
				...process.env,
				PATH: directory + path.delimiter + (process.env.PATH ?? ""),
				PROBE_HOST: "probe.invalid",
				PROBE_KEY: "test key",
				PROBE_KNOWN_HOSTS: "test host key",
				PROBE_TEST_NODE: process.execPath,
				PROBE_TEST_PRELOAD: preloadUrl,
				PROBE_TEST_ENTRY: entry,
				PROBE_TEST_CONFIG: config,
				...overrides,
			},
		});
	const run = runWorkflow();
	assert.equal(run.status, 1, run.stderr);
	assert.deepEqual(run.stdout.trim().split("\n"), [
		...routeLines,
		"The external probe reported a failed check.",
	]);
	const invalidConfig = path.join(directory, "invalid.json");
	writeFileSync(invalidConfig, "invalid JSON");
	for (const overrides of [
		{ PROBE_TEST_CONFIG: path.join(directory, "missing.json") },
		{ PROBE_TEST_CONFIG: invalidConfig },
		{ PROBE_TEST_SCAN: "cannot-start" },
		{ PROBE_TEST_SCAN: "incomplete" },
	]) {
		const unavailable = runWorkflow(overrides);
		assert.equal(unavailable.status, 2, unavailable.stderr);
		assert.equal(unavailable.stdout.trim(), "Public port probe unavailable.");
	}
	const failedWithMissingFamily = runWorkflow({ PROBE_TEST_SCAN: "ipv6-unavailable" });
	assert.equal(failedWithMissingFamily.status, 1);
	assert.deepEqual(failedWithMissingFamily.stdout.trim().split("\n"), [
		routeLines[0],
		"The external probe reported a failed check.",
	]);
	const silent = runWorkflow({ PROBE_TEST_SCAN: "silent" });
	assert.equal(silent.status, 0, silent.stderr);
	assert.match(silent.stdout, /unreachable \(expected while down\)/u);
	const onlineConfig = path.join(directory, "online.json");
	writeFileSync(
		onlineConfig,
		JSON.stringify([{ ipv4: "192.0.2.3", ipv6: "2001:db8::3", ...online }]),
	);
	const silentOnline = runWorkflow({ PROBE_TEST_SCAN: "silent", PROBE_TEST_CONFIG: onlineConfig });
	assert.equal(silentOnline.status, 1);
	assert.equal(silentOnline.stdout.trim(), "The external probe reported a failed check.");
	writeFileSync(
		ssh,
		`#!/bin/sh
printf '%s\\n' "$PROBE_TEST_SSH_STDOUT"
exit "$PROBE_TEST_SSH_STATUS"
`,
	);
	const success = "Public port verification passed for 1 hosts on IPv4 and IPv6.";
	for (const [status, stdout] of [
		["255", cli.stdout],
		["255", success],
		["1", ""],
		["0", ""],
		["0", `${success}\n${success}`],
		["1", `${cli.stdout}${cli.stdout}`],
		["0", `${success}\n${cli.stdout}`],
		["1", `${success}\n${cli.stdout}`],
	] as const) {
		const unavailable = runWorkflow({
			PROBE_TEST_SSH_STATUS: status,
			PROBE_TEST_SSH_STDOUT: stdout,
		});
		assert.equal(unavailable.status, 2, unavailable.stderr);
		assert.equal(unavailable.stdout.trim(), "Public port probe unavailable.");
	}
	const passed = runWorkflow({
		PROBE_TEST_SSH_STATUS: "0",
		PROBE_TEST_SSH_STDOUT: success,
	});
	assert.equal(passed.status, 0, passed.stderr);
	assert.match(passed.stdout, /^Public port verification passed/u);
	const unconfigured = runWorkflow({ PROBE_KEY: "" });
	assert.equal(unconfigured.status, 2, unconfigured.stderr);
	assert.equal(
		unconfigured.stdout.trim(),
		"Public port probe unavailable. The connection is not configured.",
	);
	writeFileSync(
		ssh,
		`#!/bin/sh
printf 'Public port verifi\\000cation passed for 1 hosts on IPv4 and IPv6.\\n'
`,
	);
	const binary = runWorkflow();
	assert.equal(binary.status, 2, binary.stderr);
	assert.equal(binary.stdout.trim(), "Public port probe unavailable.");
});

void test("a routing failure keeps its state and still reports responses on other ports", () => {
	const unreachable = port("tcp", 80, "filtered", "host-unreach");
	for (const reason of ["host-unreach", "net-unreach", "no-route"]) {
		assert.deepEqual(assessScan(report([port("tcp", 80, "filtered", reason)]), down), {
			status: "routing-failure",
			hiddenReplies: false,
			unexpected: [],
		});
	}
	const replies = [
		port("tcp", 10_250),
		port("tcp", 6443, "closed", "reset"),
		port("udp", 8472, "filtered", "admin-prohibited"),
	];
	assert.deepEqual(assessScan(report([unreachable, ...replies]), down), {
		status: "routing-failure",
		hiddenReplies: false,
		unexpected: ["10250/tcp", "6443/tcp", "8472/udp"],
	});
});

void test("incomplete scans fail even for a host marked down", () => {
	assert.throws(() => assessScan(report().replace("<host>", '<host timedout="true">'), down));
	assert.throws(() => assessScan(report().replace('count="131070"', 'count="131069"'), down));
	assert.throws(() => assessScan(report().replace('exit="success"', 'exit="error"'), down));
	assert.throws(() =>
		assessScan(
			report().replace('protocol="tcp" numservices="65535"', 'protocol="tcp" numservices="1"'),
			down,
		),
	);
});
