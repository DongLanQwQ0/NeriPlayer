import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import vivo_upstream as follow


def release(number=follow.FIRST_RELEASE_ID):
    return dict(id=number, tag_name=f"v{number}", draft=False, prerelease=False,
                published_at="2026-10-23T06:18:02Z", assets=[])


class UpstreamReleaseTests(unittest.TestCase):
    def test_no_release_means_no_commit_build(self):
        self.assertIsNone(follow.pending_release([], []))

    def test_old_and_draft_releases_are_not_enrolled(self):
        draft = release()
        draft["draft"] = True
        self.assertIsNone(follow.pending_release([], [release(follow.FIRST_RELEASE_ID - 1), draft]))

    def test_processes_one_release_at_a_time_in_publication_order(self):
        later = release(follow.FIRST_RELEASE_ID + 1)
        later["published_at"] = "2026-10-24T00:00:00Z"
        self.assertEqual(follow.FIRST_RELEASE_ID, follow.pending_release([], [later, release()])["id"])

    def test_only_complete_non_draft_release_is_finished(self):
        own = dict(tag_name=f"vivo-release-{follow.FIRST_RELEASE_ID}", draft=False, assets=[
            {"name": name, "size": 100} for name in follow.REQUIRED_ASSETS])
        self.assertIsNone(follow.pending_release([own], [release()]))
        self.assertIsNotNone(follow.pending_release([dict(own, draft=True)], [release()]))
        self.assertIsNotNone(follow.pending_release([dict(own, assets=[])], [release()]))
        self.assertIsNotNone(follow.pending_release([dict(own, assets=[
            {"name": name, "size": 0} for name in follow.REQUIRED_ASSETS])], [release()]))

    def test_prerelease_identity_is_preserved(self):
        item = release()
        item["prerelease"] = True
        self.assertTrue(follow.pending_release([], [item])["prerelease"])

    def test_discovery_pins_the_release_tag_and_emits_its_exact_commit(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory, "output")
            def git_result(*args):
                return "b" * 40 if args[0] == "rev-parse" else ""
            with patch.dict(os.environ, {"GITHUB_REPOSITORY": "DongLanQwQ0/NeriPlayer", "GITHUB_OUTPUT": str(output)}), \
                    patch.object(follow, "releases", side_effect=[[], [release()]]), \
                    patch.object(follow, "git", side_effect=git_result) as git:
                follow.discover()
            values = dict(line.split("=", 1) for line in output.read_text().splitlines())
            self.assertEqual("true", values["has_release"])
            self.assertEqual("b" * 40, values["sha"])
            self.assertEqual("false", values["prerelease"])
            git.assert_any_call("fetch", "--no-tags", f"https://github.com/{follow.UPSTREAM}.git",
                                f"refs/tags/v{follow.FIRST_RELEASE_ID}")

    def test_discovery_does_not_fetch_or_merge_when_no_release_is_pending(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory, "output")
            with patch.dict(os.environ, {"GITHUB_REPOSITORY": "DongLanQwQ0/NeriPlayer", "GITHUB_OUTPUT": str(output)}), \
                    patch.object(follow, "releases", return_value=[]), patch.object(follow, "git") as git:
                follow.discover()
            self.assertEqual("has_release=false\n", output.read_text())
            git.assert_not_called()

    def test_rejects_a_non_sha_target_before_running_git(self):
        with patch.object(follow, "git") as git:
            with self.assertRaises(ValueError):
                follow.merge_release("--exec=anything")
        git.assert_not_called()

    def test_multiline_api_labels_cannot_inject_workflow_outputs(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch.dict(os.environ, {"GITHUB_OUTPUT": str(Path(directory, "output"))}):
                with self.assertRaises(ValueError):
                    follow.outputs({"label": "v1\nmerged_sha=anything"})

    def test_merge_conflict_stops_before_success_output(self):
        with patch.object(follow, "git"), patch.object(follow, "outputs") as output, \
                patch.object(follow.subprocess, "run", side_effect=subprocess.CalledProcessError(1, "git merge")):
            with self.assertRaises(subprocess.CalledProcessError):
                follow.merge_release("a" * 40)
        output.assert_not_called()

    def test_real_merge_keeps_both_histories_and_fork_adaptations(self):
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
            local_git("branch", "upstream")
            Path(directory, "vivo.txt").write_text("adaptation\n", encoding="utf-8")
            local_git("add", ".")
            local_git("commit", "-m", "vivo adaptation")
            fork = local_git("rev-parse", "HEAD")
            local_git("switch", "upstream")
            Path(directory, "upstream.txt").write_text("release\n", encoding="utf-8")
            local_git("commit", "-am", "upstream release")
            upstream = local_git("rev-parse", "HEAD")
            local_git("switch", "master")
            original_run = subprocess.run
            def local_run(args, **kwargs):
                kwargs.setdefault("cwd", directory)
                return original_run(args, **kwargs)
            with patch.object(follow, "git", side_effect=local_git), \
                    patch.object(follow.subprocess, "run", side_effect=local_run), patch.object(follow, "outputs"):
                follow.merge_release(upstream)
            self.assertEqual("adaptation\n", Path(directory, "vivo.txt").read_text(encoding="utf-8"))
            self.assertEqual("release\n", Path(directory, "upstream.txt").read_text(encoding="utf-8"))
            self.assertEqual([fork, upstream], local_git("show", "-s", "--format=%P", "HEAD").split())


if __name__ == "__main__":
    unittest.main()
