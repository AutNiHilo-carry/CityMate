import importlib.util
from pathlib import Path
import tempfile
import unittest


spec = importlib.util.spec_from_file_location('ci_runner', Path(__file__).parents[1] / 'run-ci.py')
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class EvidenceRunnerTest(unittest.TestCase):
    def test_new_unit_test_is_discovered_but_context_tests_are_excluded(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            source = root / 'src/test/java/example'
            source.mkdir(parents=True)
            for name, content in {
                'NewTest': 'package example; class NewTest {}',
                'DbTest': 'package example; @SpringBootTest class DbTest {}',
                'FlowIntegrationTest': 'package example; class FlowIntegrationTest {}',
            }.items():
                (source / (name + '.java')).write_text(content, encoding='utf-8')
            selected, excluded = runner.select_tests(root)
            self.assertEqual(['example.NewTest'], selected)
            self.assertEqual(2, len(excluded))

    def test_source_fingerprint_excludes_credentials_and_build_outputs(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'pom.xml').write_text('<project/>', encoding='utf-8')
            (root / '.env').write_text('SECRET=not-for-evidence', encoding='utf-8')
            (root / 'target').mkdir()
            (root / 'target/result.json').write_text('{}', encoding='utf-8')
            first = runner.fingerprint(root)
            self.assertEqual(['pom.xml'], list(first))
            (root / 'pom.xml').write_text('<project>changed</project>', encoding='utf-8')
            self.assertNotEqual(first, runner.fingerprint(root))

    def test_missing_or_failed_reports_cannot_be_mistaken_for_a_clean_run(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            with self.assertRaises(ValueError):
                runner.junit_summary(root)
            (root / 'TEST-example.xml').write_text(
                '<testsuite name="example.Test" tests="2" failures="1" errors="0" skipped="0"/>',
                encoding='utf-8')
            self.assertEqual(1, runner.junit_summary(root)[0]['failures'])


if __name__ == '__main__':
    unittest.main()
