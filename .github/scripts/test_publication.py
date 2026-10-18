import contextlib
import io
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import check_publication as scanner


class PublicationTests(unittest.TestCase):
    def test_private_identifier_boundaries(self):
        self.assertEqual(scanner.check_line("device=workstation-example", ["workstation-example"]), {"private identifier"})
        self.assertEqual(scanner.check_line("workstation-examples", ["workstation-example"]), set())

    def test_addresses_and_home_paths(self):
        address = ".".join(["192", "168", "10", "25"])
        home = "/" + "home/" + "developer/project"
        self.assertEqual(scanner.check_line(address, []), {"IP address"})
        self.assertEqual(scanner.check_line(home, []), {"local home path"})
        address6 = ":".join(["fd00", "", "123"])
        self.assertEqual(scanner.check_line(address6, []), {"IP address"})
        self.assertEqual(scanner.check_line("val handler = Foo::bar", []), set())

    def test_deleted_information_remains_blocked_in_history(self):
        with tempfile.TemporaryDirectory() as directory:
            def git(*args):
                return subprocess.check_output(["git", *args], cwd=directory, text=True).strip()
            git("init", "-q")
            git("config", "user.name", "Publication test")
            git("config", "user.email", "test@example.invalid")
            path = Path(directory) / "README.md"
            path.write_text("Public documentation\n")
            git("add", ".")
            git("commit", "-qm", "Base")
            base = git("rev-parse", "HEAD")
            path.write_text("workstation-example\n")
            git("commit", "-qam", "Add configuration")
            path.write_text("Public documentation\n")
            git("commit", "-qam", "Remove configuration")
            self.assertEqual(scanner.check_history(base, "HEAD", ["workstation-example"], directory), {"private identifier"})
            self.assertEqual(scanner.check_history(base, "HEAD", [], directory), set())

    def test_required_policy_is_not_silently_skipped(self):
        with patch.dict(os.environ, {"PUBLICATION_DENYLIST": "", "REQUIRE_PRIVATE_POLICY": "true"}):
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(scanner.main([]), 1)

    def test_findings_do_not_echo_private_values(self):
        output = io.StringIO()
        with patch.dict(os.environ, {"PUBLICATION_DENYLIST": '["workstation-example"]'}):
            with patch.object(scanner, "check_history", return_value={"private identifier"}):
                with contextlib.redirect_stderr(output):
                    self.assertEqual(scanner.main([]), 1)
        self.assertNotIn("workstation-example", output.getvalue())


if __name__ == "__main__":
    unittest.main()
