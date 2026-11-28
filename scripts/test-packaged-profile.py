"""Regression checks for Redis CLI decoding without requiring Docker."""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('fixture', Path(__file__).with_name('profile-packaged-transaction.py'))
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)


class RedisCliDecodingTest(unittest.TestCase):
    def test_info_text_is_preserved(self):
        text = '# Commandstats\ncmdstat_get:calls=1\n'
        with patch.object(fixture, 'command', return_value=SimpleNamespace(stdout=text)):
            self.assertEqual(text, fixture.redis('INFO', 'commandstats'))

    def test_structured_values_still_decode_as_json(self):
        for wire, expected, args in [
            ('[0,null,null,null]', [0,None,None,None], ['XPENDING','stream.orders','g1']),
            ('"0"', '0', ['GET','seckill:stock:970600']),
            ('["appendfsync","always"]', ['appendfsync','always'], ['CONFIG','GET','appendfsync'])
        ]:
            with self.subTest(args=args), patch.object(fixture, 'command', return_value=SimpleNamespace(stdout=wire)):
                self.assertEqual(expected, fixture.redis(*args))


if __name__ == '__main__':
    unittest.main()
