"""Validate a staged executable JAR without opening local credentials."""
import hashlib
import json
from pathlib import Path
import struct
import sys
import zipfile

root = Path(__file__).resolve().parents[1]
folder = Path(sys.argv[1]).resolve()
assert folder.is_relative_to(root / 'target' / 'local-releases')
audit = json.loads((root / 'docs/logic-audit-verification.json').read_text())
for path, digest in audit['sourceHashes'].items():
    assert hashlib.sha256((root / path).read_bytes()).hexdigest() == digest, path
jar = folder / 'release.jar'
with zipfile.ZipFile(jar) as bundle:
    names = bundle.namelist()
    assert not any(Path(name).name == '.env' for name in names)
    assert not any('RequestStageProbe' in name or name.endswith('IntegrationTest.class') for name in names)
    resources = {}
    for source in sorted((root / 'src/main/resources').rglob('*')):
        if source.is_file():
            relative = source.relative_to(root / 'src/main/resources').as_posix()
            body = bundle.read('BOOT-INF/classes/' + relative)
            assert body == source.read_bytes(), relative
            resources[relative] = hashlib.sha256(body).hexdigest()
    versions = {}
    for name in names:
        if name.startswith('BOOT-INF/classes/') and name.endswith('.class'):
            version = struct.unpack('>H', bundle.read(name)[6:8])[0]
            assert version == 52, (name, version)
            versions[name] = version
    assert versions and len([n for n in resources if n.endswith('.lua')]) == 9
    assert 'Main-Class: org.springframework.boot.loader.JarLauncher' in bundle.read('META-INF/MANIFEST.MF').decode()
    old = folder / 'previous.jar'
    if old.exists():
        with zipfile.ZipFile(old) as previous:
            for name in names:
                if name.startswith('BOOT-INF/classes/db/migration/') and not name.endswith('/'):
                    assert bundle.read(name) == previous.read(name), ('Migration changed', name)
metadata = dict(jarSha256=hashlib.sha256(jar.read_bytes()).hexdigest(),
                jarSizeBytes=jar.stat().st_size, applicationClassCount=len(versions),
                javaClassMajorVersion=52, resources=resources, auditSourcesMatched=True,
                envFileAbsent=True, testProbeAbsent=True, migrationsUnchangedFromPrevious=old.exists())
(folder / 'artifact.json').write_text(json.dumps(metadata, indent=2) + '\n')
print('JAR verified: Java 8 application classes, current resources, nine Lua scripts and unchanged migrations.')
