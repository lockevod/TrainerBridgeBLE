# TrainerBridgeBLE

BLE bridge: lee el rodillo (Zycle) como central y lo re-expone a las apps
(Bestcycling/Garmin/Karoo) como peripheral, corrigiendo la potencia en medio.

## Arquitectura (el flujo)
- `ble/ZycleClient` — central: habla con el rodillo real.
- `ble/MirrorServer` — peripheral: finge SER el rodillo para las apps.
- `ble/PowerRewrite` + `correction/PowerCorrection` — corrige potencia (read)
  e invierte el target ERG (write).
- `karoo/` — sensor virtual para el propio Karoo.

## Build / test
- JDK 17 obligatorio (karoo-ext). `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`
- `./gradlew :app:testDebugUnitTest`  (lógica pura: PowerCorrection, CorrectedFeed)
- `./gradlew :app:assembleDebug`

## Serena
Para navegar/editar símbolos (buscar defs, refs, renombrar, editar cuerpos de
funciones) usa las herramientas de Serena antes que grep/read/edit manual.
