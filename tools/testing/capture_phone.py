"""Read-only USB capture for a debuggable assistant build. Never sends touch or changes settings."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import time

sys.stdout.reconfigure(encoding='utf-8')

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--adb', default='adb')
p.add_argument('--serial', required=True)
p.add_argument('--seconds', type=int, default=180)
p.add_argument('--out', required=True)
args = p.parse_args()
if not 1 <= args.seconds <= 3600:
    p.error('--seconds must be 1..3600')
out = Path(args.out)
out.mkdir(parents=True, exist_ok=True)
app = 'com.example.autoswipeassistant'

def adb(*command, check=True):
    r = subprocess.run([args.adb, '-s', args.serial, *command], stdout=subprocess.PIPE,
                       stderr=subprocess.PIPE, timeout=15)
    if check and r.returncode:
        raise RuntimeError(r.stderr.decode('utf-8', errors='replace'))
    return r.stdout

assert adb('get-state').decode().strip() == 'device'
seen = set()
deadline = time.monotonic() + args.seconds
with (out / 'events.txt').open('a', encoding='utf-8') as events, (out / 'sampling.jsonl').open('a', encoding='utf-8') as samples:
    while time.monotonic() < deadline:
        try:
            raw = adb('exec-out', 'run-as', app, 'cat', 'no_backup/diagnostics.json')
            data = json.loads(raw.decode('utf-8'))
            (out / 'latest-diagnostics.json').write_bytes(raw)
            new = [e for e in data.get('events', []) if e not in seen]
            for e in new:
                seen.add(e)
                events.write(e + '\n\n')
                if '[广告手势]' in e or ('[广告循环]' in e and any(x in e for x in ('=SWIPE', '=LIMIT', '=CLEARED'))):
                    print(e, flush=True)
            events.flush()
            pid = adb('shell', 'pidof', app).decode().strip().split()[0]
            groups = adb('shell', 'cat', f'/proc/{pid}/cgroup', check=False).decode().splitlines()
            group = next((line[3:] for line in groups if line.startswith('0::')), '')
            freeze = adb('shell', 'cat', '/sys/fs/cgroup' + group + '/cgroup.freeze', check=False).decode().strip() if group else 'unknown'
            samples.write(json.dumps(dict(captured_at=time.strftime('%Y-%m-%d %H:%M:%S'), pid=pid,
                                          frozen=freeze, latest=data.get('events', [''])[-1]), ensure_ascii=False) + '\n')
            samples.flush()
        except (RuntimeError, json.JSONDecodeError, IndexError, subprocess.TimeoutExpired) as e:
            print('Capture delayed: ' + str(e), flush=True)
        time.sleep(min(2, max(0, deadline - time.monotonic())))
print('Capture complete: ' + str(out.resolve()), flush=True)
