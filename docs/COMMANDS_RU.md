# ImPuls v1.3.0 — команды

Основная команда: `/impuls` (алиас `/imp`).

## Игрок

### Профиль, ранг, задания

```text
/impuls help
/impuls status
/impuls medal
/impuls medal recover
/impuls quest status
/impuls quest take mine|hunt|craft|gather|explore
/impuls quest submit
/impuls quest abandon
/impuls rank status
/impuls rank trial
/impuls insure
```

### Земля

```text
/impuls claim buy
/impuls claim vip
/impuls claim info
/impuls claim expand <id> <west|east|north|south> <blocks>
/impuls claim sell <id> <player> <price>
/impuls claim accept <id>
```

### Гильдия

```text
/impuls guild create <name>
/impuls guild invite <player>
/impuls guild accept
/impuls guild leave
/impuls guild deposit <coins>
/impuls guild info
/impuls guild role <player> deputy|member
/impuls guild transfer <player>
/impuls guild base buy
/impuls guild base info
/impuls guild base expand <blocks>
/impuls guild alliance invite <guild>
/impuls guild alliance accept <guild>
/impuls guild alliance remove <guild>
```

### Войны

```text
/impuls war challenge <guild>
/impuls war accept
/impuls war status
```

### Подземелья и общий трофей

```text
/impuls dungeon enter <H|G|F|E|D|C|B|A|S|SS|SSS|SSS+>
/impuls dungeon next
/impuls dungeon leave
/impuls loot status
/impuls loot vote <player>
/impuls loot award
```

`loot award` завершает голосование главой группы; при отсутствии завершения действует автоматическая выдача по сохранённым голосам.

### Экономика

```text
/impuls work
/impuls sellserver <amount>
/impuls market list
/impuls market sell <amount> <price>
/impuls market buy <id>
/impuls market cancel <id>
/impuls market deliver
```

### Транспорт и город

```text
/impuls spawn
/impuls travel list
/impuls travel <spawn|market|guild|port|arena|dungeon|north|south|west|east>
/impuls event
/impuls royal status
/impuls royal visit
/impuls royal castle
```

### Мини-игры

```text
/impuls duel <player>
/impuls duel accept <player>
/impuls duel leave
/impuls archery
/impuls archery leave
/impuls maze
/impuls maze leave
/impuls wavegame
/impuls wavegame leave
/impuls buildgame
/impuls buildgame submit
/impuls buildgame leave
```

Дуэли, стрельба, лабиринт, волновая арена и строительная зона используют временное игровое состояние/инвентарь. При штатном завершении и после восстановления незавершённой сессии обычное состояние возвращается.

### Рейтинги

```text
/impuls top defender
/impuls top coins
/impuls top rank
/impuls top xp
/impuls top quests
/impuls top dungeons
```

### VIP

```text
/impuls vip creative
/impuls fly
```

Требуется permission `impuls.vip`. Creative работает только в разрешённом VIP-участке, а полёт оплачивается поминутно и отключается в запрещённых состояниях.

## Администратор / OP

Требуется `impuls.admin`:

```text
/impuls diag
/impuls backup status
/impuls backup create
/impuls backup verify
/impuls capital status
/impuls capital build
/impuls capital rebuild
/impuls royal grant <player> <minutes|permanent>
/impuls royal revoke <player>
/impuls war cancel <id>
```

Datapack/консоль:

```text
function impuls:admin/wall/status
function impuls:admin/wall/start
datapack list enabled
plugins
version ImPulsCore
```

## Permissions

```text
impuls.admin   # OP/admin функции
impuls.vip     # VIP Creative и платный полёт
```

Все остальные обычные игровые команды доступны без отдельного permission и дополнительно проверяют состояние игрока, владение, роль гильдии, баланс или активную сессию.
