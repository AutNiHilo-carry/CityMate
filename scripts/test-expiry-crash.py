import importlib.util
from pathlib import Path
import unittest
s=importlib.util.spec_from_file_location('expiry',Path(__file__).with_name('verify-expiry-crash.py'))
m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
class Checks(unittest.TestCase):
    def pending(self):
        return dict(orderStatus=4,orders=1,databaseRemaining=2,redisRemaining='1',releaseEvents=1,closedEvents=1,releaseDone=0,releaseInbox=0,releaseMarker=None)
    def final(self):
        return dict(orderStatus=4,orders=1,successfulReservations=1,fulfillmentInbox=1,releaseEvents=1,closedEvents=1,releaseDone=1,releaseInbox=1,releaseEventId=9,releaseMarker='123',qualificationOrderId='123',releaseMarkerTtl=-1,databaseRemaining=2,redisRemaining='2',duplicateOwners=0,unfinishedFulfillmentEvents=0,streamPendingGlobal=0)
    def test_committed_gap(self):m.validate_pending(self.pending())
    def test_reject_already_released(self):
        v=self.pending();v['redisRemaining']='2'
        with self.assertRaises(AssertionError):m.validate_pending(v)
    def test_final(self):m.validate_final(self.final(),'123',9)
    def test_reject_double_return(self):
        v=self.final();v['redisRemaining']='3'
        with self.assertRaises(AssertionError):m.validate_final(v,'123',9)
    def test_reject_expiring_marker(self):
        v=self.final();v['releaseMarkerTtl']=300
        with self.assertRaises(AssertionError):m.validate_final(v,'123',9)
if __name__=='__main__':unittest.main()
