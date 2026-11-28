"""Validate an executable JAR and create a narrow runtime build context."""
import hashlib
import json
from pathlib import Path
import struct
import sys
import zipfile

root=Path(__file__).resolve().parents[1]
folder=Path(sys.argv[1]).resolve()
run_id=sys.argv[2]
jar=root/'target/citymate-0.0.1-SNAPSHOT.jar'
image=folder/'image'
image.mkdir(parents=True,exist_ok=False)
with zipfile.ZipFile(jar) as bundle:
    names=set(bundle.namelist())
    assert not any(Path(n).name=='.env' for n in names)
    assert not any(n.startswith('BOOT-INF/classes/') and ('RequestStageProbe' in n or n.endswith('IntegrationTest.class')) for n in names)
    scripts={}
    for source in sorted((root/'src/main/resources').glob('*.lua')):
        entry='BOOT-INF/classes/'+source.name
        body=bundle.read(entry)
        assert body==source.read_bytes(),entry
        scripts[source.name]=hashlib.sha256(body).hexdigest()
    assert len(scripts)==9
    assert 'BOOT-INF/classes/db/migration/V10__versioned_public_knowledge.sql' in names
    assert any(n.startswith('BOOT-INF/classes/mapper/') and n.endswith('.xml') for n in names)
    assert 'BOOT-INF/classes/application.yaml' in names
    class_versions={}
    for name in ['com/hmdp/CityMateApplication.class','com/hmdp/reliability/RedisScripts.class']:
        entry='BOOT-INF/classes/'+name
        if entry not in names and name.startswith('com/hmdp/CityMate'):
            options=[n for n in names if n.startswith('BOOT-INF/classes/com/hmdp/') and n.endswith('Application.class')]
            assert len(options)==1,options
            entry=options[0]
        major=struct.unpack('>H',bundle.read(entry)[6:8])[0]
        assert major==52,(entry,major)
        class_versions[entry]=major
    manifest=bundle.read('META-INF/MANIFEST.MF').decode()
    assert 'Main-Class: org.springframework.boot.loader.JarLauncher' in manifest
(image/'app.jar').write_bytes(jar.read_bytes())
(image/'Dockerfile').write_text('FROM maven:3.9-eclipse-temurin-8\nWORKDIR /app\nCOPY app.jar /app/app.jar\nLABEL citymate.profile.run-id="'+run_id+'"\nENTRYPOINT ["java", "-Xmx512m", "-jar", "/app/app.jar"]\nCMD []\n',encoding='utf-8')
metadata=dict(runId=run_id,jarSha256=hashlib.sha256(jar.read_bytes()).hexdigest(),jarSizeBytes=jar.stat().st_size,
    luaScripts=scripts,classMajorVersions=class_versions,testProbeAbsent=True,envFileAbsent=True,
    runtimeBaseImage='maven:3.9-eclipse-temurin-8',runtimeProcess='java -Xmx512m -jar /app/app.jar',
    note='Cached JDK 8 base reused for controlled runtime; Maven is not running in application container')
(folder/'artifact.json').write_text(json.dumps(metadata,indent=2)+'\n',encoding='utf-8')
print('Executable JAR resources and Java 8 classes validated')
