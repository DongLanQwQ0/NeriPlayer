import os
from http.client import RemoteDisconnected
import io
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import re
from urllib.error import HTTPError
from unittest.mock import patch

import vivo_upstream as follow


def release(number=follow.FIRST_RELEASE_ID + 1):
    return dict(id=number, tag_name=f"v{number}", draft=False, prerelease=False,
                published_at="2026-10-23T06:18:02Z", assets=[])


class UpstreamReleaseTests(unittest.TestCase):
    def test_no_release_means_no_commit_build(self):
        self.assertIsNone(follow.pending_release([], []))

    def test_api_disconnect_retries_without_skipping_a_release(self):
        response = lambda: io.StringIO('[{"id": 123}]')
        with patch.object(follow.urllib.request, "urlopen", side_effect=[RemoteDisconnected(), response()]) as request, \
                patch.object(follow.time, "sleep") as sleep:
            self.assertEqual([{"id": 123}], follow.release_page(object()))
        self.assertEqual(2, request.call_count)
        sleep.assert_called_once_with(1)

    def test_api_retry_is_bounded_and_denied_permissions_are_not_retried(self):
        for failure, attempts in ((RemoteDisconnected(), 3), (HTTPError("url", 403, "Denied", {}, None), 1)):
            with self.subTest(failure=failure), patch.object(follow.urllib.request, "urlopen", side_effect=failure) as request, \
                    patch.object(follow.time, "sleep"):
                with self.assertRaises(type(failure)):
                    follow.release_page(object())
                self.assertEqual(attempts, request.call_count)
            if isinstance(failure, HTTPError):
                failure.close()

    def test_transient_api_error_retries_and_other_http_errors_fail_immediately(self):
        for status, attempts in ((502, 3), (404, 1)):
            failure = HTTPError("url", status, "Failure", {}, None)
            with self.subTest(status=status), patch.object(follow.urllib.request, "urlopen", side_effect=failure) as request, \
                    patch.object(follow.time, "sleep"):
                with self.assertRaises(HTTPError):
                    follow.release_page(object())
                self.assertEqual(attempts, request.call_count)
            failure.close()

    def test_old_and_draft_releases_are_not_enrolled(self):
        draft = release()
        draft["draft"] = True
        self.assertIsNone(follow.pending_release([], [release(follow.FIRST_RELEASE_ID - 1), draft]))

    def test_processes_one_release_at_a_time_in_publication_order(self):
        later = release(follow.FIRST_RELEASE_ID + 2)
        later["published_at"] = "2026-10-24T00:00:00Z"
        self.assertEqual(follow.FIRST_RELEASE_ID + 1, follow.pending_release([], [later, release()])["id"])

    def test_withdrawn_release_is_skipped_without_blocking_the_next_release(self):
        withdrawn = release(421509148)
        self.assertIsNone(follow.pending_release([], [withdrawn]))
        draft = dict(tag_name="vivo-release-421509148", draft=True, assets=[])
        self.assertIsNone(follow.pending_release([draft], [withdrawn]))
        self.assertEqual(release(), follow.pending_release([draft], [withdrawn, release()]))

    def test_only_complete_non_draft_release_is_finished(self):
        own = dict(tag_name=f"vivo-release-{follow.FIRST_RELEASE_ID + 1}", draft=False, assets=[
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
                                f"refs/tags/v{follow.FIRST_RELEASE_ID + 1}")

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


class WorkflowTriggerTests(unittest.TestCase):
    def test_only_the_upstream_release_detector_is_automatic(self):
        folder = Path(__file__).resolve().parents[1] / "workflows"
        for path in sorted(folder.glob("*.yml")):
            with self.subTest(workflow=path.name):
                text = path.read_text(encoding="utf-8")
                block = re.search(r"^on:\n(.*?)(?=^\S)", text, re.MULTILINE | re.DOTALL)
                self.assertIsNotNone(block, "Expected an explicit top-level trigger mapping")
                events = set(re.findall(r"^  ([a-z_]+):", block.group(1), re.MULTILINE))
                expected = {"schedule", "workflow_dispatch"} if path.name == "vivo_upstream.yml" else {"workflow_dispatch"}
                self.assertEqual(expected, events)

    def test_build_and_publish_require_a_real_pending_release_and_successful_merge(self):
        folder = Path(__file__).resolve().parents[1] / "workflows"
        text = (folder / "vivo_upstream.yml").read_text(encoding="utf-8")
        self.assertIn("if: needs.discover.outputs.has_release == 'true'", text)
        self.assertIn("needs: [discover, merge]", text)
        self.assertIn("needs: [discover, merge, build]", text)
        checks = "python3 -B -m unittest discover -s .github/scripts -p 'test_vivo_upstream.py'"
        merge = text.split("  merge:\n", 1)[1].split("  build:\n", 1)[0]
        self.assertLess(merge.index(checks), merge.index("git push origin"))
        android = (folder / "android_ci.yml").read_text(encoding="utf-8")
        self.assertNotIn("  build:\n", android)
        self.assertNotIn("  upload-telegram:\n", android)


class SigningPinTests(unittest.TestCase):
    def test_workflow_checks_all_reported_certificate_identities(self):
        workflow = Path(__file__).resolve().parents[1] / "workflows" / "vivo_upstream.yml"
        lines = workflow.read_text(encoding="utf-8").splitlines()
        extraction = next(line.strip() for line in lines if line.strip().startswith("actual_cert="))
        comparison = next(line.strip() for line in lines if line.strip().startswith('test "$actual_cert"'))
        if os.name == "nt":
            bash = Path(shutil.which("git")).resolve().parents[1] / "bin" / "bash.exe"
        else:
            bash = shutil.which("bash")
        expected = "a" * 64
        old = f"Signer #1 certificate SHA-256 digest: {expected}\n"
        current = f"V3.0 Signer: certificate SHA-256 digest: {expected}\n"
        foreign = f"V3.0 Signer: certificate SHA-256 digest: {'b' * 64}\n"
        for report, accepted in ((old, True), (current, True), (old + current, True),
                                 (old + foreign, False), ("No certificate\n", False)):
            with self.subTest(report=report), tempfile.TemporaryDirectory() as directory:
                Path(directory, "certificate.txt").write_text(report, encoding="utf-8")
                environment = dict(os.environ, RUNNER_TEMP=Path(directory).as_posix(),
                                   EXPECTED_CERT_SHA256=expected)
                result = subprocess.run(
                    [str(bash), "-e", "-o", "pipefail", "-c",
                     "PATH=/usr/bin:/bin:$PATH\n" + extraction + "\n" + comparison],
                    env=environment, capture_output=True, text=True,
                )
                self.assertEqual(accepted, result.returncode == 0, result.stderr)


if __name__ == "__main__":
    unittest.main()
