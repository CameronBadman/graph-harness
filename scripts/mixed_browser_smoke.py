# /// script
# requires-python = ">=3.11"
# dependencies = ["playwright==1.63.0"]
# ///

"""Browser coverage for advertised structural languages; scripted browser, not agent evidence."""

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time

from playwright.sync_api import sync_playwright


def wait_descriptor(runtime):
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        descriptors = list(runtime.glob("*.json"))
        if descriptors:
            return json.loads(descriptors[0].read_text())
        time.sleep(0.1)
    raise TimeoutError("daemon descriptor did not appear")


def main():
    parser = argparse.ArgumentParser(description="Scripted mixed-language browser smoke; not agent evidence.")
    parser.add_argument("launcher")
    parser.add_argument("--ui", default="ui/dist")
    parser.add_argument("--fixture", default="examples/ticket-office")
    parser.add_argument("--chromium", required=True)
    parser.add_argument("--artifacts", default="/tmp/graphharness-mixed-browser-check")
    args = parser.parse_args()
    launcher, ui, fixture = Path(args.launcher).resolve(), Path(args.ui).resolve(), Path(args.fixture).resolve()
    artifacts = Path(args.artifacts); artifacts.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix="graphharness-mixed-browser-"))
    checkout, runtime, copied_distribution = temporary / "fixture", temporary / "runtime", temporary / "distribution"
    shutil.copytree(fixture, checkout); runtime.mkdir(mode=0o700)
    (checkout / "web" / "receipt.js").write_text("export function formatReceipt(total) { return `$${total}`; }\n", encoding="utf-8")
    shutil.copytree(launcher.parent.parent, copied_distribution)
    launcher = copied_distribution / "bin" / launcher.name
    env = os.environ.copy(); env["GRAPHHARNESS_RUNTIME_DIR"] = str(runtime)
    daemon = subprocess.Popen([str(launcher), "daemon", str(checkout), str(ui)], env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    try:
        descriptor = wait_descriptor(runtime)
        endpoint = descriptor["endpoint"]
        checks = (("typescript", "formatQuote", "formatQuote"), ("javascript", "formatReceipt", "formatReceipt"), ("python", "summarize_sales", "summarize_sales"))
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(headless=True, executable_path=args.chromium)
            context = browser.new_context(viewport={"width": 1440, "height": 900}, reduced_motion="reduce")
            page = context.new_page(); page.set_default_timeout(90_000); errors = []
            page.on("pageerror", lambda error: errors.append(str(error)))
            page.goto(endpoint)
            page.get_by_role("button", name="Start pairing").click()
            code = page.locator("code.command").inner_text().split()[-1]
            approved = subprocess.run([str(launcher), "authorize-browser", str(checkout), code], env=env, capture_output=True, text=True, timeout=10)
            assert approved.returncode == 0, approved.stderr
            page.locator("#workspace").wait_for(state="visible")
            page.wait_for_function("""() => ['java', 'typescript', 'python'].every(language =>
                [...document.querySelector('.language-filter').options].some(option => option.value === language))""")
            available = page.locator(".language-filter option").evaluate_all("options => options.map(option => option.value)")
            expected = [(language, query, source) for language, query, source in checks if language in available]
            assert {"typescript", "javascript", "python"} <= set(available), available
            checked = []
            page.get_by_role("button", name="Code list").click()
            for language, query, source_text in expected:
                page.locator(".language-filter").select_option(language)
                page.get_by_role("searchbox", name="Search symbols or files").fill(query)
                match = page.locator("#list-panel button").filter(has_text=query).first
                match.wait_for(state="visible")
                match.focus()
                page.keyboard.press("Enter")
                page.get_by_role("button", name="Load source").click()
                page.locator("pre.source").wait_for(state="visible")
                assert source_text in page.locator("pre.source").inner_text()
                checked.append(language)
            page.get_by_role("button", name="Fit graph").click()
            page.screenshot(path=str(artifacts / "mixed-1440x900.png"), full_page=True)
            assert not errors, errors
            context.close(); browser.close()
        print(json.dumps({"scripted": True, "claim_boundary": "Browser interaction coverage only; not agent evidence.", "languages_checked": checked,
                          "checks": ["language_filter", "keyboard_operable_code_list", "selected_symbol_source", "no_page_errors"], "artifacts": str(artifacts)}))
    finally:
        daemon.terminate()
        try:
            daemon.wait(timeout=5)
        except subprocess.TimeoutExpired:
            daemon.kill(); daemon.wait(timeout=5)
        shutil.rmtree(temporary, ignore_errors=True)


if __name__ == "__main__":
    main()
