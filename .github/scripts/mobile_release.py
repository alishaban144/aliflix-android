"""Prepare, verify, and publish Aliflix's GitHub release bundle."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys


WORKFLOW = ".github/workflows/mobile-release.yml"
BUNDLE = Path("release-bundle")
MOBILE = "aliflix-mobile.apk"
BRIDGE = "aliflix-mobile-compat.apk"
ASSETS = (
    MOBILE,
    BRIDGE,
    "update-mobile-arm64.json",
    "update-mobile.json",
    "native-parity.json",
)


def command(*args, missing=None):
    result = subprocess.run(args, capture_output=True, text=True, encoding="utf-8")
    if result.returncode:
        detail = result.stderr.strip()
        if missing is not None and missing(result.returncode, detail):
            return None
        raise ValueError(detail or f"{args[0]} {args[1]} exited with {result.returncode}")
    return result.stdout


def repository():
    return os.environ["GITHUB_REPOSITORY"]


def api(path, optional=False):
    raw = command(
        "gh", "api", f"repos/{repository()}/{path}",
        missing=lambda _, message: optional and "(HTTP 404)" in message,
    )
    return json.loads(raw) if raw is not None else None


def release_for_tag(tag):
    raw = command(
        "gh", "release", "view", tag, "--repo", repository(),
        "--json", "databaseId,isDraft",
        missing=lambda _, message: message == "release not found",
    )
    return api(f"releases/{json.loads(raw)['databaseId']}") if raw is not None else None


def version():
    gradle = Path("app/build.gradle.kts").read_text(encoding="utf-8")
    codes = re.findall(r"^val mobileVersionCode\s*=\s*([1-9][0-9]*)[ \t]*$", gradle, re.M)
    names = re.findall(r'^val mobileVersionName\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+)"[ \t]*$', gradle, re.M)
    if len(codes) != 1 or len(names) != 1:
        raise ValueError("Expected one mobile version code and name in app/build.gradle.kts")
    return names[0], int(codes[0])


def source():
    return f"v{version()[0]}", command("git", "rev-parse", "HEAD").strip()


def require_draft(tag, sha):
    release = release_for_tag(tag)
    if not release or not release["draft"] or release["target_commitish"] != sha:
        raise ValueError(f"Expected an unpublished {tag} draft for {sha}.")
    return release


def prepare():
    sha = command("git", "rev-parse", "HEAD").strip()
    reuse_run = os.environ.get("REUSE_RUN", "").strip()
    draft = os.environ.get("DRAFT") == "true"
    backend_only = os.environ.get("BACKEND_ONLY") == "true"
    if (backend_only and (draft or reuse_run)) or (draft and reuse_run):
        raise ValueError("Draft, draft promotion, and Worker-only are separate release modes.")
    if reuse_run and os.environ.get("REQUESTED_NOTES", "").strip():
        raise ValueError("Promotion uses the notes from the verified draft.")

    name, code = version()
    tag = f"v{name}"
    worker = True
    if not backend_only:
        requested = os.environ.get("REQUESTED_TAG", "").strip()
        if os.environ["GITHUB_EVENT_NAME"] == "push":
            requested = os.environ["GITHUB_REF_NAME"].removeprefix("release/")
        if requested and requested != tag:
            raise ValueError(f"Requested {requested}, but Gradle defines {tag}.")

        local_tag = command(
            "git", "rev-parse", "--verify", "--quiet", f"refs/tags/{tag}^{{commit}}",
            missing=lambda status, _: status == 1,
        )
        if local_tag and local_tag.strip() != sha:
            raise ValueError(f"{tag} already points to a different commit.")

        existing = release_for_tag(tag)
        if existing:
            if not existing["draft"]:
                raise ValueError(f"{tag} is already published. Increase the mobile version for a new release.")
            if existing["target_commitish"] != sha:
                raise ValueError(f"{tag} has a draft for a different commit.")
        if reuse_run:
            if not re.fullmatch(r"[1-9][0-9]*", reuse_run):
                raise ValueError("Enter the numeric Actions run ID for the verified draft.")
            if not existing:
                raise ValueError(f"No draft exists for {tag}.")
            run = api(f"actions/runs/{reuse_run}")
            if (run["head_sha"] != sha or run["path"] != WORKFLOW or
                    run["head_repository"]["full_name"] != repository() or run["conclusion"] != "success"):
                raise ValueError("Choose a successful run of this release workflow for the same commit.")

        latest = api("releases/latest", optional=True)
        if latest:
            previous = latest["tag_name"]
            manifest = json.loads(command(
                "gh", "release", "download", previous, "--repo", repository(),
                "--pattern", "update-mobile-arm64.json", "--output", "-",
            ))
            if type(manifest.get("versionCode")) is not int or code <= manifest["versionCode"]:
                raise ValueError(f"Mobile version code must be greater than the published code for {previous}.")
            worker = bool(command("git", "diff", "--name-only", f"refs/tags/{previous}", sha,
                                  "--", "recommendation-worker").strip())

    outputs = {
        "commit": sha,
        "tag": tag,
        "version": name,
        "version_code": code,
        "worker": str(worker).lower(),
        "reuse_run": reuse_run,
    }
    with Path(os.environ["GITHUB_OUTPUT"]).open("a", encoding="utf-8") as output:
        for key, value in outputs.items():
            print(f"{key}={value}", file=output)
    print(f"{tag} at {sha}. Worker changes: {worker}. Reuse run: {reuse_run or 'none'}.")


def open_draft():
    tag, sha = source()
    if release_for_tag(tag) is None:
        command("gh", "release", "create", tag, "--repo", repository(), "--draft",
                "--target", sha, "--title", f"Aliflix {tag[1:]} (verifying)",
                "--notes", "The release workflow is still verifying this draft.")
    require_draft(tag, sha)


def upload_apk():
    tag, sha = source()
    apk = os.environ["RELEASE_APK"]
    if apk not in (MOBILE, BRIDGE):
        raise ValueError(f"Unexpected APK name: {apk}")
    release = require_draft(tag, sha)
    upload_changed_assets(release, tag, Path.cwd(), (apk,))


def sha256(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def write_json(path, data):
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8", newline="\n")


def manifest(artifact, tag, name, code, notes, abis=None):
    path = BUNDLE / artifact
    result = {
        "versionCode": code,
        "versionName": name,
        "apkUrl": f"https://github.com/{repository()}/releases/download/{tag}/{artifact}",
        "sha256": sha256(path),
        "sizeBytes": path.stat().st_size,
        "minimumSdk": 29,
        "notes": notes,
    }
    if abis is not None:
        result.update(supportedAbis=abis, releaseNotes=notes)
    return result


def make_bundle():
    name, code = version()
    tag, sha = source()
    release = require_draft(tag, sha)
    notes = os.environ.get("REQUESTED_NOTES", "").replace("\r\n", "\n").strip()
    if not notes:
        notes = command("git", "log", "-1", "--format=%s").strip()
    (BUNDLE / "release-notes.md").write_text(notes + "\n", encoding="utf-8", newline="\n")
    write_json(BUNDLE / "update-mobile-arm64.json", manifest(MOBILE, tag, name, code, notes, ["arm64-v8a"]))
    write_json(BUNDLE / "update-mobile.json", manifest(BRIDGE, tag, "3.1.175", 265, ""))
    write_json(BUNDLE / "receipt.json", {
        "commit": sha,
        "tag": tag,
        "files": {file: f"sha256:{sha256(BUNDLE / file)}" for file in ASSETS},
        "notes_sha256": hashlib.sha256(notes.encode("utf-8")).hexdigest(),
    })
    upload_changed_assets(release, tag, BUNDLE, ASSETS[2:])
    command("gh", "release", "edit", tag, "--repo", repository(), "--title", f"Aliflix {name}",
            "--notes-file", str(BUNDLE / "release-notes.md"))
    verify_bundle()


def asset_digests(release):
    return {asset["name"]: asset.get("digest") for asset in release["assets"]}


def upload_changed_assets(release, tag, directory, assets):
    existing = asset_digests(release)
    changed = [str(directory / name) for name in assets
               if existing.get(name) != f"sha256:{sha256(directory / name)}"]
    if changed:
        command("gh", "release", "upload", tag, *changed, "--repo", repository(), "--clobber")


def verify_bundle():
    tag, sha = source()
    receipt = json.loads((BUNDLE / "receipt.json").read_text(encoding="utf-8"))
    if receipt.get("tag") != tag or receipt.get("commit") != sha:
        raise ValueError("Release bundle belongs to a different source commit or tag.")
    draft = require_draft(tag, sha)
    if set(receipt["files"]) != set(ASSETS) or receipt["files"] != asset_digests(draft):
        raise ValueError("GitHub draft assets differ from the verified release bundle.")
    notes = (draft["body"] or "").replace("\r\n", "\n").strip()
    if receipt["notes_sha256"] != hashlib.sha256(notes.encode("utf-8")).hexdigest():
        raise ValueError("GitHub draft notes differ from the verified updater notes.")
    return draft


def pin_tag(tag, sha):
    ref = f"refs/tags/{tag}"
    remote = command("git", "ls-remote", "--exit-code", "--refs", "origin", ref,
                     missing=lambda status, _: status == 2)
    if remote is not None:
        command("git", "fetch", "origin", "tag", tag)
    elif command("git", "show-ref", "--verify", "--quiet", ref,
                 missing=lambda status, _: status == 1) is None:
        command("git", "tag", tag, sha)
    if command("git", "rev-parse", f"{ref}^{{commit}}").strip() != sha:
        raise ValueError(f"{tag} points to a different commit.")
    if remote is None:
        command("git", "push", "origin", ref)


def publish():
    draft = verify_bundle()
    tag, sha = source()
    pin_tag(tag, sha)
    if os.environ.get("DRAFT") != "true":
        verify_bundle()
        command("gh", "release", "edit", tag, "--repo", repository(), "--verify-tag",
                "--draft=false", "--latest")
        summary = f"Published [{tag}]({draft['html_url']})."
    else:
        summary = (
            f"Verified [{tag} draft]({draft['html_url']}). "
            f"Run this workflow at {tag} with reuse_run set to {os.environ['GITHUB_RUN_ID']} to publish it."
        )
    print(summary)
    with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a", encoding="utf-8") as output:
        print(summary, file=output)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    steps = {
        "prepare": prepare, "open-draft": open_draft, "upload-apk": upload_apk,
        "bundle": make_bundle, "verify": verify_bundle, "publish": publish,
    }
    parser.add_argument("step", choices=steps)
    try:
        steps[parser.parse_args().step]()
    except (ValueError, OSError, KeyError) as error:
        sys.exit(str(error))
