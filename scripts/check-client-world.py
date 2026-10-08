#!/usr/bin/env python3
"""Optional real marshaller/network check. Creates temporary state and always stops its server."""
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time

import sys
project = Path(__file__).resolve().parent.parent
if len(sys.argv)!=2 or not (Path(sys.argv[1]) / 'code/projectx-pcode.jar').is_file():
    raise SystemExit('Usage: python3 scripts/check-client-world.py "/path/to/Spiral Knights"')
game = Path(sys.argv[1]).resolve()
if not (project / 'build/tortodrone.jar').is_file():
    raise SystemExit('Build first with make build')
with tempfile.TemporaryDirectory(prefix='tortodrone-installed-client-') as name:
    scratch = Path(name)
    shutil.copy2(project / 'build/tortodrone.jar', scratch / 'tortodrone.jar')
    shutil.copy2(project / 'build/tortodrone-admin.jar', scratch / 'tortodrone-admin.jar')
    compile_classpath = ':'.join([str(game / 'code/projectx-pcode.jar'), str(game / 'code/*'), str(project / 'build/tortodrone.jar')])
    subprocess.run(['javac','--release','17','-cp',compile_classpath,'-d',str(scratch),str(project / 'scripts/compat/WorldConnectionCheck.java'),str(project / 'scripts/compat/WorldCompatibility.java')],check=True)
    reader = subprocess.run(['java','--add-opens=java.base/java.lang=ALL-UNNAMED',
        '--add-opens=java.base/java.util=ALL-UNNAMED','-Djava.awt.headless=true',
        '-Dno_unpack_resources=true','-Duser.home='+str(scratch / 'home'),
        '-Djava.util.prefs.userRoot='+str(scratch / 'home/prefs'),'-cp',str(scratch)+':'+compile_classpath,
        'WorldCompatibility',str(game)],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,timeout=30)
    if reader.returncode:
        print(reader.stdout[-8000:])
        raise RuntimeError('Installed reader check failed')
    for line in reader.stdout.splitlines():
        if line.startswith('PASS:'): print(line)
    (scratch / 'data').mkdir()
    (scratch / 'home').mkdir()
    (scratch / 'data/server.properties').write_text('server-ip=127.0.0.1\nserver-port=0\nlog-mode=debug\n')
    subprocess.run(['java', '-jar', str(scratch / 'tortodrone-admin.jar'), '--create-account', 'LanPlayer'], input='lan-test-password\nlan-test-password\n', text=True, cwd=scratch, check=True, stdout=subprocess.PIPE)
    with (scratch / 'server-output.log').open('w') as output:
        server = subprocess.Popen(['java', '-jar', str(scratch / 'tortodrone.jar')], cwd=scratch, stdout=output, stderr=subprocess.STDOUT)
        try:
            port = None
            deadline = time.monotonic() + 15
            while time.monotonic() < deadline:
                text = (scratch / 'server-output.log').read_text()
                match = re.search(r'Listening on 127\.0\.0\.1:(\d+)', text)
                if match:
                    port = match.group(1)
                    break
                if server.poll() is not None:
                    raise RuntimeError('Temporary server failed: ' + text)
                time.sleep(.05)
            if port is None:
                raise RuntimeError('Temporary server readiness timeout')
            classpath = ':'.join([str(scratch), str(game / 'code/projectx-pcode.jar'), str(game / 'code/*'), str(project / 'build/tortodrone.jar')])
            command = ['java', '--add-opens=java.base/java.lang=ALL-UNNAMED', '--add-opens=java.base/java.util=ALL-UNNAMED', '-Djava.awt.headless=true', '-Duser.home=' + str(scratch / 'home'), '-Djava.util.prefs.userRoot=' + str(scratch / 'home/prefs'), '-cp', classpath, 'WorldConnectionCheck', port, str(scratch / 'data/keys/public.txt')]
            result = subprocess.run(command, cwd=scratch, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=35)
            if result.returncode:
                print(result.stdout[-9000:]); print((scratch / 'server-output.log').read_text()[-6000:])
                raise RuntimeError('Installed client check failed')
            for line in result.stdout.splitlines():
                if line.startswith('PASS:'):
                    print(line)
        finally:
            server.terminate()
            try:
                server.wait(timeout=5)
            except subprocess.TimeoutExpired:
                server.kill()
                server.wait()
