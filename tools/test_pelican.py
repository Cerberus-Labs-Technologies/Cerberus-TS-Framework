#!/usr/bin/env python3
"""Validate generated eggs, boot script persistence and a real distribution/container lifecycle."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import queue
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
DEPLOY = ROOT / 'deploy/pelican'


def environment(**overrides):
    result = {k: v for k, v in os.environ.items() if not k.startswith('CERBERUS_')}
    result.update(SERVER_PORT='24875', CERBERUS_TRANSPORT='RAW', CERBERUS_BUNDLED_PLUGINS='welcome,music')
    result.update(overrides)
    return result


def prepare(base, distribution=None):
    runtime = base / 'runtime'
    runtime.mkdir()
    shutil.copy2(DEPLOY / 'start.sh', runtime / 'start.sh')
    if distribution:
        (runtime / 'app').symlink_to(distribution.resolve(), target_is_directory=True)
    else:
        (runtime / 'app/bin').mkdir(parents=True)
        (runtime / 'app/config').mkdir()
        (runtime / 'app/config/cerberus.example.properties').write_text('host=example.test\n')
        launcher = runtime / 'app/bin/cerberus-ts'
        launcher.write_text('#!' + sys.executable + '\n' + '''import json, os, pathlib, sys
with pathlib.Path('invocations.jsonl').open('a') as output:
 output.write(json.dumps({'args': sys.argv[1:], 'env': {k:v for k,v in os.environ.items() if k.startswith('CERBERUS_') or k=='JAVA_OPTS'}})+'\\n')
''')
        launcher.chmod(0o755)
    (runtime / 'bundled').mkdir()
    for plugin in ('welcome', 'music', 'server-setup'):
        (runtime / f'bundled/{plugin}-plugin.jar').write_bytes(('bundled-' + plugin).encode())
    server = base / 'server'
    server.mkdir()
    return runtime, server


class PelicanTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='cerberus-pelican-')
        self.addCleanup(self.temporary.cleanup)
        self.runtime, self.server = prepare(Path(self.temporary.name))

    def boot(self, **overrides):
        return subprocess.run(['bash', str(self.runtime / 'start.sh')], cwd=self.server,
                              env=environment(**overrides), capture_output=True, text=True, timeout=10)

    def test_generated_exports_match_and_use_supported_contract(self):
        spec = importlib.util.spec_from_file_location('cerberus_egg_generator', DEPLOY / 'generate.py')
        generator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(generator)
        for name, expected in generator.eggs().items():
            self.assertEqual(expected, json.loads((DEPLOY / name).read_text()))
            config = expected['config']
            self.assertEqual('[cerberus] CONNECTED', json.loads(config['startup'])['done'])
            self.assertEqual('stop', config['stop'])
            self.assertEqual((DEPLOY / 'install.sh').read_text(), expected['scripts']['installation']['script'])
            variables = expected['variables']
            self.assertEqual(len(variables), len({v['env_variable'] for v in variables}))
            self.assertFalse({'HOME', 'SERVER_PORT', 'STARTUP', 'SERVER_MEMORY'} & {v['env_variable'] for v in variables})
            for v in variables:
                if any(word in v['env_variable'] for word in ('PASSWORD', 'API_KEY')):
                    self.assertEqual('', v['default_value'])
            self.assertNotIn('server-setup', next(v['default_value'] for v in variables if v['env_variable']=='CERBERUS_BUNDLED_PLUGINS'))

    def test_boot_uses_primary_allocation_and_persistent_paths(self):
        result = self.boot(SERVER_PORT='29143')
        self.assertEqual(0, result.returncode, result.stderr)
        calls = [json.loads(line) for line in (self.server/'invocations.jsonl').read_text().splitlines()]
        self.assertEqual(['--config','config/cerberus.properties','--check-config'], calls[0]['args'])
        self.assertEqual(['--config','config/cerberus.properties','--console'], calls[1]['args'])
        env = calls[1]['env']
        self.assertEqual('29143', env['CERBERUS_WEB_PORT'])
        self.assertEqual('29143', env['CERBERUS_REST_PORT'])
        self.assertEqual('0.0.0.0', env['CERBERUS_WEB_HOST'])
        self.assertEqual(str((self.server/'data').resolve()), env['CERBERUS_DATA_DIRECTORY'])
        self.assertEqual({'cerberus-bundled-welcome.jar','cerberus-bundled-music.jar'}, {p.name for p in (self.server/'plugins').iterdir()})
        self.assertTrue((self.server/'data/music/files').is_dir())
        self.assertFalse((self.server/'config/cerberus.properties').stat().st_mode & 0o077)

    def test_restart_preserves_operator_files_and_refreshes_managed_plugins(self):
        self.assertEqual(0,self.boot().returncode)
        config = self.server/'config/cerberus.properties'
        config.write_text('custom=true\n')
        custom = self.server/'plugins/custom.jar'
        custom.write_bytes(b'operator-plugin')
        identity = self.server/'data/music/identity'
        identity.write_bytes(b'persistent-identity')
        (self.runtime/'bundled/music-plugin.jar').write_bytes(b'updated-music')
        self.assertEqual(0,self.boot(CERBERUS_BUNDLED_PLUGINS='music').returncode)
        self.assertEqual('custom=true\n',config.read_text())
        self.assertEqual(b'operator-plugin',custom.read_bytes())
        self.assertEqual(b'persistent-identity',identity.read_bytes())
        self.assertEqual(b'updated-music',(self.server/'plugins/cerberus-bundled-music.jar').read_bytes())
        self.assertFalse((self.server/'plugins/cerberus-bundled-welcome.jar').exists())

    def test_setup_is_explicit_and_none_disables_bundled_plugins(self):
        self.assertEqual(0,self.boot(CERBERUS_BUNDLED_PLUGINS='server-setup').returncode)
        self.assertEqual(['cerberus-bundled-server-setup.jar'],[p.name for p in (self.server/'plugins').iterdir()])
        self.assertEqual(0,self.boot(CERBERUS_BUNDLED_PLUGINS='none').returncode)
        self.assertEqual([],list((self.server/'plugins').iterdir()))

    def test_bad_ports_and_plugin_choices_fail_before_boot(self):
        for port in ('0','65536','hello','24875; touch injected'):
            with self.subTest(port=port):
                self.assertNotEqual(0,self.boot(SERVER_PORT=port).returncode)
        for selection in ('music,','music,,welcome','unknown','$(touch injected)'):
            with self.subTest(selection=selection):
                self.assertNotEqual(0,self.boot(CERBERUS_BUNDLED_PLUGINS=selection).returncode)
        self.assertFalse((self.server/'invocations.jsonl').exists())
        self.assertFalse((self.server/'injected').exists())

    def test_ssh_requires_a_host_key_and_does_not_discover_it(self):
        self.assertNotEqual(0,self.boot(CERBERUS_TRANSPORT='SSH').returncode)
        (self.server/'config/known_hosts').write_text('# verify a key first\n')
        self.assertNotEqual(0,self.boot(CERBERUS_TRANSPORT='SSH').returncode)
        self.assertFalse((self.server/'invocations.jsonl').exists())
        (self.server/'config/known_hosts').write_text('[example.test]:24873 ssh-ed25519 AAAATESTFIXTURE\n')
        self.assertEqual(0,self.boot(CERBERUS_TRANSPORT='SSH').returncode)

    def test_empty_optional_fields_do_not_override_file_and_secrets_are_not_echoed(self):
        secret='fixture-only-$(touch injected)-!'
        result=self.boot(CERBERUS_PASSWORD=secret,CERBERUS_API_KEY='',CERBERUS_ADMIN_UID='',CERBERUS_VOICE_PASSWORD='')
        self.assertEqual(0,result.returncode,result.stderr)
        call=json.loads((self.server/'invocations.jsonl').read_text().splitlines()[-1])
        self.assertEqual(secret,call['env']['CERBERUS_PASSWORD'])
        self.assertFalse({'CERBERUS_API_KEY','CERBERUS_ADMIN_UID','CERBERUS_VOICE_PASSWORD'} & call['env'].keys())
        self.assertNotIn(secret,result.stdout+result.stderr)
        self.assertFalse((self.server/'injected').exists())

    def test_reserved_symlink_is_not_overwritten(self):
        self.assertEqual(0,self.boot().returncode)
        target=self.server/'plugins/cerberus-bundled-music.jar'
        target.unlink()
        outside=self.server/'keep.jar'
        outside.write_bytes(b'keep')
        target.symlink_to(outside)
        self.assertNotEqual(0,self.boot().returncode)
        self.assertEqual(b'keep',outside.read_bytes())


def smoke(distribution=None,image=None):
    with tempfile.TemporaryDirectory(prefix='cerberus-pelican-live-') as temporary, socket.socket() as listener, socket.socket() as allocation:
        base=Path(temporary)
        listener.bind(('127.0.0.1',0));listener.listen(1);listener.settimeout(30)
        allocation.bind(('127.0.0.1',0));web_port=allocation.getsockname()[1];allocation.close()
        failure=queue.Queue()
        def query():
            try:
                connection,_=listener.accept()
                with connection:
                    connection.settimeout(30)
                    stream=connection.makefile('rwb',buffering=0)
                    stream.write(b'TS3\n\rWelcome to the TeamSpeak 3 ServerQuery interface\n\r')
                    for line in stream:
                        name=line.decode().split(' ',1)[0].strip()
                        if name=='version':stream.write(b'version=3.13.7 platform=Linux\n')
                        elif name=='whoami':stream.write(b'client_id=1 virtualserver_id=1\n')
                        elif name=='serverinfo':stream.write(b'virtualserver_name=PelicanFixture virtualserver_port=9987\n')
                        stream.write(b'error id=0 msg=ok\n')
            except Exception as error:failure.put(error)
        worker=threading.Thread(target=query,daemon=True);worker.start()
        env=environment(SERVER_PORT=str(web_port),CERBERUS_BUNDLED_PLUGINS='none',CERBERUS_GENERATION='TS3',CERBERUS_HOST='127.0.0.1',CERBERUS_PORT=str(listener.getsockname()[1]),CERBERUS_PASSWORD='fixture-only',CERBERUS_COMMAND_INTERVAL_MS='0',CERBERUS_RECONNECT_ATTEMPTS='0')
        if image:
            if not sys.platform.startswith('linux'):raise RuntimeError('The container smoke test uses Linux host networking; run in Linux CI.')
            server=base/'server';server.mkdir()
            command=['docker','run','--rm','-i','--network','host','--user',f'{os.getuid()}:{os.getgid()}','--mount',f'type=bind,source={server},target=/home/container']
            for key,value in env.items():
                if key.startswith('CERBERUS_') or key=='SERVER_PORT':command.extend(['--env',f'{key}={value}'])
            command.append(image)
        else:
            runtime,server=prepare(base,distribution)
            command=['bash',str(runtime/'start.sh')]
        process=subprocess.Popen(command,cwd=server,env=env,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
        lines=queue.Queue()
        def read():
            for line in process.stdout:lines.put(line)
            lines.put(None)
        threading.Thread(target=read,daemon=True).start()
        output=[]
        def until(predicate):
            while True:
                line=lines.get(timeout=45)
                if line is None:raise RuntimeError('Runtime exited before the expected response: '+''.join(output[-10:]))
                output.append(line)
                if predicate(line):return line
        try:
            until(lambda line:'[cerberus] CONNECTED' in line)
            url=f'http://127.0.0.1:{web_port}'
            with urllib.request.urlopen(url,timeout=10) as response:
                assert response.status==200 and b'Cerberus' in response.read()
            try:urllib.request.urlopen(url+'/api/web/status',timeout=10)
            except urllib.error.HTTPError as error:assert error.code in (401,403)
            else:raise AssertionError('Unauthenticated status API was accessible')
            process.stdin.write('web-token\n');process.stdin.flush()
            token=until(lambda line:re.fullmatch(r'[A-Za-z0-9_-]{30,}\s*',line) is not None).strip()
            request=urllib.request.Request(url+'/api/web/status',headers={'Authorization':'Bearer '+token})
            with urllib.request.urlopen(request,timeout=10) as response:
                assert response.status==200
                json.load(response)
            process.stdin.write('stop\n');process.stdin.flush();process.wait(timeout=30)
            assert process.returncode==0, 'Runtime exited unsuccessfully'
            worker.join(timeout=5)
            if not failure.empty():raise failure.get()
            print('Pelican runtime smoke passed: plugin-independent readiness, web allocation, token authentication, console stop.')
        finally:
            if process.poll() is None:process.terminate()
            try:process.wait(timeout=10)
            except subprocess.TimeoutExpired:process.kill();process.wait()
            process.stdin.close();process.stdout.close()


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    source=parser.add_mutually_exclusive_group()
    source.add_argument('--distribution',type=Path)
    source.add_argument('--image')
    args=parser.parse_args()
    result=unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(PelicanTest))
    if not result.wasSuccessful():raise SystemExit(1)
    if args.distribution or args.image:smoke(args.distribution,args.image)


if __name__=='__main__':main()
