# Hytale Block Animations (Forge 1.12.2)

Mod **solo cliente**:
- Al picar un bloque, éste se mueve (squash & stretch + leve inclinación) como en Hytale.
- Al romperlo, el bloque se achica hasta el tamaño del item en vez de desaparecer de golpe.
- Config in-game: *Mods → Hytale Block Animations → Config* (velocidad, intensidad, duración del achicado, toggles).

## Compilar
1. Copiá `gradlew`, `gradlew.bat` y la carpeta `gradle/` del MDK oficial de Forge 1.12.2.
2. JDK 8 + `./gradlew setupDecompWorkspace` y luego `./gradlew build`.
3. El .jar queda en `build/libs/`.
