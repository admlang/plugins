// The Testing view: check suites found with `adm test --list --json` and
// run with `adm test --json`.

import * as cp from "child_process";
import * as path from "path";
import * as vscode from "vscode";
import { admEnv, admPath, reportMissing, runAdm } from "./adm";
import { Lines, Outcome, parseEvent, Report } from "./testevents";
import { parseTestList, parseTestName, runFilter } from "./testnames";

export class Tests implements vscode.Disposable {
	private controller = vscode.tests.createTestController("adm", "ADM");
	// The workspace folder of each test, by the test's full name.
	private folders = new Map<string, vscode.WorkspaceFolder>();
	private tests = new Map<string, vscode.TestItem>();
	private listed = false;
	private pending: NodeJS.Timeout | undefined;
	private subscriptions: vscode.Disposable[] = [];

	constructor() {
		this.controller.resolveHandler = async (item) => {
			if (!item) {
				await this.discover();
			}
		};
		this.controller.refreshHandler = () => this.discover();
		this.controller.createRunProfile("Run", vscode.TestRunProfileKind.Run, (request, token) => this.run(request, token), true);
		// A saved source may add, move or remove tests: list again, once
		// the view has asked for them.
		this.subscriptions.push(
			vscode.workspace.onDidSaveTextDocument((document) => {
				if (document.languageId === "adm" && this.listed) {
					this.later();
				}
			}),
			vscode.workspace.onDidChangeWorkspaceFolders(() => {
				if (this.listed) {
					this.later();
				}
			}),
		);
	}

	private later(): void {
		if (this.pending) {
			clearTimeout(this.pending);
		}
		this.pending = setTimeout(() => {
			this.pending = undefined;
			void this.discover();
		}, 1500);
	}

	// Lists the tests of every workspace folder and rebuilds the tree:
	// file, suite, test.
	async discover(): Promise<void> {
		this.listed = true;
		const adm = admPath();
		if (!adm) {
			return;
		}
		const folders = vscode.workspace.workspaceFolders ?? [];
		const roots: vscode.TestItem[] = [];
		const tests = new Map<string, vscode.TestItem>();
		const owners = new Map<string, vscode.WorkspaceFolder>();
		for (const folder of folders) {
			const ran = await runAdm(adm, ["test", ".", "--list", "--json"], folder.uri.fsPath);
			const files = new Map<string, vscode.TestItem>();
			for (const listed of parseTestList(ran.stdout)) {
				const uri = vscode.Uri.file(path.resolve(folder.uri.fsPath, listed.file));
				let file = files.get(listed.file);
				if (!file) {
					const label = folders.length > 1 ? `${folder.name}/${listed.file}` : listed.file;
					file = this.controller.createTestItem(uri.toString(), label, uri);
					files.set(listed.file, file);
					roots.push(file);
				}
				const parts = parseTestName(listed.name);
				let parent = file;
				if (parts) {
					const id = `${uri.toString()}#${parts.suite}`;
					let suite = file.children.get(id);
					if (!suite) {
						suite = this.controller.createTestItem(id, parts.suite, uri);
						file.children.add(suite);
					}
					parent = suite;
				}
				const item = this.controller.createTestItem(listed.name, parts ? parts.test : listed.name, uri);
				const line = Math.max(0, listed.line - 1);
				item.range = new vscode.Range(line, 0, line, 0);
				if (parent !== file && !parent.range) {
					parent.range = item.range;
				}
				parent.children.add(item);
				tests.set(listed.name, item);
				owners.set(listed.name, folder);
			}
		}
		this.tests = tests;
		this.folders = owners;
		this.controller.items.replace(roots);
	}

	// The tests a request selects, without the ones it excludes.
	private selected(request: vscode.TestRunRequest): vscode.TestItem[] {
		const excluded = new Set(request.exclude ?? []);
		const out: vscode.TestItem[] = [];
		const visit = (item: vscode.TestItem) => {
			if (excluded.has(item)) {
				return;
			}
			if (item.children.size === 0) {
				if (this.tests.get(item.id) === item) {
					out.push(item);
				}
				return;
			}
			item.children.forEach(visit);
		};
		if (request.include) {
			request.include.forEach(visit);
		} else {
			this.controller.items.forEach(visit);
		}
		return out;
	}

	private async run(request: vscode.TestRunRequest, token: vscode.CancellationToken): Promise<void> {
		const adm = admPath();
		if (!adm) {
			await reportMissing();
			return;
		}
		await vscode.workspace.saveAll(false);
		const byFolder = new Map<vscode.WorkspaceFolder, vscode.TestItem[]>();
		for (const item of this.selected(request)) {
			const folder = this.folders.get(item.id);
			if (!folder) {
				continue;
			}
			const group = byFolder.get(folder) ?? [];
			group.push(item);
			byFolder.set(folder, group);
		}
		const run = this.controller.createTestRun(request);
		for (const items of byFolder.values()) {
			items.forEach((item) => run.enqueued(item));
		}
		try {
			for (const [folder, items] of byFolder) {
				if (token.isCancellationRequested) {
					break;
				}
				await this.runFolder(adm, folder, items, run, token);
			}
		} finally {
			run.end();
		}
	}

	private runFolder(
		adm: string,
		folder: vscode.WorkspaceFolder,
		items: vscode.TestItem[],
		run: vscode.TestRun,
		token: vscode.CancellationToken,
	): Promise<void> {
		let all = 0;
		for (const owner of this.folders.values()) {
			if (owner === folder) {
				all++;
			}
		}
		const wanted = new Map(items.map((item) => [item.id, item]));
		const extra = vscode.workspace.getConfiguration("adm", folder.uri).get<string[]>("test.arguments", []);
		const args = ["test", ".", "--json", ...runFilter([...wanted.keys()], all), ...extra];
		const report = new Report();
		const lines = new Lines();
		const apply = (outcomes: Outcome[]) => {
			for (const outcome of outcomes) {
				const item = "name" in outcome && outcome.name !== undefined ? wanted.get(outcome.name) : undefined;
				switch (outcome.kind) {
					case "started":
						if (item) {
							run.started(item);
						}
						break;
					case "passed":
						if (item) {
							run.passed(item, outcome.milliseconds);
						}
						break;
					case "skipped":
						if (item) {
							run.skipped(item);
						}
						break;
					case "failed":
						if (item) {
							const message = new vscode.TestMessage(outcome.message);
							if (outcome.place) {
								const line = Math.max(0, outcome.place.line - 1);
								const uri = vscode.Uri.file(path.resolve(folder.uri.fsPath, outcome.place.file));
								message.location = new vscode.Location(uri, new vscode.Position(line, 0));
							} else if (item.uri && item.range) {
								message.location = new vscode.Location(item.uri, item.range);
							}
							run.failed(item, message, outcome.milliseconds);
						}
						break;
					case "output":
						run.appendOutput(outcome.text.replace(/\r?\n/g, "\r\n") + "\r\n", undefined, item);
						break;
				}
			}
		};
		const read = (text: string[]) => {
			for (const line of text) {
				const event = parseEvent(line);
				if (event) {
					apply(report.take(event));
				} else if (line.trim() !== "") {
					run.appendOutput(line + "\r\n");
				}
			}
		};
		return new Promise((resolve) => {
			const child = cp.spawn(adm, args, { cwd: folder.uri.fsPath, env: admEnv() });
			const cancel = token.onCancellationRequested(() => child.kill());
			child.stdout.setEncoding("utf8");
			child.stderr.setEncoding("utf8");
			child.stdout.on("data", (chunk: string) => read(lines.push(chunk)));
			child.stderr.on("data", (chunk: string) => run.appendOutput(chunk.replace(/\r?\n/g, "\r\n")));
			const finish = (failure?: string) => {
				cancel.dispose();
				read(lines.end());
				apply(report.flush());
				if (!token.isCancellationRequested) {
					const why = failure ?? report.reason();
					for (const [name, item] of wanted) {
						if (!report.finished.has(name)) {
							run.errored(item, new vscode.TestMessage(why));
						}
					}
				}
				resolve();
			};
			child.on("error", (err) => finish(`adm did not start: ${err.message}`));
			child.on("close", () => finish());
		});
	}

	dispose(): void {
		if (this.pending) {
			clearTimeout(this.pending);
		}
		this.subscriptions.forEach((s) => s.dispose());
		this.controller.dispose();
	}
}
