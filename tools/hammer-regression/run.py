"""Isolated real-mod regression. Never writes to the supplied Minecraft instance."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile
from datetime import datetime
import argparse
import hashlib
import json

ROOT = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--jar', type=Path, help='Test byte-identical production entries from this jar')
args = parser.parse_args()
RUN = ROOT / 'build/hammer-regression' / datetime.now().strftime('run-%Y%m%d-%H%M%S')
PACK = Path('G:/MC/PCL/.minecraft/versions/1.21.1MOD测试/mods')
RUN.mkdir(parents=True)
(RUN.parent / 'last-run.txt').write_text(str(RUN), encoding='utf-8')
(RUN / 'mods').mkdir(exist_ok=True)
userdev, = Path('G:/GradleCache/caches/modules-2/files-2.1/net.neoforged/neoforge/21.1.233').rglob('*userdev.jar')
with zipfile.ZipFile(userdev) as archive:
    (RUN.parent / 'moddev-config.json').write_bytes(archive.read('config.json'))
for pattern in ('*useless_mod*.jar', '*useless_stretcher*.jar', '*exdeorum*.jar',
                'appliedenergistics2-*.jar', 'guideme-*.jar'):
    source, = PACK.glob(pattern)
    shutil.copy2(source, RUN / 'mods' / source.name)
(RUN / 'eula.txt').write_text('eula=true\n', encoding='utf-8')
(RUN / 'server.properties').write_text('online-mode=false\nserver-port=0\nlevel-type=minecraft:flat\ngenerate-structures=false\nspawn-protection=0\nview-distance=2\nsimulation-distance=2\n', encoding='utf-8')
env = os.environ.copy()
env['JAVA_HOME'] = 'G:/java21'
env['GRADLE_USER_HOME'] = 'G:/GradleCache'
with (RUN / 'gradle-console.log').open('w', encoding='utf-8') as output:
    result = subprocess.run([str(ROOT / 'gradlew.bat'), '--offline', '--no-configuration-cache', '-x', 'downloadAssets',
                             '--console=plain', '-I', str(Path(__file__).with_name('init.gradle')),
                             '-PhammerRunDirectory=' + str(RUN), 'classes', 'compileHammerRegressionJava',
                             'prepareServerRun'], cwd=ROOT, env=env, stdout=output, stderr=subprocess.STDOUT, timeout=300)
if result.returncode == 0:
    classpath = (ROOT / 'build/moddev/serverLegacyClasspath.txt').read_text(encoding='utf-8').splitlines()
    classpath.append(str(ROOT / 'build/moddev/artifacts/neoforge-21.1.233.jar'))
    if args.jar:
        diagnostic = RUN / 'mods/veinminerplus-hammer-diagnostic.jar'
        added = []
        with zipfile.ZipFile(args.jar) as original, zipfile.ZipFile(diagnostic, 'w', zipfile.ZIP_DEFLATED) as output:
            originals = original.namelist()
            assert not any('HammerRegression' in name for name in originals)
            for entry in original.infolist():
                output.writestr(entry, original.read(entry))
            compiled = ROOT / 'build/classes/java/hammerRegression'
            for source in compiled.rglob('*.class'):
                name = source.relative_to(compiled).as_posix()
                assert name not in originals
                output.write(source, name)
                added.append(name)
        with zipfile.ZipFile(args.jar) as original, zipfile.ZipFile(diagnostic) as output:
            assert all(original.read(name) == output.read(name) for name in originals)
        (RUN / 'production-audit.json').write_text(json.dumps({
            'jar': str(args.jar.resolve()), 'sha256': hashlib.sha256(args.jar.read_bytes()).hexdigest(),
            'unchanged_production_entries': len(originals), 'test_classes_added': added,
        }, indent=2), encoding='utf-8')
        env.pop('MOD_CLASSES', None)
        env.pop('FML_MOD_CLASSES', None)
    else:
        env['MOD_CLASSES'] = ';'.join('veinminerplus%%' + str(ROOT / p) for p in
                                     ('build/classes/java/main', 'build/classes/java/hammerRegression', 'build/resources/main'))
    with (RUN / 'server-console.log').open('w', encoding='utf-8') as output:
        result = subprocess.run(['G:/java21/bin/java.exe', '-Xmx2G', '-Dfile.encoding=UTF-8',
                                 '-cp', os.pathsep.join(classpath),
                                 '@' + str(ROOT / 'build/moddev/serverRunVmArgs.txt'),
                                 '@' + str(ROOT / 'build/moddev/serverRunProgramArgs.txt')],
                                cwd=RUN, env=env, stdout=output, stderr=subprocess.STDOUT, timeout=180)
log = (RUN / 'logs/latest.log')
text = log.read_text(encoding='utf-8') if log.exists() else ''
for line in text.splitlines():
    if 'HAMMER ' in line:
        print(line.encode('ascii', errors='backslashreplace').decode('ascii'))
if result.returncode != 0 or 'HAMMER REGRESSION PASSED' not in text:
    print('FAIL: see', RUN / 'logs/latest.log', 'and', RUN / 'gradle-console.log')
    sys.exit(1)
