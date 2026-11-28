"""Guard against empty lock-wait diagnostics and unsupported database gates."""
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace
import unittest

spec=importlib.util.spec_from_file_location('recovery',Path(__file__).with_name('verify-process-recovery.py'))
recovery=importlib.util.module_from_spec(spec)
spec.loader.exec_module(recovery)


class RecoveryGateTest(unittest.TestCase):
    def gate(self,wire):
        gate=object.__new__(recovery.DatabaseGate)
        gate.helper=SimpleNamespace(sql=lambda query:wire)
        gate.table='tb_voucher_order'
        gate.connection_id=40
        return gate

    def test_no_waiters_is_a_valid_empty_snapshot(self):
        self.assertEqual([],self.gate('NULL').blocked_requests('12345'))

    def test_waiting_sql_must_identify_original_request(self):
        wire=json.dumps([dict(connectionId=99,state='Waiting for table metadata lock',
            sql='SELECT * FROM tb_voucher_order WHERE id=12345')])
        self.assertTrue(self.gate(wire).blocked_requests('12345')[0]['containsOriginalOrderId'])
        self.assertFalse(self.gate(wire).blocked_requests('67890')[0]['containsOriginalOrderId'])

    def test_unsupported_table_is_rejected_before_io(self):
        with self.assertRaises(AssertionError):
            recovery.DatabaseGate(None,'tb_user','test_marker')


if __name__=='__main__':
    unittest.main()
