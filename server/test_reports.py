import copy, importlib.util, json, sys, tempfile, time, types, unittest, uuid
from pathlib import Path
from fastapi import FastAPI, HTTPException, Request
from fastapi.testclient import TestClient

# Isolate the collector from production account storage. Authorization itself is
# also checked against the deployed real admin dependency before releasing.
admin=types.ModuleType('routers.admin')
def require_admin(request: Request):
    if request.headers.get('x-test-admin') != 'yes':
        raise HTTPException(401)
admin.require_admin=require_admin
sys.modules['routers']=types.ModuleType('routers')
sys.modules['routers.admin']=admin
import app_reports as reports

class ReportsTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        reports.DB_PATH=Path(self.temp.name)/'reports.sqlite3'
        reports._limits.clear()
        app=FastAPI(); app.include_router(reports.router)
        self.client=TestClient(app)
        self.report=dict(id=str(uuid.uuid4()),kind='crash',operation='uncaught',app_version='1.3.2',platform='android',os_version='7.0',occurred_at_ms=int(time.time()*1000),exception_type='java.lang.IllegalStateException',frames=['org.olcbox.Test.fail:42'],causes=[])
    def tearDown(self):
        self.client.close(); self.temp.cleanup()
    def test_delivery_idempotency_and_authorized_read(self):
        for _ in range(2):
            self.assertEqual(self.client.post('/api/app-reports',json=self.report).status_code,202)
        rows=self.client.get('/api/admin/app-reports/json',headers={'x-test-admin':'yes'}).json()['reports']
        self.assertEqual(len(rows),1); self.assertEqual(rows[0]['report'],self.report)
        self.assertEqual(self.client.get('/api/admin/app-reports').status_code,401)
        self.assertEqual(self.client.get('/api/admin/app-reports/json').status_code,401)
        page=self.client.get('/api/admin/app-reports',headers={'x-test-admin':'yes'})
        self.assertEqual(page.status_code,200); self.assertIn('Отчёты AKULA VPN',page.text)
        self.assertEqual(page.headers['cache-control'],'no-store')
    def test_privacy_schema_and_size(self):
        for field,value in [('message','private-key'),('ip','1.2.3.4'),('url','https://private')]:
            data={**self.report,field:value}
            self.assertEqual(self.client.post('/api/app-reports',json=data).status_code,422)
        for field,value in [('frames',['<script>alert(1)</script>']),('operation','https://private'),('occurred_at_ms',True),('id',123),('causes',None)]:
            self.assertEqual(self.client.post('/api/app-reports',json={**self.report,field:value}).status_code,422)
        self.assertEqual(self.client.post('/api/app-reports',content=b'x'*32769).status_code,413)
        self.assertEqual(self.client.post('/api/app-reports',content=b'invalid').status_code,422)
        self.assertEqual(reports._rows(),[])
    def test_limits(self):
        for _ in range(120):
            self.assertEqual(self.client.post('/api/app-reports',json=self.report).status_code,202)
        self.assertEqual(self.client.post('/api/app-reports',json=self.report).status_code,429)

    def test_structured_context_grouping_and_privacy(self):
        context=dict(protocol='amnezia_wg',stage='wait_handshake',reason='handshake_timeout',elapsed_ms=15020,attempt=1)
        data={**self.report, 'id':str(uuid.uuid4()), 'app_version':'1.3.3', 'context':context, 'exception_type':'diagnostic.event', 'frames':[]}
        self.assertEqual(self.client.post('/api/app-reports',json=data).status_code,202)
        other={**data,'id':str(uuid.uuid4()),'context':{**context,'elapsed_ms':15100}}
        self.assertEqual(self.client.post('/api/app-reports',json=other).status_code,202)
        auth={**data,'id':str(uuid.uuid4()),'context':dict(protocol='openvpn',stage='authenticate',reason='auth_rejected')}
        self.assertEqual(self.client.post('/api/app-reports',json=auth).status_code,202)
        rows=reports._rows()
        self.assertEqual(rows[1]['fingerprint'],rows[2]['fingerprint'])
        self.assertNotEqual(rows[0]['fingerprint'],rows[1]['fingerprint'])
        page=self.client.get('/api/admin/app-reports',headers={'x-test-admin':'yes'}).text
        self.assertIn('Нет рукопожатия',page)
        self.assertIn('15.02 с',page)
        self.assertIn('Сервер отклонил авторизацию',page)
        for bad in [{**context,'message':'secret'}, {**context,'protocol':'https://private'}, {**context,'elapsed_ms':True}, {**context,'reason':['unknown']}, {**context,'attempt':0}, {**context,'exit_code':2147483648}, {**context,'elapsed_ms':86400001}, None]:
            self.assertEqual(self.client.post('/api/app-reports',json={**data,'context':bad}).status_code,422)

if __name__=='__main__': unittest.main(verbosity=2)
