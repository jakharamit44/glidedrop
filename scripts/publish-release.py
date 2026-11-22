#!/usr/bin/env python3
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.request
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
VERSION_FILE = REPO_ROOT / "VERSION"
RELEASE_DIR = REPO_ROOT / "release"

if not VERSION_FILE.exists():
    sys.exit("[-] VERSION file not found")

VERSION = VERSION_FILE.read_text(encoding="utf-8").strip()
TAG = f"v{VERSION}"
OWNER = "jakharamit44"
REPO = "glidedrop"

def load_version_changelog(version: str) -> str:
    changelog_path = REPO_ROOT / "CHANGELOG.md"
    if not changelog_path.exists():
        return ""
    text = changelog_path.read_text(encoding="utf-8")
    pattern = rf"##\s*\[?v?{re.escape(version)}\]?[^\n]*\n\n?(.*?)(?=\n##\s*(?:\[|v)|\Z)"
    match = re.search(pattern, text, re.DOTALL)
    if match:
        return match.group(1).strip()
    return ""


def current_git_branch() -> str:
    try:
        branch = subprocess.check_output(
            ["git", "rev-parse", "--abbrev-ref", "HEAD"],
            cwd=REPO_ROOT,
            text=True,
        ).strip()
        if branch and branch != "HEAD":
            return branch
    except Exception:
        pass
    return "main"


def _find_android_tool(name: str) -> str:
    found = shutil.which(name)
    if found:
        return found
    for sdk_env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        sdk = os.environ.get(sdk_env, "").strip()
        if sdk:
            cands = sorted(Path(sdk).glob(f"build-tools/*/{name}"), reverse=True)
            if cands:
                return str(cands[0])
    default_sdk = Path.home() / "Library" / "Android" / "sdk"
    cands = sorted(default_sdk.glob(f"build-tools/*/{name}"), reverse=True)
    return str(cands[0]) if cands else ""


def verify_local_release_assets(assets_to_upload: list, allow_debug: bool = False) -> None:
    missing = [str(p) for p, _ in assets_to_upload if not p.is_file()]
    if missing:
        sys.exit(f"[-] Missing required release asset(s): {', '.join(missing)}")

    sums_path = RELEASE_DIR / "SHA256SUMS.txt"
    parsed_sums = {}
    for line in sums_path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        parts = line.split()
        if len(parts) < 2 or not re.fullmatch(r"[0-9a-fA-F]{64}", parts[0]):
            sys.exit(f"[-] Malformed line in {sums_path}: {line}")
        parsed_sums[Path(parts[-1]).name] = parts[0].lower()

    for asset_path, _ in assets_to_upload:
        if asset_path.name == "SHA256SUMS.txt":
            continue
        expected_sha = parsed_sums.get(asset_path.name)
        if not expected_sha:
            sys.exit(f"[-] Asset missing from SHA256SUMS.txt: {asset_path.name}")
        h = hashlib.sha256()
        with open(asset_path, "rb") as f:
            while True:
                chunk = f.read(1048576)
                if not chunk:
                    break
                h.update(chunk)
        actual_sha = h.hexdigest().lower()
        if actual_sha != expected_sha:
            sys.exit(f"[-] SHA-256 mismatch for {asset_path.name}: expected {expected_sha}, got {actual_sha}")

    apk_path = RELEASE_DIR / f"GlideDrop-v{VERSION}.apk"
    apksigner = _find_android_tool("apksigner")
    if apksigner:
        res = subprocess.run(
            [apksigner, "verify", "--print-certs", str(apk_path)],
            capture_output=True,
            text=True,
            check=False,
        )
        if res.returncode != 0:
            sys.exit(f"[-] APK signature verification failed for {apk_path.name}: {res.stderr.strip() or res.stdout.strip()}")
        if not allow_debug and "CN=Android Debug" in res.stdout:
            sys.exit(f"[-] Refusing to publish debug-keystore APK ({apk_path.name}); pass --allow-debug to override")
    elif not allow_debug:
        print(f"[!] Warning: apksigner not found on PATH/SDK; verify {apk_path.name} signing before distribution")

    aapt2 = _find_android_tool("aapt2")
    if aapt2 and not allow_debug:
        res = subprocess.run(
            [aapt2, "dump", "badging", str(apk_path)],
            capture_output=True,
            text=True,
            check=False,
        )
        if res.returncode == 0 and "application-debuggable" in res.stdout:
            sys.exit(f"[-] Refusing to publish android:debuggable APK ({apk_path.name}); pass --allow-debug to override")

def generate_release_body(version: str) -> str:
    notes = load_version_changelog(version)
    if not notes:
        try:
            prev_tag = subprocess.check_output(["git", "describe", "--tags", "--abbrev=0", "HEAD^"], text=True).strip()
            log_out = subprocess.check_output(["git", "log", f"{prev_tag}..HEAD", "--oneline"], text=True).strip()
            notes = "### Commits\n" + "\n".join(f"- {line}" for line in log_out.splitlines())
        except Exception:
            notes = f"Release v{version}"

    body = f"""# GlideDrop v{version}

Pure peer-to-peer file sharing between macOS and Android devices over BLE and 5 GHz Wi-Fi Direct.

{notes}

### Release Assets
- `GlideDrop-v{version}.apk`: Universal Android application (Android 12+ / API 31+).
- `GlideDrop-v{version}.pkg`: macOS Native Installer package (installs directly to /Applications and auto-unquarantines; eliminates Gatekeeper "damaged" alert).
- `GlideDrop-v{version}.dmg`: macOS drag-and-drop application with built-in Gatekeeper launcher.
- `SHA256SUMS.txt`: Cryptographic checksums.
"""
    return body

allow_debug = "--allow-debug" in sys.argv[1:]
assets_to_upload = [
    (RELEASE_DIR / f"GlideDrop-v{VERSION}.apk", "application/vnd.android.package-archive"),
    (RELEASE_DIR / f"GlideDrop-v{VERSION}.dmg", "application/x-apple-diskimage"),
    (RELEASE_DIR / f"GlideDrop-v{VERSION}.pkg", "application/octet-stream"),
    (RELEASE_DIR / "SHA256SUMS.txt", "text/plain"),
]
verify_local_release_assets(assets_to_upload, allow_debug=allow_debug)

print(f"[+] Publishing GitHub Release {TAG} for {OWNER}/{REPO}...")

# 1. Get OAuth / PAT Token from environment or git credential-osxkeychain
token = os.environ.get("GITHUB_TOKEN", "").strip() or os.environ.get("GH_TOKEN", "").strip()
if not token:
    try:
        p = subprocess.Popen(["git", "credential-osxkeychain", "get"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
        out, _ = p.communicate("protocol=https\nhost=github.com\n\n")
        for line in out.splitlines():
            if line.startswith("password="):
                token = line.split("=", 1)[1].strip()
    except Exception:
        pass

if not token:
    sys.exit("[-] Failed to retrieve GitHub token from environment (GITHUB_TOKEN/GH_TOKEN) or osxkeychain")

headers = {
    "Authorization": f"token {token}",
    "Accept": "application/vnd.github.v3+json",
    "User-Agent": "GlideDrop-Release-Script",
}

# 2. Check if release already exists or create new release
get_req = urllib.request.Request(f"https://api.github.com/repos/{OWNER}/{REPO}/releases/tags/{TAG}", headers=headers)
release_id = None
upload_url = None
release_notes = generate_release_body(VERSION)

try:
    with urllib.request.urlopen(get_req, timeout=30) as resp:
        rel_data = json.loads(resp.read().decode("utf-8"))
        release_id = rel_data.get("id")
        upload_url = rel_data.get("upload_url", "").partition("{")[0]
        html_url = rel_data.get("html_url")
        print(f"[+] Found existing release {TAG} (ID: {release_id}): {html_url}")
        # Update release body with authentic CHANGELOG.md notes
        patch_payload = json.dumps({
            "name": f"GlideDrop v{VERSION}",
            "body": release_notes,
        }).encode("utf-8")
        patch_req = urllib.request.Request(
            f"https://api.github.com/repos/{OWNER}/{REPO}/releases/{release_id}",
            data=patch_payload,
            headers={**headers, "Content-Type": "application/json"},
            method="PATCH"
        )
        try:
            with urllib.request.urlopen(patch_req, timeout=30) as pr:
                print(f"[+] Updated release {TAG} body with accurate notes from CHANGELOG.md")
        except Exception as pe:
            print(f"[-] Warning: Failed to update release body: {pe}")
except urllib.error.HTTPError as e:
    if e.code == 404:
        # Create Release
        create_payload = json.dumps({
            "tag_name": TAG,
            "target_commitish": current_git_branch(),
            "name": f"GlideDrop v{VERSION}",
            "body": release_notes,
            "draft": False,
            "prerelease": False
        }).encode("utf-8")

        post_req = urllib.request.Request(
            f"https://api.github.com/repos/{OWNER}/{REPO}/releases",
            data=create_payload,
            headers={**headers, "Content-Type": "application/json"}
        )
        with urllib.request.urlopen(post_req, timeout=30) as resp:
            rel_data = json.loads(resp.read().decode("utf-8"))
            release_id = rel_data.get("id")
            upload_url = rel_data.get("upload_url", "").partition("{")[0]
            html_url = rel_data.get("html_url")
            print(f"[+] Created release {TAG} (ID: {release_id}): {html_url}")
    else:
        sys.exit(f"[-] HTTP error getting release: {e}")

# 3. List existing assets to avoid duplicate upload error
list_assets_req = urllib.request.Request(f"https://api.github.com/repos/{OWNER}/{REPO}/releases/{release_id}/assets", headers=headers)
existing_assets = {}
with urllib.request.urlopen(list_assets_req, timeout=30) as resp:
    assets_data = json.loads(resp.read().decode("utf-8"))
    for a in assets_data:
        existing_assets[a["name"]] = a["id"]

# 4. Upload assets
for asset_path, ctype in assets_to_upload:
    asset_name = asset_path.name
    if asset_name in existing_assets:
        print(f"[*] Deleting previous asset {asset_name} (ID: {existing_assets[asset_name]})...")
        del_req = urllib.request.Request(
            f"https://api.github.com/repos/{OWNER}/{REPO}/releases/assets/{existing_assets[asset_name]}",
            headers=headers,
            method="DELETE"
        )
        with urllib.request.urlopen(del_req, timeout=30):
            pass

    print(f"[+] Uploading {asset_name} ({asset_path.stat().st_size:,} bytes)...")
    with open(asset_path, "rb") as f:
        data = f.read()

    upload_endpoint = f"{upload_url}?name={asset_name}"
    up_req = urllib.request.Request(
        upload_endpoint,
        data=data,
        headers={
            **headers,
            "Content-Type": ctype,
            "Content-Length": str(len(data))
        }
    )
    with urllib.request.urlopen(up_req, timeout=120) as up_resp:
        result = json.loads(up_resp.read().decode("utf-8"))
        print(f"[+] Successfully uploaded {asset_name}: {result.get('browser_download_url')}")

# 5. Maintain rolling release backup branch on GitHub
try:
    backup_branch = f"backup/{TAG}"
    print(f"\n[+] Syncing release backup branch {backup_branch} to GitHub...")
    subprocess.run(["git", "branch", "-f", backup_branch, "HEAD"], check=True)
    subprocess.run(["git", "push", "origin", backup_branch], check=True)
    print(f"[+] Successfully synced {backup_branch} to origin.")
except Exception as be:
    print(f"[-] Warning: Failed to sync backup branch: {be}")

print(f"\n=======================================================")
print(f" Release {TAG} published successfully!")
print(f" URL: https://github.com/{OWNER}/{REPO}/releases/tag/{TAG}")
print(f"=======================================================")

