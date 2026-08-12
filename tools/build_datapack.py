#!/usr/bin/env python3
from pathlib import Path
import hashlib, json, tempfile, zipfile

REPO=Path(__file__).resolve().parents[1]
BASE=REPO/'archives/ImPulsCore_Datapack_v0.9.0.zip'
OUT=REPO/'dist/ImPulsCore_Datapack_v1.0.0.zip'
CX=CZ=-688
R=1000
START=CX-R
SEG=250

if not BASE.is_file(): raise SystemExit(f'Missing {BASE}')

def add_once(path:Path, marker:str, text:str):
    old=path.read_text(encoding='utf-8')
    if marker not in old: path.write_text(old+'\n'+text.strip()+'\n',encoding='utf-8')

def write(root:Path, rel:str, text:str):
    p=root/rel;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf-8')

def gate_build(orientation:str):
    if orientation=='ns':
        return ['fill ~-12 ~-3 ~-5 ~-6 ~13 ~5 stone_bricks','fill ~6 ~-3 ~-5 ~12 ~13 ~5 stone_bricks','fill ~-5 ~8 ~-3 ~5 ~10 ~3 stone_bricks','fill ~-4 ~0 ~-3 ~4 ~7 ~3 air','setblock ~-9 ~12 ~0 lantern','setblock ~9 ~12 ~0 lantern']
    return ['fill ~-5 ~-3 ~-12 ~5 ~13 ~-6 stone_bricks','fill ~-5 ~-3 ~6 ~5 ~13 ~12 stone_bricks','fill ~-3 ~8 ~-5 ~3 ~10 ~5 stone_bricks','fill ~-3 ~0 ~-4 ~3 ~7 ~4 air','setblock ~0 ~12 ~-9 lantern','setblock ~0 ~12 ~9 lantern']

with tempfile.TemporaryDirectory() as td:
    root=Path(td)
    with zipfile.ZipFile(BASE) as z:z.extractall(root)
    (root/'pack.mcmeta').write_text(json.dumps({'pack':{'min_format':[94,0],'max_format':[94,1],'description':'ImPulsCore v1.0.0 — wall, 32 gates, waves'}},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    add_once(root/'data/impuls/function/load.mcfunction','# v1.0.0 wall/wave state','''# v1.0.0 wall/wave state
scoreboard players set #wall_total impuls_state 32
execute unless score #wall_phase impuls_state matches 0..32 run scoreboard players set #wall_phase impuls_state 0
execute unless score #wave_tick impuls_state matches 0.. run scoreboard players set #wave_tick impuls_state 0
execute unless score #wave_cycle impuls_state matches 0..8 run scoreboard players set #wave_cycle impuls_state 0''')
    add_once(root/'data/impuls/function/tick.mcfunction','# v1.0.0 sanitary-zone waves','''# v1.0.0 sanitary-zone waves
execute if score #night impuls_state matches 1 run scoreboard players add #wave_tick impuls_state 1
execute unless score #night impuls_state matches 1 run scoreboard players set #wave_tick impuls_state 0
execute if score #wave_tick impuls_state matches 1200.. run function impuls:city/waves/spawn''')
    write(root,'data/impuls/function/admin/wall/column_ns.mcfunction','fill ~0 ~-4 ~-2 ~0 ~6 ~2 stone_bricks\nsetblock ~0 ~7 ~-2 stone_brick_wall\nsetblock ~0 ~7 ~2 stone_brick_wall\nsetblock ~0 ~6 ~0 smooth_stone_slab[type=bottom]\n')
    write(root,'data/impuls/function/admin/wall/column_ew.mcfunction','fill ~-2 ~-4 ~0 ~2 ~6 ~0 stone_bricks\nsetblock ~-2 ~7 ~0 stone_brick_wall\nsetblock ~2 ~7 ~0 stone_brick_wall\nsetblock ~0 ~6 ~0 smooth_stone_slab[type=bottom]\n')
    sides=[('north','ns','z',CZ-R),('east','ew','x',CX+R),('south','ns','z',CZ+R),('west','ew','x',CX-R)]
    gate=0
    for side,orient,axis,const in sides:
        for i in range(8):
            gate+=1; segno=gate; a=START+i*SEG;b=a+SEG-1;gc=START+125+i*SEG;tag=f'impuls_gate_{gate:02d}'
            lines=[f'# {side} segment {segno}/32',f'forceload add {a-2 if axis=="z" else const-2} {const-2 if axis=="z" else a-2} {b+2 if axis=="z" else const+2} {const+2 if axis=="z" else b+2}']
            for v in range(a,b+1):
                if abs(v-gc)<=5:continue
                x,z=(v,const) if axis=='z' else (const,v)
                lines.append(f'execute positioned {x} 0 {z} positioned over world_surface run function impuls:admin/wall/column_{orient}')
            x,z=(gc,const) if axis=='z' else (const,gc)
            lines += [f'kill @e[type=marker,tag={tag}]',f'execute positioned {x} 0 {z} positioned over world_surface run summon marker ~ ~ ~ {{Tags:["impuls_gate","{tag}"]}}']
            lines += [f'execute at @e[type=marker,tag={tag},limit=1] run {cmd}' for cmd in gate_build(orient)]
            lines += [f'forceload remove {a-2 if axis=="z" else const-2} {const-2 if axis=="z" else a-2} {b+2 if axis=="z" else const+2} {const+2 if axis=="z" else b+2}',f'scoreboard players set #wall_phase impuls_state {segno}']
            if segno<32:lines.append(f'schedule function impuls:admin/wall/segment_{segno+1:02d} 2s replace')
            else:lines += ['scoreboard players set #wall_built impuls_state 1','function impuls:city/gates/configure','tellraw @a {"text":"[ImPuls] Стена и 32 ворот построены.","color":"green"}']
            write(root,f'data/impuls/function/admin/wall/segment_{segno:02d}.mcfunction','\n'.join(lines)+'\n')
            if orient=='ns':open_cmd='fill ~-4 ~0 ~-3 ~4 ~7 ~3 air';close_cmd=['fill ~-4 ~0 ~-2 ~4 ~7 ~2 dark_oak_planks','fill ~-3 ~1 ~-3 ~3 ~6 ~3 iron_bars']
            else:open_cmd='fill ~-3 ~0 ~-4 ~3 ~7 ~4 air';close_cmd=['fill ~-2 ~0 ~-4 ~2 ~7 ~4 dark_oak_planks','fill ~-3 ~1 ~-3 ~3 ~6 ~3 iron_bars']
            write(root,f'data/impuls/function/city/gates/gate_{gate:02d}_open.mcfunction',f'execute at @e[type=marker,tag={tag},limit=1] run {open_cmd}\nscoreboard players set #gate_{gate:02d} impuls_state 1\n')
            write(root,f'data/impuls/function/city/gates/gate_{gate:02d}_close.mcfunction','\n'.join(f'execute at @e[type=marker,tag={tag},limit=1] run {c}' for c in close_cmd)+f'\nscoreboard players set #gate_{gate:02d} impuls_state 0\n')
    write(root,'data/impuls/function/admin/wall/start.mcfunction','execute unless score #wall_built impuls_state matches 1 run scoreboard players set #wall_phase impuls_state 0\nexecute unless score #wall_built impuls_state matches 1 run schedule function impuls:admin/wall/segment_01 1s replace\n')
    write(root,'data/impuls/function/admin/wall/status.mcfunction','tellraw @s [{"text":"[ImPuls] wall "},{"score":{"name":"#wall_phase","objective":"impuls_state"}},{"text":"/32 built="},{"score":{"name":"#wall_built","objective":"impuls_state"}}]\n')
    write(root,'data/impuls/function/admin/wall/cancel.mcfunction','\n'.join(f'schedule clear impuls:admin/wall/segment_{i:02d}' for i in range(1,33))+'\n')
    write(root,'data/impuls/function/city/gates/open_all.mcfunction','scoreboard players set #gates_open impuls_state 1\n'+'\n'.join(f'function impuls:city/gates/gate_{i:02d}_open' for i in range(1,33))+'\n')
    write(root,'data/impuls/function/city/gates/close_all.mcfunction','scoreboard players set #gates_open impuls_state 0\n'+'\n'.join(f'function impuls:city/gates/gate_{i:02d}_close' for i in range(1,33))+'\n')
    pts=[(CX,CZ-R-48),(CX+R+48,CZ),(CX,CZ+R+48),(CX-R-48,CZ),(CX+700,CZ-R-48),(CX+R+48,CZ+700),(CX-700,CZ+R+48),(CX-R-48,CZ-700)]
    waves=['scoreboard players set #wave_tick impuls_state 0','scoreboard players add #wave_cycle impuls_state 1','execute if score #wave_cycle impuls_state matches 9.. run scoreboard players set #wave_cycle impuls_state 1']
    mobs=[(-4,-4,'zombie'),(4,-4,'skeleton'),(-4,4,'zombie'),(4,4,'pillager'),(0,6,'spider')]
    for n,(x,z) in enumerate(pts,1):
        pre=f'execute if score #wave_cycle impuls_state matches {n} positioned {x} 0 {z} if entity @a[distance=..192] positioned over world_surface run '
        waves += [pre+f'summon {mob} ~{dx} ~1 ~{dz} {{Tags:["impuls_wave"],PersistenceRequired:1b}}' for dx,dz,mob in mobs]
        waves.append(pre+'summon vindicator ~ ~1 ~-6 {Tags:["impuls_wave","impuls_wave_commander"],PersistenceRequired:1b}')
    write(root,'data/impuls/function/city/waves/spawn.mcfunction','\n'.join(waves)+'\n')
    repl={'impuls_damage_prev':'imp_dmg_prev','impuls_dealt_prev':'imp_deal_prev','impuls_combat_time':'imp_combat_t'}
    for p in root.rglob('*.mcfunction'):
        s=p.read_text(encoding='utf-8')
        for old,new in repl.items():s=s.replace(old,new)
        p.write_text(s,encoding='utf-8')
    OUT.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(OUT,'w',zipfile.ZIP_DEFLATED) as z:
        for p in sorted(root.rglob('*')):
            if p.is_file():z.write(p,p.relative_to(root))
print(OUT)
print(hashlib.sha256(OUT.read_bytes()).hexdigest())
