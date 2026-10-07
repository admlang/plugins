import assert from "node:assert/strict";
import { test } from "node:test";
import { Lines, Outcome, parseEvent, placeIn, Report } from "../src/testevents";

// What `adm test . --json` printed for a suite with one passing and one
// failing test.
const stream = [
	'{"event":"status","text":"building tests"}',
	'{"event":"status","text":"running tests"}',
	'{"event":"run","name":"p::check(\\"n\\")::TestAdds"}',
	'{"event":"pass","name":"p::check(\\"n\\")::TestAdds","seconds":0.25}',
	'{"event":"run","name":"p::check(\\"n\\")::TestBreaks"}',
	'{"event":"output","name":"p::check(\\"n\\")::TestBreaks","stream":"stdout","text":"about to break"}',
	'{"event":"fail","name":"p::check(\\"n\\")::TestBreaks","seconds":0}',
	'{"event":"output","stream":"stdout","text":"    assertion failed at app/main_test.adm:13"}',
	'{"event":"summary","failed":1,"passed":1,"selected":2,"skipped":0}',
	'{"event":"done","ok":false}',
	'{"event":"error","text":"tests failed"}',
	'{"event":"done","ok":false}',
];

function outcomes(lines: string[], report = new Report()): Outcome[] {
	const out: Outcome[] = [];
	for (const line of lines) {
		const event = parseEvent(line);
		if (event) {
			out.push(...report.take(event));
		}
	}
	out.push(...report.flush());
	return out;
}

test("parseEvent reads event lines and skips the rest", () => {
	assert.deepEqual(parseEvent('{"event":"done","ok":true}'), { event: "done", ok: true });
	assert.equal(parseEvent("building tests..."), undefined);
	assert.equal(parseEvent('{"name":"x"}'), undefined);
	assert.equal(parseEvent("{broken"), undefined);
	assert.equal(parseEvent(""), undefined);
});

test("Lines joins pieces into whole lines", () => {
	const lines = new Lines();
	assert.deepEqual(lines.push('{"a"'), []);
	assert.deepEqual(lines.push(':1}\n{"b":2}\r\n{"c"'), ['{"a":1}', '{"b":2}']);
	assert.deepEqual(lines.end(), ['{"c"']);
	assert.deepEqual(lines.end(), []);
});

test("placeIn finds the file and line of a failure", () => {
	assert.deepEqual(placeIn("    assertion failed at app/main_test.adm:13"), { file: "app/main_test.adm", line: 13 });
	assert.equal(placeIn("expected 3, got 2"), undefined);
});

test("Report gives a failure the lines printed after it", () => {
	const report = new Report();
	const got = outcomes(stream, report).filter((o) => o.kind !== "output");
	assert.deepEqual(got, [
		{ kind: "started", name: 'p::check("n")::TestAdds' },
		{ kind: "passed", name: 'p::check("n")::TestAdds', milliseconds: 250 },
		{ kind: "started", name: 'p::check("n")::TestBreaks' },
		{
			kind: "failed",
			name: 'p::check("n")::TestBreaks',
			milliseconds: 0,
			message: "assertion failed at app/main_test.adm:13",
			place: { file: "app/main_test.adm", line: 13 },
		},
	]);
	assert.deepEqual([...report.finished].sort(), ['p::check("n")::TestAdds', 'p::check("n")::TestBreaks']);
});

test("Report attributes the lines of a failure to its test", () => {
	const printed = outcomes(stream).filter((o) => o.kind === "output" && o.name !== undefined);
	assert.deepEqual(printed, [
		{ kind: "output", name: 'p::check("n")::TestBreaks', text: "about to break" },
		{ kind: "output", name: 'p::check("n")::TestBreaks', text: "    assertion failed at app/main_test.adm:13" },
	]);
});

test("Report falls back to what the test printed, then to the code", () => {
	const said = outcomes([
		'{"event":"run","name":"t"}',
		'{"event":"output","name":"t","text":"lost the connection"}',
		'{"event":"fail","name":"t","seconds":1}',
	]).find((o) => o.kind === "failed");
	assert.equal(said?.kind === "failed" && said.message, "lost the connection");
	const coded = outcomes(['{"event":"run","name":"t"}', '{"event":"fail","name":"t","seconds":1,"code":"assert"}']).find((o) => o.kind === "failed");
	assert.equal(coded?.kind === "failed" && coded.message, "failed (assert)");
});

test("Report reports a failure when the next test starts", () => {
	const report = new Report();
	const kinds: string[] = [];
	for (const line of ['{"event":"run","name":"a"}', '{"event":"fail","name":"a","seconds":0}', '{"event":"run","name":"b"}']) {
		kinds.push(...report.take(parseEvent(line)!).map((o) => o.kind));
	}
	assert.deepEqual(kinds, ["started", "failed", "started"]);
});

test("Report says why tests never ran", () => {
	const report = new Report();
	outcomes(
		[
			'{"event":"status","text":"building tests"}',
			'{"event":"diagnostic","file":"a.adm","line":3,"column":7,"severity":"warning","message":"unused import"}',
			'{"event":"diagnostic","file":"a.adm","line":4,"column":16,"severity":"error","message":"unknown function \\"missing\\""}',
			'{"event":"error","text":"build failed"}',
			'{"event":"done","ok":false}',
		],
		report,
	);
	assert.equal(report.finished.size, 0);
	assert.equal(report.reason(), 'a.adm:4:16: error: unknown function "missing"\nbuild failed');
	assert.equal(new Report().reason(), "the test did not run");
});

test("Report counts skipped tests as finished", () => {
	const report = new Report();
	const got = outcomes(['{"event":"skip","name":"t","reason":"not yet"}'], report);
	assert.deepEqual(got, [{ kind: "skipped", name: "t" }]);
	assert.ok(report.finished.has("t"));
});
