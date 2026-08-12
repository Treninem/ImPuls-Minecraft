# Установка ImPuls на MineKeep

Последняя подтверждённая среда: Paper 1.21.11-132, Java 25, MineKeep Free (1 ГБ RAM / 10 ГБ).

1. Сделать резервную копию мира.
2. Остановить сервер.
3. В `world/datapacks/` оставить включённым только один ImPuls datapack — `ImPulsCore_Datapack_v1.0.0.zip`. Старые версии хранить выключенными.
4. В `plugins/` установить `ImPulsCore-1.0.0.jar`.
5. Для кроссплея установить Geyser + Floodgate; для старых Java-клиентов — ViaBackwards; для аудита — CoreProtect.
6. Запустить сервер и проверить `plugins`, `datapack list`, затем выполнить `function impuls:admin/wall/status`.
7. Если стена ещё не построена: `function impuls:admin/wall/start`. Строительство идёт по одному из 32 сегментов с паузами.
8. После завершения проверить ночь/утро: все 32 физических створки должны закрыться/открыться.
9. Проверить Java и Bedrock вход, смерть со страховкой, бой, `/trigger impuls_spawn`, ежедневное задание и гильдию.

Не использовать `/reload` для обновления Java-плагинов Paper — выполнять полный restart.
