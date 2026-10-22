"""Discover upstream builds and replay only the fork's adaptations without pushing history."""

import argparse
from datetime import datetime
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.request
import zipfile


UPSTREAM = "cwuom/NeriPlayer"
# Last unmodified upstream commit; change only when intentionally refreshing the patch base.
PATCH_BASE = "f146a1fc65b1427a9950b9db95b2d1f1da536421"
MAX_BATCH = 8


def git(*args):
    return subprocess.check_output(["git", *args], text=True).strip()


def releases(repository):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("Invalid repository name")
    result = []
    page = 1
    while True:
        request = urllib.request.Request(
            f"https://api.github.com/repos/{repository}/releases?per_page=100&page={page}",
            headers={"Authorization": f"Bearer {os.environ['GH_TOKEN']}",
                     "Accept": "application/vnd.github+json", "User-Agent": "NeriPlayer-vivo-build"},
        )
        with urllib.request.urlopen(request, timeout=60) as response:
            batch = json.load(response)
        result.extend(batch)
        if len(batch) < 100:
            return result
        page += 1


def discover():
    repository = os.environ["GITHUB_REPOSITORY"]
    required_assets = {"NeriPlayer-vivo.apk", "NeriPlayer-vivo-source.zip"}
    done = {r["tag_name"] for r in releases(repository) if not r["draft"] and
            required_assets.issubset({a["name"] for a in r["assets"] if a["size"] > 0})}
    git("fetch", "--no-tags", f"https://github.com/{UPSTREAM}.git",
        "refs/heads/master:refs/remotes/vivo-upstream/master")
    tip = "refs/remotes/vivo-upstream/master"
    subprocess.run(["git", "merge-base", "--is-ancestor", PATCH_BASE, tip], check=True)
    baseline_time = datetime.fromisoformat(git("show", "-s", "--format=%cI", PATCH_BASE))
    pending = []
    # A release is a separate build even if its commit already has a development APK.
    upstream_releases = sorted(
        (r for r in releases(UPSTREAM) if not r["draft"] and
         datetime.fromisoformat(r["published_at"].replace("Z", "+00:00")) >= baseline_time),
        key=lambda r: r["published_at"],
    )
    for release in upstream_releases:
        tag = f"vivo-release-{release['id']}"
        if tag in done:
            continue
        git("fetch", "--no-tags", f"https://github.com/{UPSTREAM}.git", f"refs/tags/{release['tag_name']}")
        sha = git("rev-parse", "FETCH_HEAD^{commit}")
        pending.append({"sha": sha, "tag": tag, "kind": "release", "label": release["tag_name"]})
        if len(pending) >= MAX_BATCH:
            break
    if len(pending) < MAX_BATCH:
        commits = git("rev-list", "--reverse", "--first-parent", f"{PATCH_BASE}..{tip}").splitlines()
        for sha in commits:
            tag = f"vivo-commit-{sha}"
            if tag not in done:
                pending.append({"sha": sha, "tag": tag, "kind": "commit", "label": sha[:12]})
            if len(pending) >= MAX_BATCH:
                break
    # Include the current baseline on first activation, even before upstream moves.
    if not pending and f"vivo-commit-{PATCH_BASE}" not in done:
        pending.append({"sha": PATCH_BASE, "tag": f"vivo-commit-{PATCH_BASE}",
                        "kind": "commit", "label": PATCH_BASE[:12]})
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
        output.write("matrix=" + json.dumps({"include": pending}, separators=(",", ":")) + "\n")
        output.write(f"has_builds={'true' if pending else 'false'}\n")
    print(f"Pending builds in this batch: {len(pending)}")


def prepare(sha):
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ValueError("Expected an upstream commit SHA")
    git("config", "user.name", "github-actions[bot]")
    git("config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com")
    upstream_tip = "refs/remotes/vivo-upstream/master"
    git("fetch", "--no-tags", f"https://github.com/{UPSTREAM}.git",
        f"refs/heads/master:{upstream_tip}")
    git("merge-base", "--is-ancestor", PATCH_BASE, upstream_tip)
    git("fetch", "--no-tags", f"https://github.com/{UPSTREAM}.git", sha)
    # An upstream sync merge must not make later upstream changes part of an older build.
    own_range = (f"{PATCH_BASE}..HEAD", "--not", upstream_tip)
    for merge in git("rev-list", "--merges", *own_range).splitlines():
        if git("show", "--remerge-diff", "--format=", "--no-ext-diff", merge):
            raise ValueError("Cannot replay merge conflict resolutions; refresh the adaptation patch series before building")
    patches = git("rev-list", "--reverse", "--topo-order", "--no-merges", *own_range).splitlines()
    # Only the temporary runner checkout changes; published branch history remains intact.
    git("switch", "--detach", sha)
    if patches:
        subprocess.run(["git", "cherry-pick", *patches], check=True)
    subprocess.run(["git", "submodule", "update", "--init", "--recursive"], check=True)


def archive(destination):
    paths = subprocess.check_output(["git", "ls-files", "--recurse-submodules", "-z"]).decode().split("\0")
    with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED) as output:
        for path in paths:
            if path and Path(path).is_file():
                output.write(path, "NeriPlayer/" + path)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["discover", "prepare", "archive"])
    parser.add_argument("value", nargs="?")
    args = parser.parse_args()
    if args.command == "discover":
        discover()
    elif args.command == "prepare":
        prepare(args.value or "")
    else:
        archive(args.value or "vivo-source.zip")
