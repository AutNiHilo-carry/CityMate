"""Regression checks for live broker offsets and its Fastjson offset file."""
import importlib.util
from pathlib import Path
import unittest

spec=importlib.util.spec_from_file_location('ack',Path(__file__).with_name('verify-commit-ack-loss.py'))
ack=importlib.util.module_from_spec(spec);spec.loader.exec_module(ack)


class BrokerEvidenceTest(unittest.TestCase):
    def progress(self,consumer):
        return '\n'.join('HmdpBusiness hmdp-broker %d %d %d %d 0 N/A' %
            (q,1 if q==2 else 0,consumer if q==2 else 0,(1-consumer) if q==2 else 0) for q in range(4))

    def test_selects_committed_consumer_offset_not_queue_maximum(self):
        snapshot={'rows':ack.parse_progress(self.progress(0))}
        row=ack.selected(snapshot,2)
        self.assertEqual(1,row['brokerOffset'])
        self.assertEqual(0,row['consumerOffset'])

    def test_progress_must_include_all_four_expected_queues(self):
        with self.assertRaises(AssertionError):ack.parse_progress(self.progress(0).splitlines()[0])

    def test_standard_json_offset_file(self):
        text='{"offsetTable":{"HmdpBusiness@'+ack.GROUP+'":{"2":1}}}'
        self.assertEqual(1,ack.parse_offset_file(text,2)['selectedStoredOffset'])

    def test_fastjson_integer_map_keys(self):
        text='{"offsetTable":{"HmdpBusiness@'+ack.GROUP+'":{2:0,3:4}}}'
        value=ack.parse_offset_file(text,2)
        self.assertEqual(0,value['selectedStoredOffset'])
        self.assertTrue(value['numericKeysNormalized'])

    def test_absent_offset_remains_absent(self):
        self.assertIsNone(ack.parse_offset_file('{"offsetTable":{}}',2)['selectedStoredOffset'])


if __name__=='__main__':unittest.main()
