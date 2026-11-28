-- Keep the one-admission-per-user policy. Cancellation returns stock, not purchase eligibility.
-- KEYS: activity stock, user->order mapping, permanent release marker for this order.
local types={'string','hash','string'}
for i=1,3 do
  local t=redis.call('TYPE',KEYS[i]).ok
  if t~='none' and t~=types[i] then return -2 end
end
local released=redis.call('GET',KEYS[3])
if released then
  if released==ARGV[2] then return 0 else return -3 end
end
local stock=redis.call('GET',KEYS[1])
if not stock or not string.match(stock,'^%d+$') then return -1 end
if redis.call('HGET',KEYS[2],ARGV[1])~=ARGV[2] then return -3 end
redis.call('INCR',KEYS[1])
-- No TTL: an unacknowledged task may be replayed much later.
redis.call('SET',KEYS[3],ARGV[2])
return 1
