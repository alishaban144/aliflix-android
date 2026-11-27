import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import mobile_release as release


class ReleaseTest(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory()
        self.addCleanup(self.scratch.cleanup)
        self.bundle = Path(self.scratch.name)
        self.tag = "v3.1.210"
        self.commit = "a" * 40
        self.notes = "Offline playback fixes\n\nMore details"
        self.digests = {asset: f"sha256:{'b' * 64}" for asset in release.ASSETS}
        self.draft = {
            "draft": True,
            "target_commitish": self.commit,
            "assets": [{"name": name, "digest": digest} for name, digest in self.digests.items()],
            "body": self.notes + "\n",
            "html_url": "https://github.example/release",
        }
        self.start(patch.object(release, "BUNDLE", self.bundle))
        self.start(patch.dict(os.environ, {
            "GITHUB_EVENT_NAME": "workflow_dispatch",
            "GITHUB_REPOSITORY": "owner/repo",
            "GITHUB_OUTPUT": str(self.bundle / "outputs"),
            "GITHUB_STEP_SUMMARY": str(self.bundle / "summary"),
            "DRAFT": "false",
            "BACKEND_ONLY": "false",
            "REUSE_RUN": "",
            "REQUESTED_TAG": "",
            "REQUESTED_NOTES": "",
        }))
        self.start(patch.object(release, "version", return_value=("3.1.210", 300)))
        self.gh = self.start(patch.object(release, "release_for_tag", return_value=self.draft))
        self.shell = self.start(patch.object(release, "command", side_effect=self.command))

    def start(self, context):
        self.addCleanup(context.stop)
        return context.start()

    def command(self, *args, **kwargs):
        if args == ("git", "rev-parse", "HEAD"):
            return self.commit
        if args[:3] == ("git", "rev-parse", "--verify"):
            return None
        if args[:3] == ("gh", "release", "download"):
            return '{"versionCode":299}'
        if args[:2] == ("git", "diff"):
            return ""
        raise AssertionError(args)

    def receipt(self, **overrides):
        release.write_json(self.bundle / "receipt.json", {
            "tag": self.tag,
            "commit": self.commit,
            "files": self.digests,
            "notes_sha256": hashlib.sha256(self.notes.encode()).hexdigest(),
            **overrides,
        })

    def test_dispatch_derives_tag_and_uses_latest_published_release(self):
        with patch.object(release, "api", return_value={"tag_name": "v3.1.209"}):
            release.prepare()
        outputs = (self.bundle / "outputs").read_text()
        self.assertIn(f"tag={self.tag}\n", outputs)
        self.assertIn("worker=false\n", outputs)
        self.shell.assert_any_call("git", "diff", "--name-only", "refs/tags/v3.1.209",
                                   self.commit, "--", "recommendation-worker")

    def test_already_published_tag_stops_before_packaging(self):
        self.draft["draft"] = False
        with self.assertRaisesRegex(ValueError, "already published"):
            release.prepare()
        self.assertFalse((self.bundle / "outputs").exists())

    def test_stale_version_code_and_conflicting_draft_stop_preflight(self):
        with patch.object(release, "version", return_value=("3.1.210", 299)), \
                patch.object(release, "api", return_value={"tag_name": "v3.1.209"}):
            with self.assertRaisesRegex(ValueError, "greater than"):
                release.prepare()
        self.draft["target_commitish"] = "b" * 40
        with self.assertRaisesRegex(ValueError, "different commit"):
            release.prepare()

    def test_reuse_rejects_run_from_another_commit(self):
        with patch.dict(os.environ, {"REUSE_RUN": "123"}), patch.object(release, "api", return_value={
            "head_sha": "b" * 40,
            "path": release.WORKFLOW,
            "conclusion": "success",
            "head_repository": {"full_name": "owner/repo"},
        }):
            with self.assertRaisesRegex(ValueError, "same commit"):
                release.prepare()

    def test_promote_checks_digests_and_pins_tag_before_publishing(self):
        self.receipt()
        with patch.object(release, "pin_tag") as pin, patch.object(release, "command") as shell:
            shell.side_effect = lambda *args: self.commit if args[0] == "git" else ""
            release.publish()
        pin.assert_called_once_with(self.tag, self.commit)
        shell.assert_any_call("gh", "release", "edit", self.tag, "--repo", "owner/repo",
                              "--verify-tag", "--draft=false", "--latest")

    def test_promote_rejects_changed_assets_or_notes(self):
        self.receipt()
        self.draft["assets"][0]["digest"] = "sha256:changed"
        with self.assertRaisesRegex(ValueError, "assets differ"):
            release.verify_bundle()
        self.draft["assets"][0]["digest"] = self.digests[release.MOBILE]
        self.draft["body"] = "Changed notes"
        with self.assertRaisesRegex(ValueError, "notes differ"):
            release.verify_bundle()

    def test_verified_draft_gets_a_tag_and_promotion_run_id(self):
        self.receipt()
        with patch.dict(os.environ, {"DRAFT": "true", "GITHUB_RUN_ID": "123"}), \
                patch.object(release, "pin_tag") as pin:
            release.publish()
        pin.assert_called_once_with(self.tag, self.commit)
        self.assertIn(f"at {self.tag} with reuse_run set to 123", (self.bundle / "summary").read_text())

    def test_manifests_keep_both_updater_routes(self):
        for name in (release.MOBILE, release.BRIDGE, "native-parity.json"):
            (self.bundle / name).write_bytes(name.encode())
        with patch.dict(os.environ, {"REQUESTED_NOTES": self.notes}), \
                patch.object(release, "upload_changed_assets"), patch.object(release, "verify_bundle"), \
                patch.object(release, "command", return_value=self.commit):
            release.make_bundle()
        current = json.loads((self.bundle / "update-mobile-arm64.json").read_text())
        bridge = json.loads((self.bundle / "update-mobile.json").read_text())
        self.assertEqual(current["versionCode"], 300)
        self.assertEqual(current["supportedAbis"], ["arm64-v8a"])
        self.assertEqual(current["releaseNotes"], self.notes)
        self.assertEqual(bridge["versionCode"], 265)
        self.assertNotIn("supportedAbis", bridge)
        self.assertTrue(bridge["apkUrl"].endswith(f"/{self.tag}/{release.BRIDGE}"))
        self.assertEqual(bridge["sha256"], release.sha256(self.bundle / release.BRIDGE))


if __name__ == "__main__":
    unittest.main()
