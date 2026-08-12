#!/usr/bin/env python3
from pathlib import Path
import json, zipfile, tempfile, shutil, hashlib

repo = Path(__file__).resolve().parents[1]
base_zip = repo / "archives" / "ImPulsCore_Datapack_v0.9.0.zip"
out_zip = repo / "dist" / "ImPulsCore_Datapack_v1.0.0.zip"
if not base_zip.exists():
    raise SystemExit(f"Missing base archive: {base_zip}")

with tempfile.TemporaryDirectory() as tmp:
    root = Path(tmp)
    (root / "datapack").mkdir()
    with zipfile.ZipFile(base_zip) as z:
        z.extractall(root / "datapack")

    def write(rel, content):
        p=root/rel; p.parent.mkdir(parents=True, exist_ok=True); p.write_text(content, encoding='utf-8')
    write('datapack/pack.mcmeta', json.dumps({"pack":{"min_format":[94,0],"max_format":[94,1],"description":"ImPulsCore v1.0.0 — стена, 32 физических ворот, волны"}}, ensure_ascii=False, indent=2)+"\n")
    write('datapack/README_RU.txt', '''ImPulsCore Datapack v1.0.0\n\nОснован на фактически восстановленном v0.9.0.\nСохранены: регистрация, 12 рангов H–SSS+, ежедневные задания, монеты,\nстраховой статус, медальон, возврат на спавн, боевое состояние,\nпервичная площадь и квартал, логика дня/ночи.\n\nДобавлено в v1.0.0:\n- физическая внешняя стена примерно 2000×2000 вокруг центра -688,-688;\n- ровно 32 физических ворот: по 8 на каждую сторону;\n- ворота получают marker-сущность и реально открываются/закрываются блоками;\n- ночная автоматика привязана к физическим створкам;\n- безопасное поэтапное строительство стены по одному сегменту;\n- ночные волны в санитарной зоне только рядом с активными игроками;\n- теги impuls_wave/impuls_wave_commander для интеграции с плагином;\n- служебные команды проверки стены и ворот.\n''')

    loadp=root/'datapack/data/impuls/function/load.mcfunction'
    load=loadp.read_text(encoding='utf-8')
    add_load='''\n# v1.0.0 wall/wave state\nscoreboard players set #wall_total impuls_state 32\nexecute unless score #wall_phase impuls_state matches 0..32 run scoreboard players set #wall_phase impuls_state 0\nexecute unless score #wave_tick impuls_state matches 0.. run scoreboard players set #wave_tick impuls_state 0\nexecute unless score #wave_cycle impuls_state matches 0..8 run scoreboard players set #wave_cycle impuls_state 0\n'''
    if '# v1.0.0 wall/wave state' not in load: load += add_load
    loadp.write_text(load, encoding='utf-8')

    tickp=root/'datapack/data/impuls/function/tick.mcfunction'
    tick=tickp.read_text(encoding='utf-8')
    add_tick='''\n# v1.0.0 sanitary-zone waves: one cycle per 60 seconds at night\nexecute if score #night impuls_state matches 1 run scoreboard players add #wave_tick impuls_state 1\nexecute unless score #night impuls_state matches 1 run scoreboard players set #wave_tick impuls_state 0\nexecute if score #wave_tick impuls_state matches 1200.. run function impuls:city/waves/spawn\n'''
    if '# v1.0.0 sanitary-zone waves' not in tick: tick += add_tick
    tickp.write_text(tick, encoding='utf-8')

    center_x=center_z=-688; r=1000; start=center_x-r; segment=250
    centers=[start+125+i*segment for i in range(8)]
    fnroot=root/'datapack/data/impuls/function'
    (fnroot/'admin/wall').mkdir(parents=True, exist_ok=True)
    (fnroot/'city/gates').mkdir(parents=True, exist_ok=True)
    write('datapack/data/impuls/function/admin/wall/column_ns.mcfunction', '# One terrain-following north/south wall column, 5 blocks thick.\nfill ~0 ~-4 ~-2 ~0 ~6 ~2 minecraft:stone_bricks\nsetblock ~0 ~7 ~-2 minecraft:stone_brick_wall\nsetblock ~0 ~7 ~2 minecraft:stone_brick_wall\nsetblock ~0 ~6 ~0 minecraft:smooth_stone_slab[type=bottom]\n')
    write('datapack/data/impuls/function/admin/wall/column_ew.mcfunction', '# One terrain-following east/west wall column, 5 blocks thick.\nfill ~-2 ~-4 ~0 ~2 ~6 ~0 minecraft:stone_bricks\nsetblock ~-2 ~7 ~0 minecraft:stone_brick_wall\nsetblock ~2 ~7 ~0 minecraft:stone_brick_wall\nsetblock ~0 ~6 ~0 minecraft:smooth_stone_slab[type=bottom]\n')

    def gate_structure(orientation, num):
        name=f'Ворота {num:02d}'
        if orientation=='ns':
            return [f'# {name}', 'fill ~-12 ~-3 ~-5 ~-6 ~13 ~5 minecraft:stone_bricks','fill ~6 ~-3 ~-5 ~12 ~13 ~5 minecraft:stone_bricks','fill ~-11 ~1 ~-4 ~-7 ~11 ~4 minecraft:air','fill ~7 ~1 ~-4 ~11 ~11 ~4 minecraft:air','fill ~-13 ~14 ~-6 ~-5 ~14 ~6 minecraft:deepslate_tile_slab[type=bottom]','fill ~5 ~14 ~-6 ~13 ~14 ~6 minecraft:deepslate_tile_slab[type=bottom]','fill ~-5 ~8 ~-3 ~5 ~10 ~3 minecraft:stone_bricks','fill ~-4 ~0 ~-3 ~4 ~7 ~3 minecraft:air',f'setblock ~0 ~9 ~-4 minecraft:oak_wall_sign[facing=north]{{front_text:{{messages:[\'{{"text":"{name}"}}\',\'{{"text":"ImPuls"}}\',\'{{"text":""}}\',\'{{"text":""}}\']}}}}',f'setblock ~0 ~9 ~4 minecraft:oak_wall_sign[facing=south]{{front_text:{{messages:[\'{{"text":"{name}"}}\',\'{{"text":"ImPuls"}}\',\'{{"text":""}}\',\'{{"text":""}}\']}}}}','setblock ~-9 ~12 ~0 minecraft:lantern','setblock ~9 ~12 ~0 minecraft:lantern']
        return [f'# {name}','fill ~-5 ~-3 ~-12 ~5 ~13 ~-6 minecraft:stone_bricks','fill ~-5 ~-3 ~6 ~5 ~13 ~12 minecraft:stone_bricks','fill ~-4 ~1 ~-11 ~4 ~11 ~-7 minecraft:air','fill ~-4 ~1 ~7 ~4 ~11 ~11 minecraft:air','fill ~-6 ~14 ~-13 ~6 ~14 ~-5 minecraft:deepslate_tile_slab[type=bottom]','fill ~-6 ~14 ~5 ~6 ~14 ~13 minecraft:deepslate_tile_slab[type=bottom]','fill ~-3 ~8 ~-5 ~3 ~10 ~5 minecraft:stone_bricks','fill ~-3 ~0 ~-4 ~3 ~7 ~4 minecraft:air',f'setblock ~-4 ~9 ~0 minecraft:oak_wall_sign[facing=west]{{front_text:{{messages:[\'{{"text":"{name}"}}\',\'{{"text":"ImPuls"}}\',\'{{"text":""}}\',\'{{"text":""}}\']}}}}',f'setblock ~4 ~9 ~0 minecraft:oak_wall_sign[facing=east]{{front_text:{{messages:[\'{{"text":"{name}"}}\',\'{{"text":"ImPuls"}}\',\'{{"text":""}}\',\'{{"text":""}}\']}}}}','setblock ~0 ~12 ~-9 minecraft:lantern','setblock ~0 ~12 ~9 minecraft:lantern']

    segments=[]; gate_num=1
    sides=[('north','ns','z',center_z-r),('east','ew','x',center_x+r),('south','ns','z',center_z+r),('west','ew','x',center_x-r)]
    for side,orient,constaxis,constval in sides:
        for i in range(8):
            seg_no=len(segments)+1; variable_start=start+i*segment; variable_end=variable_start+segment-1; gate_center=centers[i]; tag=f'impuls_gate_{gate_num:02d}'
            lines=[f'# Segment {seg_no:02d}/32 — {side}, {tag}']
            if constaxis=='z':
                x1,x2=variable_start,variable_end; z=constval; lines += [f'forceload add {x1-2} {z-2} {x2+2} {z+2}']
                for x in range(x1,x2+1):
                    if abs(x-gate_center)>5: lines.append(f'execute positioned {x} 0 {z} positioned over world_surface run function impuls:admin/wall/column_ns')
                lines += [f'kill @e[type=minecraft:marker,tag={tag}]',f'execute positioned {gate_center} 0 {z} positioned over world_surface run summon minecraft:marker ~ ~ ~ {{Tags:["impuls_gate","{tag}"]}}']
                lines += [f'execute at @e[type=minecraft:marker,tag={tag},limit=1] run {c}' for c in gate_structure('ns',gate_num) if not c.startswith('#')]
                lines.append(f'forceload remove {x1-2} {z-2} {x2+2} {z+2}')
            else:
                z1,z2=variable_start,variable_end; x=constval; lines += [f'forceload add {x-2} {z1-2} {x+2} {z2+2}']
                for z in range(z1,z2+1):
                    if abs(z-gate_center)>5: lines.append(f'execute positioned {x} 0 {z} positioned over world_surface run function impuls:admin/wall/column_ew')
                lines += [f'kill @e[type=minecraft:marker,tag={tag}]',f'execute positioned {x} 0 {gate_center} positioned over world_surface run summon minecraft:marker ~ ~ ~ {{Tags:["impuls_gate","{tag}"]}}']
                lines += [f'execute at @e[type=minecraft:marker,tag={tag},limit=1] run {c}' for c in gate_structure('ew',gate_num) if not c.startswith('#')]
                lines.append(f'forceload remove {x-2} {z1-2} {x+2} {z2+2}')
            lines += [f'scoreboard players set #wall_phase impuls_state {seg_no}',f'tellraw @a [{{"text":"[ImPuls] ","color":"gold","bold":true}},{{"text":"Стена: сегмент {seg_no}/32 построен, {tag} готов.","color":"green"}}]']
            if seg_no<32: lines.append(f'schedule function impuls:admin/wall/segment_{seg_no+1:02d} 2s replace')
            else: lines += ['scoreboard players set #wall_built impuls_state 1','function impuls:city/gates/configure','tellraw @a [{"text":"[ImPuls] ","color":"gold","bold":true},{"text":"Внешняя стена и все 32 физических ворот построены.","color":"green"}]']
            write(f'datapack/data/impuls/function/admin/wall/segment_{seg_no:02d}.mcfunction','\n'.join(lines)+'\n'); segments.append((seg_no,gate_num,tag,orient))
            if orient=='ns': close=['fill ~-4 ~0 ~-2 ~4 ~7 ~2 minecraft:dark_oak_planks','fill ~-3 ~1 ~-3 ~3 ~6 ~3 minecraft:iron_bars']; open_='fill ~-4 ~0 ~-3 ~4 ~7 ~3 minecraft:air'
            else: close=['fill ~-2 ~0 ~-4 ~2 ~7 ~4 minecraft:dark_oak_planks','fill ~-3 ~1 ~-3 ~3 ~6 ~3 minecraft:iron_bars']; open_='fill ~-3 ~0 ~-4 ~3 ~7 ~4 minecraft:air'
            write(f'datapack/data/impuls/function/city/gates/gate_{gate_num:02d}_open.mcfunction',f'execute at @e[type=minecraft:marker,tag={tag},limit=1] run {open_}\nscoreboard players set #gate_{gate_num:02d} impuls_state 1\n')
            write(f'datapack/data/impuls/function/city/gates/gate_{gate_num:02d}_close.mcfunction','\n'.join(f'execute at @e[type=minecraft:marker,tag={tag},limit=1] run {c}' for c in close)+f'\nscoreboard players set #gate_{gate_num:02d} impuls_state 0\n'); gate_num+=1

    write('datapack/data/impuls/function/admin/wall/start.mcfunction','execute if score #wall_built impuls_state matches 1 run tellraw @s [{"text":"[ImPuls] ","color":"gold"},{"text":"Стена уже помечена как построенная.","color":"yellow"}]\nexecute unless score #wall_built impuls_state matches 1 run scoreboard players set #wall_phase impuls_state 0\nexecute unless score #wall_built impuls_state matches 1 run schedule function impuls:admin/wall/segment_01 1s replace\n')
    write('datapack/data/impuls/function/admin/wall/status.mcfunction','tellraw @s [{"text":"[ImPuls] Стена: ","color":"gold"},{"score":{"name":"#wall_phase","objective":"impuls_state"}},{"text":"/32; built=","color":"gray"},{"score":{"name":"#wall_built","objective":"impuls_state"}}]\n')
    write('datapack/data/impuls/function/admin/wall/cancel.mcfunction','\n'.join([f'schedule clear impuls:admin/wall/segment_{i:02d}' for i in range(1,33)])+'\n')
    open_lines=['scoreboard players set #gates_open impuls_state 1']; close_lines=['scoreboard players set #gates_open impuls_state 0']
    for i in range(1,33): open_lines.append(f'function impuls:city/gates/gate_{i:02d}_open'); close_lines.append(f'function impuls:city/gates/gate_{i:02d}_close')
    write('datapack/data/impuls/function/city/gates/open_all.mcfunction','\n'.join(open_lines)+'\n'); write('datapack/data/impuls/function/city/gates/close_all.mcfunction','\n'.join(close_lines)+'\n')

    wave_points=[(center_x,center_z-r-48),(center_x+r+48,center_z),(center_x,center_z+r+48),(center_x-r-48,center_z),(center_x+700,center_z-r-48),(center_x+r+48,center_z+700),(center_x-700,center_z+r+48),(center_x-r-48,center_z-700)]
    wave_lines=['scoreboard players set #wave_tick impuls_state 0','scoreboard players add #wave_cycle impuls_state 1','execute if score #wave_cycle impuls_state matches 9.. run scoreboard players set #wave_cycle impuls_state 1']
    for idx,(x,z) in enumerate(wave_points,1):
        prefix=f'execute if score #wave_cycle impuls_state matches {idx} positioned {x} 0 {z} if entity @a[distance=..192] positioned over world_surface run '
        for dx,dz,etype in [(-4,-4,'zombie'),(4,-4,'skeleton'),(-4,4,'zombie'),(4,4,'pillager'),(0,6,'spider')]: wave_lines.append(prefix+f'summon minecraft:{etype} ~{dx} ~1 ~{dz} {{Tags:["impuls_wave"],PersistenceRequired:1b}}')
        wave_lines.append(prefix+'summon minecraft:vindicator ~0 ~1 ~-6 {Tags:["impuls_wave","impuls_wave_commander"],CustomName:\'{"text":"Командир волны","color":"dark_red","bold":true}\',CustomNameVisible:1b,PersistenceRequired:1b}')
    write('datapack/data/impuls/function/city/waves/spawn.mcfunction','\n'.join(wave_lines)+'\n')

    replacements={"impuls_damage_prev":"imp_dmg_prev","impuls_dealt_prev":"imp_deal_prev","impuls_combat_time":"imp_combat_t"}
    for p in (root/'datapack').rglob('*.mcfunction'):
        text=p.read_text(encoding='utf-8')
        for old,new in replacements.items(): text=text.replace(old,new)
        p.write_text(text,encoding='utf-8')
    out_zip.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(out_zip,'w',zipfile.ZIP_DEFLATED) as z:
        for p in sorted((root/'datapack').rglob('*')):
            if p.is_file(): z.write(p,p.relative_to(root/'datapack'))
print(out_zip)
print(hashlib.sha256(out_zip.read_bytes()).hexdigest())
