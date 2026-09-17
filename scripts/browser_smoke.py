import argparse
import importlib.metadata
import json
import os
from pathlib import Path
import subprocess

from playwright.sync_api import sync_playwright


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("endpoint")
    parser.add_argument("launcher")
    parser.add_argument("root")
    parser.add_argument("runtime_directory")
    parser.add_argument("--chromium")
    parser.add_argument("--artifacts", default="/tmp/graphharness-browser-check")
    args = parser.parse_args()
    artifacts = Path(args.artifacts)
    artifacts.mkdir(parents=True, exist_ok=True)
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True, executable_path=args.chromium)
        context = browser.new_context(viewport={"width": 1440, "height": 900}, reduced_motion="reduce")
        page = context.new_page()
        errors = []
        page.on("pageerror", lambda error: errors.append(str(error)))
        page.goto(args.endpoint)
        page.get_by_role("button", name="Start pairing").click()
        code = page.locator("code.command").inner_text().split()[-1]
        command = [str(Path(args.launcher).resolve()), "authorize-browser", str(Path(args.root).resolve()), code]
        env = os.environ.copy()
        env["GRAPHHARNESS_RUNTIME_DIR"] = args.runtime_directory
        approved = subprocess.run(command, env=env, capture_output=True, text=True, timeout=10)
        assert approved.returncode == 0, "CLI observer approval failed"
        page.locator("#workspace").wait_for(state="visible")
        page.get_by_role("searchbox", name="Search symbols or files").fill("reserve")
        page.get_by_role("button", name="Code list").click()
        selected = page.locator("#list-panel button").filter(has_text="reserve").first
        selected.click()
        page.get_by_role("button", name="Load source").click()
        page.locator("pre.source").wait_for(state="visible")
        assert "remaining" in page.locator("pre.source").inner_text()
        assert page.get_by_role("combobox", name="Filter by language").is_visible()
        assert page.get_by_role("button", name="Fit graph").is_visible()
        page.get_by_role("searchbox", name="Search symbols or files").fill("")
        page.get_by_role("button", name="Code list").click()
        page.get_by_role("button", name="Fit graph").click()
        sizes = [(1440, 900), (1280, 800), (390, 844)]
        for width, height in sizes:
            page.set_viewport_size({"width": width, "height": height})
            page.screenshot(path=str(artifacts / f"live-{width}x{height}.png"), full_page=True)
            assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth"), f"Horizontal overflow at {width}"
        assert not errors, errors
        cookies = context.cookies()
        observer = next(cookie for cookie in cookies if cookie["name"] == "gh_observer")
        assert observer["httpOnly"] and observer["sameSite"] == "Strict"
        assert page.evaluate("localStorage.length") == 0
        print(json.dumps({"browser": browser.version, "playwright": importlib.metadata.version("playwright"), "checks": ["pair", "approve", "claim", "graph", "search", "source", "controls", "viewports", "cookie", "no_local_storage", "no_page_errors"], "artifacts": str(artifacts)}))
        context.close()
        browser.close()


if __name__ == "__main__":
    main()
