// The events `adm test --json` prints, one JSON object per line, and what
// they say about each test.

export type TestEvent =
	| { event: "status"; text: string }
	| { event: "diagnostic"; file?: string; line?: number; column?: number; severity?: string; message: string }
	| { event: "run"; name: string; kind?: string }
	| { event: "pass"; name: string; seconds: number }
	| { event: "fail"; name: string; seconds: number; code?: string }
	| { event: "skip"; name: string; reason?: string }
	| { event: "output"; name?: string; text: string; stream?: string }
	| { event: "summary"; passed: number; failed: number; skipped: number; selected: number }
	| { event: "error"; text: string }
	| { event: "done"; ok: boolean };

// The event a line holds; none for a line that is not one.
export function parseEvent(line: string): TestEvent | undefined {
	const text = line.trim();
	if (!text.startsWith("{")) {
		return undefined;
	}
	let parsed: unknown;
	try {
		parsed = JSON.parse(text);
	} catch {
		return undefined;
	}
	if (!parsed || typeof parsed !== "object" || typeof (parsed as { event?: unknown }).event !== "string") {
		return undefined;
	}
	return parsed as TestEvent;
}

// Lines cuts a stream that arrives in pieces into whole lines.
export class Lines {
	private rest = "";

	push(chunk: string): string[] {
		const text = this.rest + chunk;
		const parts = text.split(/\r?\n/);
		this.rest = parts.pop() ?? "";
		return parts;
	}

	// What is left when the stream ends without a line end.
	end(): string[] {
		const last = this.rest;
		this.rest = "";
		return last === "" ? [] : [last];
	}
}

export interface Place {
	file: string;
	line: number;
}

export type Outcome =
	| { kind: "started"; name: string }
	| { kind: "passed"; name: string; milliseconds: number }
	| { kind: "failed"; name: string; milliseconds: number; message: string; place?: Place }
	| { kind: "skipped"; name: string }
	| { kind: "output"; name?: string; text: string };

// The place a failure line names: `... at path/file.adm:13`.
export function placeIn(text: string): Place | undefined {
	const m = /\bat (\S+\.adm):(\d+)/.exec(text);
	return m ? { file: m[1], line: Number(m[2]) } : undefined;
}

interface Failing {
	name: string;
	milliseconds: number;
	code?: string;
	detail: string[];
}

// Report turns the events of one run into outcomes. The runner says why a
// test failed in the lines it prints right after the `fail` event, so a
// failure is reported once the next event that is not such a line arrives.
export class Report {
	private printed = new Map<string, string[]>();
	private problems: string[] = [];
	private failing: Failing | undefined;
	readonly finished = new Set<string>();

	take(event: TestEvent): Outcome[] {
		if (event.event === "output" && event.name === undefined && this.failing) {
			this.failing.detail.push(event.text);
			return [{ kind: "output", name: this.failing.name, text: event.text }];
		}
		const out = this.flush();
		switch (event.event) {
			case "run":
				this.printed.set(event.name, []);
				out.push({ kind: "started", name: event.name });
				break;
			case "output":
				if (event.name !== undefined) {
					this.printed.get(event.name)?.push(event.text);
				}
				out.push({ kind: "output", name: event.name, text: event.text });
				break;
			case "pass":
				this.finished.add(event.name);
				out.push({ kind: "passed", name: event.name, milliseconds: event.seconds * 1000 });
				break;
			case "fail":
				this.finished.add(event.name);
				this.failing = { name: event.name, milliseconds: event.seconds * 1000, code: event.code, detail: [] };
				break;
			case "skip":
				this.finished.add(event.name);
				out.push({ kind: "skipped", name: event.name });
				break;
			case "diagnostic": {
				const where = event.file ? `${event.file}:${event.line ?? 1}:${event.column ?? 1}: ` : "";
				const text = `${where}${event.severity ? event.severity + ": " : ""}${event.message}`;
				if (event.severity !== "warning") {
					this.problems.push(text);
				}
				out.push({ kind: "output", text });
				break;
			}
			case "error":
				this.problems.push(event.text);
				out.push({ kind: "output", text: event.text });
				break;
			case "status":
				out.push({ kind: "output", text: event.text });
				break;
		}
		return out;
	}

	// The failure still waiting for its lines, when the stream ends.
	flush(): Outcome[] {
		const failing = this.failing;
		if (!failing) {
			return [];
		}
		this.failing = undefined;
		const detail = failing.detail.map((line) => line.trim()).filter((line) => line !== "");
		const before = (this.printed.get(failing.name) ?? []).join("\n").trim();
		let message = detail.join("\n");
		if (message === "") {
			message = before !== "" ? before : failing.code ? `failed (${failing.code})` : "failed";
		}
		let place: Place | undefined;
		for (const line of detail) {
			place = placeIn(line);
			if (place) {
				break;
			}
		}
		return [{ kind: "failed", name: failing.name, milliseconds: failing.milliseconds, message, place }];
	}

	// Why the tests that never finished did not run: the build's errors.
	reason(): string {
		return this.problems.length > 0 ? this.problems.join("\n") : "the test did not run";
	}
}
