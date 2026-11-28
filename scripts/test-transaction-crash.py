"""Check that the new diagnostic cannot mistake a pre-write SELECT for an INSERT wait."""
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace
import unittest

spec=importlib.util.spec_from_file_location('tx_recovery',Path(__file__).with_name('verify-transaction-crash.py'))
tx=importlib.util.module_from_spec(spec)
spec.loader.exec_module(tx)


class TransactionGateTest(unittest.TestCase):
    def waiters(self,wire,order='12345'):
        gate=object.__new__(tx.OrderInsertGapGate)
        gate.helper=SimpleNamespace(sql=lambda query:wire)
        gate.connection_id=40
        return gate.blocked_requests(order)

    def test_empty_transaction_snapshot_is_valid(self):
        self.assertEqual([],self.waiters('NULL'))

    def test_select_is_not_an_after_update_insert_checkpoint(self):
        row=dict(transactionId='101',connectionId=42,state='LOCK WAIT',rowsModified=1,
            sql='SELECT * FROM tb_voucher_order WHERE id=12345')
        self.assertFalse(self.waiters(json.dumps([row]))[0]['isOrderInsert'])

    def test_insert_requires_original_request_identity(self):
        row=dict(transactionId='101',connectionId=42,state='LOCK WAIT',rowsModified=1,
            sql='INSERT INTO tb_voucher_order (id,user_id) VALUES (12345,7)')
        value=self.waiters(json.dumps([row]))[0]
        self.assertTrue(value['isOrderInsert'] and value['containsOriginalOrderId'])
        self.assertFalse(self.waiters(json.dumps([row]),'67890')[0]['containsOriginalOrderId'])

    def test_gate_marker_rejects_sql_injection_before_io(self):
        with self.assertRaises(AssertionError):
            tx.gate_statement("unsafe'; DROP TABLE x")


if __name__=='__main__':
    unittest.main()
