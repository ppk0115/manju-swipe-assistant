"""Run real accessibility/gesture integration on a disposable emulator. No OCR required."""
import argparse
import json
from pathlib import Path
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument('--adb', default='adb')
parser.add_argument('--serial', default='emulator-5580')
parser.add_argument('--cases', nargs='*')
args = parser.parse_args()
if not args.serial.startswith('emulator-'):
    parser.error('Requires a disposable emulator: assistant preferences are replaced.')
APP = 'com.example.autoswipeassistant'
FIXTURE = 'com.example.landscapefixture'
SERVICE = APP + '/.AutoSwipeAccessibilityService'
out = Path('.diagnostics/ad-loop-regression')
out.mkdir(parents=True, exist_ok=True)

def adb(*command, data=None, check=True):
    result = subprocess.run([args.adb, '-s', args.serial, *command], input=data,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=35)
    if check and result.returncode:
        raise RuntimeError(result.stderr.decode('utf-8', errors='replace'))
    return result.stdout.decode('utf-8', errors='replace')

def setting(key, value):
    adb('shell', 'settings', 'put', 'secure', key, value)

def reset(case):
    setting('enabled_accessibility_services', 'null')
    adb('shell', 'am', 'force-stop', APP)
    adb('shell', 'am', 'force-stop', FIXTURE)
    prefs = ('<?xml version="1.0" encoding="utf-8"?><map>'
             '<boolean name="automation_enabled" value="true"/>'
             f'<boolean name="observe_only" value="{str(case.get("observe", False)).lower()}"/>'
             '<boolean name="diagnostics_enabled" value="true"/>'
             '<boolean name="candidate_scan_enabled" value="false"/>'
             # Deliberately retain the old OCR preference to verify it cannot activate OCR.
             '<boolean name="landscape_ocr_enabled" value="true"/>'
             + ('<boolean name="ad_badge_loop_enabled" value="false"/>' if case.get('legacy') else '') +
             '<boolean name="custom_rules_enabled" value="true"/>'
             '<string name="custom_prompts">上滑继续观看剧集</string>'
             f'<string name="target_packages">{FIXTURE}</string>'
             '<string name="active_pattern">default</string></map>')
    adb('shell', 'run-as', APP, 'mkdir', '-p', 'shared_prefs')
    adb('shell', 'run-as', APP, 'tee', 'shared_prefs/auto_swipe_settings.xml', data=prefs.encode())
    adb('shell', 'run-as', APP, 'rm', '-f', 'no_backup/diagnostics.json')
    adb('shell', 'am', 'start', '-n', APP + '/.MainActivity')
    setting('enabled_accessibility_services', SERVICE)
    setting('accessibility_enabled', '1')
    time.sleep(1.2)

def result():
    raw = adb('exec-out', 'run-as', FIXTURE, 'cat', 'shared_prefs/result.xml')
    return {n.attrib['name']: int(n.attrib['value']) if n.tag in ('int', 'long') else n.attrib.get('value', n.text)
            for n in ET.fromstring(raw)}

cases = [
    dict(name='ready_badge', mode='loop_ready', seconds=9, minimum=1, maximum=1, successes=1),
    dict(name='blank_video_after_ad', mode='loop_blank_after_success', seconds=9, minimum=1, maximum=1, successes=1),
    dict(name='countdown_ignored_by_player', mode='loop_countdown', seconds=12, minimum=2, maximum=6, successes=1, before_ready=True),
    dict(name='description_badge', mode='loop_description', seconds=9, minimum=1, maximum=1, successes=1),
    dict(name='normal_video', mode='normal_video', seconds=6, minimum=0, maximum=0, successes=0),
    dict(name='badge_substring', mode='loop_substring', seconds=6, minimum=0, maximum=0, successes=0),
    dict(name='hidden_badge', mode='loop_hidden', seconds=6, minimum=0, maximum=0, successes=0),
    dict(name='observe_only', mode='loop_ready', seconds=7, minimum=0, maximum=0, successes=0, observe=True),
    dict(name='temporary_absence', mode='loop_temporary', seconds=12, minimum=2, maximum=6, successes=1),
    dict(name='blank_read', mode='loop_blank', seconds=14, minimum=2, maximum=6, successes=1),
    dict(name='next_ad', mode='loop_next_ad', seconds=16, minimum=2, maximum=2, successes=2),
    dict(name='switch_after_attempt', mode='loop_persistent', seconds=8, minimum=1, maximum=1, successes=0, switch=True),
    dict(name='portrait', mode='loop_ready', seconds=9, minimum=1, maximum=1, successes=1, portrait=True),
    dict(name='legacy_prompt_mode', mode='countdown', seconds=10, minimum=1, maximum=1, legacy=True),
    dict(name='persistent_limit', mode='loop_persistent', seconds=98, minimum=2, maximum=60, successes=0, limit=True),
]
previous = adb('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services').strip()
previous_enabled = adb('shell', 'settings', 'get', 'secure', 'accessibility_enabled').strip()
results = []
try:
    setting('immersive_mode_confirmations', 'confirmed')
    for case in cases:
        name = case['name']
        if args.cases and name not in args.cases:
            continue
        print('RUN ' + name, flush=True)
        reset(case)
        adb('shell', 'am', 'start', '-n', FIXTURE + '/.FixtureActivity', '--es', 'mode', case['mode'],
            '--ez', 'portrait', str(case.get('portrait', False)).lower())
        deadline = time.monotonic() + case['seconds']
        switched = False
        count_when_switched = None
        while time.monotonic() < deadline:
            if case.get('switch') and not switched:
                current = result()
                # Do not react to the previous activity's asynchronously persisted result.
                if current.get('mode') == case['mode'] and current.get('gestures', 0) > 0:
                    count_when_switched = current['gestures']
                    adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
                    switched = True
            time.sleep(.5)
        row = result()
        diag = adb('exec-out', 'run-as', APP, 'cat', 'no_backup/diagnostics.json', check=False)
        (out / (name + '-diagnostics.json')).write_text(diag, encoding='utf-8')
        row.update(case=name)
        count = row.get('gestures', 0)
        checks = dict(count=case['minimum'] <= count <= case['maximum'])
        if 'successes' in case:
            checks['successes'] = row.get('successes', 0) == case['successes']
        if count > 1:
            checks['one_second_after_completion'] = row.get('min_start_gap_ms', 0) >= 1000
        if case.get('successes'):
            checks['no_swipe_after_badge_disappears'] = count == row.get('gestures_at_clear')
            checks['three_absent_reads'] = 'CLEARED' in diag
        if case.get('before_ready'):
            checks['attempt_during_countdown'] = 0 < row.get('first_gesture_at', 0) < row.get('ready_at', 0)
        if case.get('limit'):
            checks['limit_reached'] = 'LIMIT' in diag
        if case.get('observe'):
            checks['actually_observed'] = '观察模式：识别广告' in diag
        if case.get('switch'):
            checks['no_gesture_after_switch'] = switched and count == count_when_switched
        if case.get('legacy'):
            checks['no_early_legacy_swipe'] = row.get('early') == 'false'
        checks['no_ocr_events'] = '[OCR]' not in diag
        row['checks'] = checks
        row['passed'] = all(checks.values())
        results.append(row)
        (out / 'results.json').write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps(row, ensure_ascii=False), flush=True)
finally:
    setting('enabled_accessibility_services', previous or 'null')
    setting('accessibility_enabled', previous_enabled or '0')
print(f'{sum(r["passed"] for r in results)}/{len(results)} passed', flush=True)
raise SystemExit(0 if results and all(r['passed'] for r in results) else 1)
