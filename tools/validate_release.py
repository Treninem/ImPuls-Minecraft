#!/usr/bin/env python3
from pathlib import Path
import json, re, sys, tempfile, zipfile
repo = Path(__file__).resolve().parents[1]
zip_path = repo / 'dist' / 'ImPulsCore_Datapack_v1.0.0.zip'
errors=[]
if not zip_path.exists(): print(f'missing release: {zip_path}', file=sys.stderr); sys.exit(2)
with tempfile.TemporaryDirectory() as td:
    root=Path(td)
    with zipfile.ZipFile(zip_path) as z:
        bad=[n for n in z.namelist() if Path(n).is_absolute() or '..' in Path(n).parts]
        if bad: errors.append(f'Unsafe zip paths: {bad[:3]}')
        z.extractall(root)
    for p in root.rglob('*.json'):
        try: json.loads(p.read_text(encoding='utf-8'))
        except Exception as e: errors.append(f'JSON {p.relative_to(root)}: {e}')
    try: json.loads((root/'pack.mcmeta').read_text(encoding='utf-8'))
    except Exception as e: errors.append(f'pack.mcmeta: {e}')
    funcs={str(p.relative_to(root/'data')).replace('\\','/').replace('/function/','/').removesuffix('.mcfunction').replace('/',':',1) for p in (root/'data').rglob('*.mcfunction')}
    refs=[]
    for p in (root/'data').rglob('*.mcfunction'):
        for n,line in enumerate(p.read_text(encoding='utf-8').splitlines(),1):
            for m in re.finditer(r'(?:^| run |schedule )function\s+([a-z0-9_.-]+:[a-z0-9_./-]+)',line): refs.append((p,n,m.group(1)))
    for p,n,r in refs:
        if r not in funcs: errors.append(f'Missing function {r} referenced by {p.relative_to(root)}:{n}')
    load=root/'data/impuls/function/load.mcfunction'
    if not load.exists(): errors.append('Missing impuls:load')
    else:
        names=re.findall(r'^scoreboard objectives add\s+(\S+)',load.read_text(encoding='utf-8'),re.M)
        for name in names:
            if len(name)>16: errors.append(f'Objective too long ({len(name)}): {name}')
        if len(names)!=len(set(names)): errors.append('Duplicate scoreboard objective declarations')
    required=['data/minecraft/tags/function/load.json','data/minecraft/tags/function/tick.json','data/impuls/function/city/gates/open_all.mcfunction','data/impuls/function/city/gates/close_all.mcfunction']
    for rel in required:
        if not (root/rel).exists(): errors.append(f'Missing required file: {rel}')
    print(f'functions={len(funcs)} refs={len(refs)} errors={len(errors)}')
for e in errors: print(e)
sys.exit(1 if errors else 0)
