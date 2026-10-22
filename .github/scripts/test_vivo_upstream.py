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
        def git_result(*args):
            return "b" * 40 if args[:4] == ("rev-list", "--reverse", "--topo-order", "--no-merges") else ""

        with patch.object(follow, "git", side_effect=git_result) as git, \
                patch.object(follow.subprocess, "run", side_effect=subprocess.CalledProcessError(1, "git cherry-pick")) as run:
            with self.assertRaises(subprocess.CalledProcessError):
                follow.prepare("a" * 40)
        self.assertEqual(1, run.call_count)
        self.assertFalse(any(call.args[0] == "push" for call in git.call_args_list))

    def test_rejects_merges_with_resolution_changes(self):
        def git_result(*args):
            if args[:2] == ("rev-list", "--merges"):
                return "b" * 40
            return "resolved diff" if args[0] == "show" else ""

        with patch.object(follow, "git", side_effect=git_result) as git, patch.object(follow.subprocess, "run") as run:
            with self.assertRaisesRegex(ValueError, "merge conflict resolutions"):
                follow.prepare("a" * 40)
        run.assert_not_called()
        self.assertFalse(any(call.args[0] == "switch" for call in git.call_args_list))

    def test_sync_merge_does_not_replay_later_upstream_changes_into_an_older_build(self):
        with tempfile.TemporaryDirectory() as directory:
            def local_git(*args):
                if args[0] == "fetch":
                    return ""
                return subprocess.check_output(["git", *args], cwd=directory, text=True).strip()

            subprocess.run(["git", "init", "-b", "master", directory], check=True, capture_output=True)
            local_git("config", "user.name", "Test")
            local_git("config", "user.email", "test@example.com")
            Path(directory, "upstream.txt").write_text("base\n", encoding="utf-8")
            local_git("add", ".")
            local_git("commit", "-m", "base")
            base = local_git("rev-parse", "HEAD")
            local_git("branch", "upstream")
            Path(directory, "vivo.txt").write_text("adaptation\n", encoding="utf-8")
            local_git("add", ".")
            local_git("commit", "-m", "vivo adaptation")
            local_git("switch", "upstream")
            Path(directory, "upstream.txt").write_text("later upstream\n", encoding="utf-8")
            local_git("commit", "-am", "upstream change")
            local_git("update-ref", "refs/remotes/vivo-upstream/master", "HEAD")
            local_git("switch", "master")
            local_git("merge", "--no-ff", "--no-edit", "upstream")
            Path(directory, "vivo.txt").write_text("updated adaptation\n", encoding="utf-8")
            local_git("commit", "-am", "vivo fix after sync")
            original_run = subprocess.run

            def local_run(args, **kwargs):
                kwargs.setdefault("cwd", directory)
                return original_run(args, **kwargs)

            with patch.object(follow, "git", side_effect=local_git), \
                    patch.object(follow, "PATCH_BASE", base), patch.object(follow.subprocess, "run", side_effect=local_run):
                follow.prepare(base)
            self.assertEqual("base\n", Path(directory, "upstream.txt").read_text(encoding="utf-8"))
            self.assertEqual("updated adaptation\n", Path(directory, "vivo.txt").read_text(encoding="utf-8"))

    def test_rejects_a_non_sha_target(self):
        with self.assertRaises(ValueError):
            follow.prepare("--exec=anything")


if __name__ == "__main__":
    unittest.main()
