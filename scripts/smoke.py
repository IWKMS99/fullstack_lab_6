import json
import os
from pathlib import Path
from urllib.request import Request, build_opener, HTTPCookieProcessor, urlopen
from urllib.error import HTTPError
from http.cookiejar import CookieJar
from datetime import datetime, timedelta
import uuid
import time

BASE=os.environ.get('BASE_URL', 'http://localhost:8080')
config=dict(line.split('=',1) for line in Path(__file__).resolve().parents[1].joinpath('.env').read_text().splitlines() if '=' in line)
class Client:
 def __init__(self):
  self.cookies=CookieJar(); self.opener=build_opener(HTTPCookieProcessor(self.cookies)); self.token=None
 def request(self,method,path,body=None,content_type='application/json',expected=200):
  data=json.dumps(body).encode() if isinstance(body,dict) else body
  headers={'Content-Type':content_type}
  if self.token: headers['Authorization']='Bearer '+self.token
  try:
   response=self.opener.open(Request(BASE+path,data=data,headers=headers,method=method),timeout=20)
   status=response.status; payload=response.read()
  except HTTPError as error:
   status=error.code; payload=error.read()
  assert status==expected,(method,path,status,payload[:300])
  return json.loads(payload) if payload else None

for attempt in range(30):
 try:
  Client().request('GET','/api/v1/schedule?date='+datetime.now().date().isoformat()); break
 except Exception:
  if attempt==29: raise
  time.sleep(1)

guest=Client(); guest.request('GET','/api/v1/my-bookings',expected=401)
user=Client(); user.token=user.request('POST','/api/v1/auth/register',{'email':f'smoke-{uuid.uuid4()}@example.test','password':'smoke-password-123'})['token']
user.request('GET','/api/v1/admin/users',expected=403)
admin=Client(); admin.token=admin.request('POST','/api/v1/auth/login',{'email':config['BOOTSTRAP_ADMIN_EMAIL'],'password':config['BOOTSTRAP_ADMIN_PASSWORD']})['token']
room_name='Smoke-'+uuid.uuid4().hex[:12]
room=admin.request('POST','/api/v1/admin/rooms',{'name':room_name,'floor':8,'capacity':14},expected=201)
filtered=admin.request('GET','/api/v1/admin/rooms?search='+room_name+'&floor=8&minCapacity=10&sort=capacity,desc&page=0&size=1')
assert filtered['content'][0]['id']==room['id'] and filtered['totalElements']==1
admin.request('GET','/api/v1/admin/rooms?size=101',expected=400)
admin.request('PUT','/api/v1/admin/rooms/'+room['id'],{'name':room_name+'-updated','floor':9,'capacity':16})
start=(datetime.now()+timedelta(days=2)).replace(hour=10,minute=0,second=0,microsecond=0)
if start.month==1 and start.day==1:
 start+=timedelta(days=1)
booking=user.request('POST','/api/v1/bookings',{'roomId':room['id'],'startTime':start.isoformat(),'endTime':(start+timedelta(minutes=30)).isoformat()},expected=201)
user.request('DELETE','/api/v1/bookings/'+booking['id'],expected=204)
boundary='RoomFlowSmokeBoundary'
content=b'%PDF-1.4\n'+b'A'*(2*1024*1024)+b'\n%%EOF'
multipart=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="guide.pdf"\r\nContent-Type: application/pdf\r\n\r\n'.encode()+content+f'\r\n--{boundary}--\r\n'.encode())
file=admin.request('POST','/api/v1/admin/rooms/'+room['id']+'/files',multipart,'multipart/form-data; boundary='+boundary,expected=201)
assert urlopen(file['url']).read()==content
files=admin.request('GET','/api/v1/admin/rooms/'+room['id']+'/files'); assert files[0]['id']==file['id']
admin.request('DELETE','/api/v1/admin/rooms/files/'+file['id'],expected=204)
admin.request('DELETE','/api/v1/admin/rooms/'+room['id'],expected=204)
assert not admin.request('GET','/api/v1/admin/rooms?search='+room_name)['content']
admin.request('POST','/api/v1/auth/logout',expected=204)
admin.request('POST','/api/v1/auth/refresh',expected=401)
print('PASS: Nginx UI/API, bootstrap admin, 401/403, room filters/CRUD, booking/cancel, 2MiB S3 upload/download/delete, logout revocation')
