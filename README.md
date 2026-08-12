# ImPuls Minecraft Server

Кроссплатформенный Survival + RPG сервер **ImPuls**: экономика, гильдии, ранги, задания, подземелья, защищённая столица, участки и 32 городских ворот.

## Репозиторий

- `archives/ImPulsCore_Datapack_v0.9.0.full.part01.b64`, `part02`, `part31`, `part32` — точные Base64-части восстановленного v0.9.0; CI собирает их и проверяет SHA-256 до использования.
- `tools/build_datapack.py` — воспроизводимо собирает datapack **v1.0.0** из восстановленного v0.9.0.
- `tools/validate_release.py` — проверяет JSON, ссылки функций и scoreboard objectives.
- `plugin/` — исходники Java-плагина **ImPulsCore v1.0.0**.
- `docs/` — установка, администрирование и текущий статус.
- GitHub Actions собирает готовые `ImPulsCore_Datapack_v1.0.0.zip`, `ImPulsCore-1.0.0.jar` и `SHA256SUMS.txt`.

## v1.0.0

Сохранены механики v0.9.0. Добавлены физическая внешняя стена примерно 2000×2000, ровно 32 физических ворот, ночные волны санитарной зоны, SQLite-ядро, страховка инвентаря на одну обычную смерть, гильдии, защищённые участки 32×32, VIP-участки 96×96 и изолированный VIP Creative.

Исправлен дефект v0.9.0: три scoreboard objective превышали лимит 16 символов (`impuls_damage_prev`, `impuls_dealt_prev`, `impuls_combat_time`). В v1.0.0 они переименованы без потери логики.

## Проверка сборки

GitHub Actions обязан пройти восстановление исходного архива по SHA-256, сборку datapack, статическую проверку datapack, Maven-сборку плагина, проверку JAR и формирование контрольных сумм. Неуспешный CI не считается релизом.

## MineKeep

Java: `impuls.minekeep.gg`  
Bedrock: `impuls.bedrock.minekeep.gg`

Последняя подтверждённая серверная среда: Paper 1.21.11-132, Java 25, MineKeep Free. Реальный TPS, строительство стены на текущем рельефе и Bedrock-вход должны проверяться уже после загрузки новой сборки на MineKeep.
