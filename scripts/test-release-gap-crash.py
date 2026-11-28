import importlib.util
from pathlib import Path
import unittest

s = importlib.util.spec_from_file_location('gap', Path(__file__).with_name('verify-release-gap-crash.py'))
m = importlib.util.module_from_spec(s)
s.loader.exec_module(m)


class Checks(unittest.TestCase):
    def sample(self):
        return dict(orderStatus=4, orders=1, releaseEvents=1, closedEvents=1,
                    releaseEventId=4, releaseDone=0, releaseInbox=0,
                    databaseRemaining=2, redisRemaining='2', releaseMarker='123',
                    qualificationOrderId='123', releaseMarkerTtl=-1)

    def test_target_insert(self):
        self.assertTrue(m.target_insert("INSERT INTO tb_message_inbox(consumer_group,event_id) VALUES('activities',4)", 4))

    def test_other_event_not_target(self):
        self.assertFalse(m.target_insert("INSERT INTO tb_message_inbox(consumer_group,event_id) VALUES('activities',14)", 4))

    def test_other_group_not_target(self):
        self.assertFalse(m.target_insert("INSERT INTO tb_message_inbox(consumer_group,event_id) VALUES('orders',4)", 4))

    def test_read_not_insert(self):
        self.assertFalse(m.target_insert("SELECT COUNT(*) FROM tb_message_inbox WHERE consumer_group='activities' AND event_id=4", 4))

    def test_gate_refuses_unsafe_marker(self):
        with self.assertRaises(AssertionError):
            m.gate_statement("x';DROP TABLE x", 4)

    def test_observed_gap(self):
        m.validate_gap(self.sample(), '123', 4)

    def test_committed_inbox_not_gap(self):
        v = self.sample()
        v['releaseInbox'] = 1
        with self.assertRaises(AssertionError):
            m.validate_gap(v, '123', 4)

    def test_missing_release_not_gap(self):
        v = self.sample()
        v['releaseMarker'] = None
        with self.assertRaises(AssertionError):
            m.validate_gap(v, '123', 4)


if __name__ == '__main__':
    unittest.main()
