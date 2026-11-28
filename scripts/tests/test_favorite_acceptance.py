import importlib.util
from pathlib import Path
import tempfile
import unittest


spec = importlib.util.spec_from_file_location(
    'favorite_acceptance', Path(__file__).parents[1] / 'verify-favorite-database.py')
acceptance = importlib.util.module_from_spec(spec)
spec.loader.exec_module(acceptance)


class FavoriteAcceptancePlanTest(unittest.TestCase):
    def test_live_plan_only_passes_selected_credential_placeholders_and_starts_no_business_services(self):
        root = Path(__file__).resolve().parents[2]
        spec = importlib.util.spec_from_file_location(
            'live_support', root / 'scripts/verify-answer-support-live.py')
        live = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(live)
        with tempfile.TemporaryDirectory() as folder:
            plan = live.live_plan(root, Path(folder), True)
            self.assertEqual({'test'}, set(plan['services']))
            env = plan['services']['test']['environment']
            self.assertEqual({'CITYMATE_LIVE_REVIEW', 'DEEPSEEK_API_KEY', 'DEEPSEEK_MODEL'}, set(env))
            self.assertTrue(env['DEEPSEEK_API_KEY'].startswith('${DEEPSEEK_API_KEY:'))
            self.assertFalse(any('.env' in v for v in plan['services']['test']['volumes']))

    def test_only_selected_source_paths_are_mounted_read_only_and_credentials_are_not_loaded(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            plan = acceptance.build_compose(root, root / 'target/run', True)
            test = plan['services']['test']
            source_mounts = [v for v in test['volumes'] if ':/source/' in v]
            self.assertEqual(3, len(source_mounts))
            self.assertTrue(all(v.endswith(':ro') for v in source_mounts))
            self.assertFalse(any('.env' in v or ':/source:' in v for v in test['volumes']))
            self.assertEqual('false', test['environment']['CITYMATE_AI_ENABLED'])
            self.assertNotIn('DEEPSEEK_API_KEY', test['environment'])
            self.assertTrue(plan['volumes']['maven-cache']['external'])

    def test_database_cannot_resolve_to_business_host_or_publish_a_host_port(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            plan = acceptance.build_compose(root, root / 'target/run', False)
            self.assertEqual('hmdp_optimization_test',
                             plan['services']['mysql']['environment']['MYSQL_DATABASE'])
            self.assertTrue(plan['services']['test']['environment']['SPRING_DATASOURCE_URL']
                            .startswith('jdbc:mysql://mysql:3306/hmdp_optimization_test?'))
            self.assertTrue(all('ports' not in service for service in plan['services'].values()))
            self.assertEqual({}, plan['volumes']['maven-cache'])


if __name__ == '__main__':
    unittest.main()
