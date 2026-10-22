import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import vivo_upstream as follow


class UpstreamBuildTests(unittest.TestCase):
    def discover(self, own=(), upstream=(), commits=()):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "output"

            def git(*args):
                if args[0] == "show":
                    return "2026-10-21T16:00:00+08:00"
                if args[0] == "rev-list":
                    return "\n".join(commits)
                if args[0] == "rev-parse":
                    return "b" * 40
                return ""

            with patch.dict(os.environ, {"GITHUB_REPOSITORY": "DongLanQwQ0/NeriPlayer", "GITHUB_OUTPUT": str(output)}), \
                    patch.object(follow, "releases", side_effect=[list(own), list(upstream)]), \
                    patch.object(follow, "git", side_effect=git), patch.object(follow.subprocess, "run"):
                follow.discover()
            return json.loads(output.read_text(encoding="utf-8").splitlines()[0].removeprefix("matrix="))["include"]

    def test_initial_activation_builds_the_baseline(self):
        self.assertEqual(follow.PATCH_BASE, self.discover()[0]["sha"])

    def test_keeps_every_pending_mainline_commit_not_only_the_latest(self):
        commits = [f"{number:040x}" for number in range(1, 12)]
        self.assertEqual(commits[:follow.MAX_BATCH], [item["sha"] for item in self.discover(commits=commits)])

    def test_only_complete_published_releases_are_done(self):
        sha = "a" * 40
        release = {"tag_name": f"vivo-commit-{sha}", "draft": False, "assets": [
            {"name": "NeriPlayer-vivo.apk", "size": 100},
            {"name": "NeriPlayer-vivo-source.zip", "size": 100},
        ]}
        self.assertEqual(sha, self.discover(own=[dict(release, assets=[])], commits=[sha])[0]["sha"])
        self.assertEqual(follow.PATCH_BASE, self.discover(own=[release], commits=[sha])[0]["sha"])
        self.assertEqual(sha, self.discover(own=[dict(release, draft=True)], commits=[sha])[0]["sha"])

    def test_release_time_compares_instants_not_timezone_strings(self):
        release = {"id": 123, "tag_name": "v1", "draft": False, "published_at": "2026-10-21T09:00:00Z"}
        result = self.discover(upstream=[release])
        self.assertEqual("vivo-release-123", result[0]["tag"])
        self.assertEqual("release", result[0]["kind"])

    def test_source_conflict_stops_before_submodule_or_build_work(self):
        with patch.object(follow, "git") as git, \
                patch.object(follow.subprocess, "run", side_effect=subprocess.CalledProcessError(1, "git rebase")) as run:
            with self.assertRaises(subprocess.CalledProcessError):
                follow.prepare("a" * 40)
        self.assertEqual(1, run.call_count)
        self.assertFalse(any(call.args[0] == "push" for call in git.call_args_list))

    def test_rejects_a_non_sha_target(self):
        with self.assertRaises(ValueError):
            follow.prepare("--exec=anything")


if __name__ == "__main__":
    unittest.main()
