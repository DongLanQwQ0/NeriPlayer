"""Merge new upstream Releases into the fork, then build and publish its release variant."""

import argparse
from http.client import RemoteDisconnected
import json
import os
from pathlib import Path
import re
import subprocess
import time
from urllib.error import HTTPError, URLError
import urllib.request
import zipfile


UPSTREAM = "cwuom/NeriPlayer"
# First upstream Release enrolled in the optimized .vivo distribution.
FIRST_RELEASE_ID = 421509148
# Explicitly withdrawn distributions must not be retried as incomplete drafts.
WITHDRAWN_RELEASE_IDS = {421509148}
REQUIRED_ASSETS = {"NeriPlayer-vivo.apk", "NeriPlayer-vivo-source.zip"}


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
                     "Accept": "application/vnd.github+json", "User-Agent": "NeriPlayer-vivo-release"},
        )
        batch = release_page(request)
        result.extend(batch)
        if len(batch) < 100:
            return result
        page += 1


def release_page(request):
    """Retry transient API disconnects, never authentication/permission failures."""
    for attempt in range(3):
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                return json.load(response)
        except (URLError, RemoteDisconnected, TimeoutError) as failure:
            if isinstance(failure, HTTPError) and failure.code not in {429, 500, 502, 503, 504}:
                raise
            if attempt == 2:
                raise
            if isinstance(failure, HTTPError):
                failure.close()
            time.sleep(2 ** attempt)


def pending_release(own, upstream):
    done = {r["tag_name"] for r in own if not r["draft"] and
            REQUIRED_ASSETS.issubset({a["name"] for a in r["assets"] if a["size"] > 0})}
    pending = sorted(
        (r for r in upstream if not r["draft"] and r["id"] >= FIRST_RELEASE_ID and
         r["id"] not in WITHDRAWN_RELEASE_IDS and
         f"vivo-release-{r['id']}" not in done),
        key=lambda r: (r["published_at"], r["id"]),
    )
    return pending[0] if pending else None


def outputs(values):
    if any("\n" in str(value) or "\r" in str(value) for value in values.values()):
        raise ValueError("Multiline workflow output")
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
        for key, value in values.items():
            output.write(f"{key}={value}\n")


def discover():
    release = pending_release(releases(os.environ["GITHUB_REPOSITORY"]), releases(UPSTREAM))
    if release is None:
        outputs({"has_release": "false"})
        return
    ref = f"refs/tags/{release['tag_name']}"
    git("check-ref-format", ref)
    git("fetch", "--no-tags", f"https://github.com/{UPSTREAM}.git", ref)
    sha = git("rev-parse", "FETCH_HEAD^{commit}")
    validate_sha(sha)
    outputs({"has_release": "true", "sha": sha, "tag": f"vivo-release-{release['id']}",
             "label": release["tag_name"], "prerelease": str(release["prerelease"]).lower()})


def validate_sha(sha):
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ValueError("Expected an upstream commit SHA")


def merge_release(sha):
    validate_sha(sha)
    git("config", "user.name", "github-actions[bot]")
    git("config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com")
    git("fetch", "--no-tags", f"https://github.com/{UPSTREAM}.git", sha)
    # Preserve the fork's commits and explicit merge history. Conflicts stop before a push.
    subprocess.run(["git", "merge", "--no-ff", "--no-edit", sha], check=True)
    outputs({"merged_sha": git("rev-parse", "HEAD")})


def archive(destination):
    paths = subprocess.check_output(["git", "ls-files", "--recurse-submodules", "-z"]).decode().split("\0")
    with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED) as output:
        for path in paths:
            if path and Path(path).is_file():
                output.write(path, "NeriPlayer/" + path)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["discover", "merge", "archive"])
    parser.add_argument("value", nargs="?")
    args = parser.parse_args()
    if args.command == "discover":
        discover()
    elif args.command == "merge":
        merge_release(args.value or "")
    else:
        archive(args.value or "vivo-source.zip")
