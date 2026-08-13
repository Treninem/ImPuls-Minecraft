# Установка ImPuls v1.3.0 на MineKeep

## Перед обновлением

1. Сделать полный backup мира средствами MineKeep.
2. Остановить сервер полностью.
3. Сохранить папку `plugins/ImPulsCore/` — особенно `impuls.sqlite3`.
4. Не удалять старые архивы v0.7.1/v0.8.1/v0.9.0, но не держать несколько ImPuls datapack активными одновременно.

## Файлы релиза

Из `ImPuls-v1.3.0-final.zip` нужны:

- `ImPulsCore-1.3.0.jar`
- `ImPulsCore_Datapack_v1.3.0.zip`
- `SHA256SUMS.txt`

Внешний общий ZIP на сервер не распаковывать.

## Установка Java-плагина

1. В `plugins/` убрать старый активный `ImPulsCore-*.jar`.
2. Загрузить `ImPulsCore-1.3.0.jar`.
3. Папку `plugins/ImPulsCore/` и SQLite не удалять.

## Установка datapack

1. Открыть `world/datapacks/`.
2. Старые ImPuls datapack сохранить отдельно/выключить.
3. Загрузить **не распаковывая** `ImPulsCore_Datapack_v1.3.0.zip`.
4. В активной папке должен остаться один текущий ImPuls datapack.

## Первый запуск

Выполнить полный Start/Restart, не `/reload`.

В консоли проверить:

```text
plugins
version ImPulsCore
datapack list enabled
```

Ожидается ImPulsCore `1.3.0` и активный `ImPulsCore_Datapack_v1.3.0.zip`.

Затем:

```text
function impuls:admin/wall/status
```

Если внешняя стена ещё не построена:

```text
function impuls:admin/wall/start
```

Стена строится поэтапно. Новые городские/королевские структуры тоже создаются небольшими пакетами и стараются не перезаписывать существующие рукотворные блоки.

## Обязательный smoke-test

После запуска проверить:

1. Консоль без `SEVERE`, `Could not load`, `SQLException`, `NoClassDefFoundError`.
2. День/ночь и все 32 физических ворот.
3. `/impuls status`, `/impuls medal`, `/impuls quest status`, `/impuls rank status`.
4. Страховку обычной смерти и отсутствие её расхода в безопасных мини-играх/подземелье.
5. Участки, VIP Creative и невозможность вынести Creative-предметы.
6. Гильдии, базу, союз и тестовую войну.
7. Подземелье и общий групповой трофей с голосованием.
8. `/impuls event`, дуэль, стрельбу, лабиринт, wavegame и buildgame.
9. Рынок и профессии.
10. `/impuls backup create`, затем `/impuls backup verify`.
11. `/impuls diag` и реальные RAM/TPS после завершения строительства.
12. Вход Java и Bedrock в один мир.

## Важно

- Не использовать `/reload` для обновления Java-плагина Paper.
- Если после старта есть критическая ошибка, не продолжать эксплуатацию новой версии: сохранить консоль и откатить JAR/datapack из backup.
- Локальный BackupService резервирует SQLite. Полный мир перед релизами резервируется средствами MineKeep.
