import assert from "node:assert/strict";
import { test } from "node:test";

import {
	advisoriesToOpen,
	advisoryDates,
	issueBody,
	issueTitle,
	parseAdvisory,
	pinAffected,
} from "./watch-jvm-advisories.ts";

// Trimmed from https://openjdk.org/groups/vulnerability/advisories/ and its 2026-11-17 page.
const index = `<li><a href="2026-11-17">2026/11/17</a></li><li><a href="2026-10-24">2026/10/24</a></li>
<li><a href="2026-10-20">2026/10/20</a></li><li><a href="/groups/vulnerability/report">Report</a></li>`;
const page = `<p>The following vulnerabilities in OpenJDK source code were fixed
in this release. The affected versions are 27.0.1.0.3, 25.0.5.0.3,
21.0.13.0.3, 17.0.21.0.3, 11.0.33, 8u512, and earlier.</p>
<h2 id="risk-matrix">OpenJDK Risk matrix</h2><table class="risk-matrix"><tr><th nowrap="nowrap">CVE ID</th><th>Component</th><th>CVSSv3.1<br />Vector</th><th>25</th></tr>
<tr><td nowrap="nowrap">CVE-2026-114938</td><td nowrap="nowrap">hotspot/<br />compiler</td><td>8.1<br />NHNNUHHH</td><td>&#8226;</td></tr>
<tr><td nowrap="nowrap">CVE-2026-114944</td><td nowrap="nowrap">core-svc/<br />java.lang.management</td><td>5.9<br />NHNNUNHN</td><td>&#8226;</td></tr></table>
<h2 id="jfx-risk-matrix">OpenJFX Risk matrix</h2><table><tr><td nowrap="nowrap">CVE-2026-86144</td><td>javafx/<br />web</td><td>7.5<br />NHNRUHHH</td></tr></table>`;

void test("the index yields its advisory dates, newest first", () => {
	assert.deepEqual(advisoryDates(index), ["2026-11-17", "2026-10-24", "2026-10-20"]);
});

void test("an advisory yields its affected builds and its OpenJDK CVEs, not OpenJFX's", () => {
	assert.deepEqual(parseAdvisory(page), {
		affected: ["27.0.1.0.3", "25.0.5.0.3", "21.0.13.0.3", "17.0.21.0.3", "11.0.33", "8u512"],
		cves: [
			{ id: "CVE-2026-114938", cvss: "8.1" },
			{ id: "CVE-2026-114944", cvss: "5.9" },
		],
	});
	assert.throws(() => parseAdvisory("<p>Nothing here.</p>"), /no affected versions/u);
});

void test("the pinned build is compared with the affected build of its own line", () => {
	const affected = ["27.0.1.0.3", "25.0.5.0.3", "21.0.13.0.3"];
	assert.equal(pinAffected("25.0.5.0.3_5", affected), true);
	assert.equal(pinAffected("25.0.4_9", affected), true);
	assert.equal(pinAffected("25.0.5.1_1", affected), false);
	assert.equal(pinAffected("25.0.6_1", affected), false);
	assert.equal(pinAffected("23.0.2_7", affected), undefined);
});

void test("the issue says whether the pinned JRE is affected", () => {
	const advisory = parseAdvisory(page);
	assert.match(
		issueBody("2026-11-17", advisory, "25.0.5.0.3_5"),
		/\*\*The pinned JRE 25\.0\.5\.0\.3_5 is affected\.\*\*/u,
	);
	assert.match(issueBody("2026-11-17", advisory, "25.0.6_1"), /newer than the affected build/u);
	assert.match(issueBody("2026-11-17", advisory, "25.0.5.0.3_5"), /\| CVE-2026-114938 \| 8\.1 \|/u);
});

void test("only a recent advisory without an issue of its title opens one", () => {
	const now = new Date("2026-11-18T01:00:00Z");
	const dates = advisoryDates(index);
	assert.deepEqual(advisoriesToOpen(dates, new Set(), now), [
		"2026-11-17",
		"2026-10-24",
		"2026-10-20",
	]);
	assert.deepEqual(
		advisoriesToOpen(dates, new Set([issueTitle("2026-10-24"), issueTitle("2026-10-20")]), now),
		["2026-11-17"],
	);
	assert.deepEqual(advisoriesToOpen(dates, new Set(), new Date("2027-01-01T00:00:00Z")), []);
});
