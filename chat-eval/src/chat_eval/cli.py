"""`chat-eval <command>`. Each subcommand registers itself in SUBCOMMANDS below."""
from __future__ import annotations

import argparse
import sys
from typing import Callable

# name -> (configure parser, run)
SUBCOMMANDS: dict[str, tuple[Callable[[argparse.ArgumentParser], None], Callable[[argparse.Namespace], int]]] = {}


def main(argv: list[str] | None = None) -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(prog="chat-eval")
    sub = parser.add_subparsers(dest="command", required=True)
    for name, (configure, _) in SUBCOMMANDS.items():
        configure(sub.add_parser(name))
    args = parser.parse_args(argv)
    return SUBCOMMANDS[args.command][1](args)


def _bank_configure(p: argparse.ArgumentParser) -> None:
    p.add_argument("action", choices=["import", "check"])
    p.add_argument("pack", nargs="?", help="QA test-case JSON (import)")
    p.add_argument("--source", help="source name for imported rows")
    p.add_argument("--prefix", default="q", help="id prefix, e.g. uc2")
    p.add_argument("--corpus", help="corpus JSON used for the title check")
    p.add_argument("--bank", default="banks/qa_bn.jsonl")


def _bank_run(args: argparse.Namespace) -> int:
    from pathlib import Path

    from chat_eval import bank, corpus, files
    from chat_eval.files import PROJECT_ROOT
    path = PROJECT_ROOT / args.bank
    existing = bank.load(path) if path.exists() else []
    if args.action == "check":
        print(f"{len(existing)} rows, {sum(bank.is_off_topic(r) for r in existing)} off-topic: ok")
        return 0
    pack = files.read_json(args.pack)
    pack = pack if isinstance(pack, list) else next(v for v in pack.values() if isinstance(v, list))
    new = bank.import_qa_pack(pack, args.source, args.prefix, corpus.load(args.corpus))
    clash = {r["id"] for r in existing} & {r["id"] for r in new}
    if clash:
        print(f"ids already in the bank: {sorted(clash)[:5]}…", file=sys.stderr)
        return 1
    files.write_jsonl(path, existing + new)
    print(f"appended {len(new)} rows from {Path(args.pack).name} as source {args.source}")
    return 0


SUBCOMMANDS["bank"] = (_bank_configure, _bank_run)


def _check_configure(p: argparse.ArgumentParser) -> None:
    p.add_argument("run", help="run folder")
    p.add_argument("--corpus", required=True)
    p.add_argument("--bank", default="banks/qa_bn.jsonl")


def _check_run(args: argparse.Namespace) -> int:
    from pathlib import Path

    from chat_eval import bank, check, corpus, files, judge
    from chat_eval.files import PROJECT_ROOT
    run_dir = Path(args.run)
    corp = corpus.load(args.corpus)
    header = files.read_json(run_dir / "run.json")
    if header.get("corpus_sha256") and header["corpus_sha256"] != corp.sha256:
        print("corpus hash differs from the one recorded for this run", file=sys.stderr)
        return 1
    files.write_json(run_dir / "run.json", header | {"corpus_sha256": corp.sha256})
    rows = check.run_checks(run_dir, bank.load(PROJECT_ROOT / args.bank), corp)
    judge.write_sheet(run_dir, rows, bank.load(PROJECT_ROOT / args.bank), corp,
                      check.latest_turns(files.read_jsonl(run_dir / "turns.jsonl"))[0].values())
    print(f"{len(rows)} rows checked; {sum(r['needs_judge'] for r in rows)} need a judge; "
          f"{sum(bool(r['warnings']) for r in rows)} warned")
    return 0


SUBCOMMANDS["check"] = (_check_configure, _check_run)


def _verdicts_configure(p: argparse.ArgumentParser) -> None:
    p.add_argument("run")
    p.add_argument("file", help="verdict JSONL written by the judge")
    p.add_argument("--as", dest="name", default="primary", help="judge name; the first is primary")


def _verdicts_run(args: argparse.Namespace) -> int:
    from pathlib import Path

    from chat_eval import files, judge
    run_dir = Path(args.run)
    verdicts = files.read_jsonl(args.file)
    try:
        judge.validate(files.read_jsonl(run_dir / "judge_sheet.jsonl"), verdicts)
    except judge.VerdictError as e:
        print(str(e), file=sys.stderr)
        return 1
    print(f"stored {len(verdicts)} verdicts at {judge.store(run_dir, verdicts, args.name)}")
    return 0


SUBCOMMANDS["verdicts"] = (_verdicts_configure, _verdicts_run)


def _report_configure(p: argparse.ArgumentParser) -> None:
    p.add_argument("run")
    p.add_argument("--corpus", required=True)
    p.add_argument("--bank", default="banks/qa_bn.jsonl")
    p.add_argument("--vs", help="another run folder to compare with")


def _report_run(args: argparse.Namespace) -> int:
    from pathlib import Path

    from chat_eval import bank, corpus, files, report
    from chat_eval.files import PROJECT_ROOT
    run_dir = Path(args.run)
    report.render(run_dir, bank.load(PROJECT_ROOT / args.bank), corpus.load(args.corpus), files.load_settings())
    print(f"wrote {run_dir / 'report.md'}")
    if args.vs:
        (run_dir / "diff.md").write_text(report.diff(Path(args.vs), run_dir), encoding="utf-8")
        print(f"wrote {run_dir / 'diff.md'}")
    return 0


SUBCOMMANDS["report"] = (_report_configure, _report_run)


def _doctor_run(args: argparse.Namespace) -> int:
    from chat_eval import device, files
    return device.doctor(files.load_settings())


def _device_configure(p: argparse.ArgumentParser) -> None:
    p.add_argument("action", choices=["up", "list"])
    p.add_argument("--avd", help="AVD name to boot for `up`")


def _device_run(args: argparse.Namespace) -> int:
    from chat_eval import device
    if args.action == "list" or not args.avd:
        print("\n".join(device.list_avds()))
        return 0
    print(device.boot(args.avd))
    return 0


SUBCOMMANDS["doctor"] = (lambda p: None, _doctor_run)
SUBCOMMANDS["device"] = (_device_configure, _device_run)


def _setup_configure(p: argparse.ArgumentParser) -> None:
    p.add_argument("--mode", choices=["assisted", "direct"], required=True)
    p.add_argument("--install", action="store_true", help="install the APK from local.toml and clear data first")
    p.add_argument("--serial", help="device serial; defaults to local.toml")


def _setup_run(args: argparse.Namespace) -> int:
    from pathlib import Path

    from chat_eval import app, device, files
    s = files.load_settings()
    local, cap = s["local"], s["capture"]
    serial = args.serial or local.get("serial")
    if args.install:
        device.adb(serial, "install", "-r", local["apk"], timeout=600)
        device.adb(serial, "shell", "pm", "clear", cap["package"])
    device.set_airplane(serial, False)  # login and the model download need the network
    driver = app.connect(s, serial)
    try:
        app.login(driver, cap["package"], local.get("username", ""), local.get("password", ""))
        app.open_assistant(driver, cap["package"])
        app.set_answer_mode(driver, "on_device", style=args.mode)
        if args.mode == "assisted":
            print("Accept the 'simple words' offer on screen if it is showing; waiting for the chat to be ready…")
            app.wait_ready(driver, timeout_s=1800)
            app.restart(driver, serial, cap["package"], cap["launcher_activity"])
            app.open_assistant(driver, cap["package"])
        device.set_airplane(serial, True)
    except Exception:
        app.dump_ui(driver, Path("setup-failure-ui.xml"))
        print("setup failed; UI dump saved to setup-failure-ui.xml", file=sys.stderr)
        raise
    finally:
        driver.quit()
    print(f"ready: {args.mode} mode, airplane on, chat open on {serial}")
    return 0


SUBCOMMANDS["setup"] = (_setup_configure, _setup_run)


def _run_configure(p: argparse.ArgumentParser) -> None:
    p.add_argument("--source", required=True, help="bank source to ask, e.g. qa-uc2-2026-09-17")
    p.add_argument("--mode", choices=["assisted", "direct"], required=True)
    p.add_argument("--bank", default="banks/qa_bn.jsonl")
    p.add_argument("--out", help="run folder; defaults to runs/<time>-<source>-<mode>")
    p.add_argument("--resume", action="store_true", help="skip questions already in turns.jsonl")
    p.add_argument("--only", help="comma-separated ids to (re)run into the same folder")
    p.add_argument("--serial")
    p.add_argument("--allow-mode-drift", action="store_true", help="record a mode mismatch as a warning instead of stopping")


def _run_run(args: argparse.Namespace) -> int:
    from datetime import datetime
    from pathlib import Path

    from chat_eval import bank, capture, files
    from chat_eval.files import PROJECT_ROOT
    s = files.load_settings()
    out = Path(args.out) if args.out else PROJECT_ROOT / "runs" / f"{datetime.now():%Y%m%dT%H%M}-{args.source}-{args.mode}"
    only = set(args.only.split(",")) if args.only else None
    capture.run(s, args.serial or s["local"].get("serial"), bank.load(PROJECT_ROOT / args.bank),
                args.source, args.mode, out, args.resume, only, args.allow_mode_drift)
    print(f"run folder: {out}")
    return 0


SUBCOMMANDS["run"] = (_run_configure, _run_run)
