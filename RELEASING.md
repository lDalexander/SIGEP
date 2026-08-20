# Proceso de Release — SIGEP (app Android)

Guía para generar y publicar una versión oficial de la app. La app es
**offline-first**: registra producción, paros, insumos y checklists en una cola
local y los sincroniza con el backend FastAPI cuando hay conexión.

> **Nota:** la comprobación de actualización automática (`SplashActivity.verificarActualizacion`)
> está **deshabilitada** a propósito. Las tablets se actualizan instalando el APK
> manualmente (sideload). El `versionCode` debe ir siempre en aumento para que la
> nueva versión instale sobre la anterior.

---

## 1. Requisitos (una sola vez)

- **JDK**: el que trae Android Studio sirve. En Windows suele estar en
  `C:\Program Files\Android\Android Studio\jbr`. Exporta `JAVA_HOME` apuntando ahí
  si compilas por línea de comandos.
- **Keystore de firma** (ver paso 2).

---

## 2. Configurar la firma (una sola vez)

### 2.1 Crear el keystore

Genera una llave de firma. **Guárdala en un lugar seguro y respáldala**: si la
pierdes, no podrás volver a publicar actualizaciones que instalen sobre las
versiones ya firmadas con ella.

```bash
keytool -genkeypair -v \
  -keystore sigep-release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias sigep
```

Te pedirá una contraseña y algunos datos (nombre, organización, etc.).

### 2.2 Crear `keystore.properties`

Copia la plantilla y rellena tus valores:

```bash
cp keystore.properties.example keystore.properties
```

```properties
storeFile=sigep-release.jks          # ruta al .jks (absoluta o relativa a la raíz)
storePassword=********
keyAlias=sigep
keyPassword=********
```

> `keystore.properties` y los archivos `*.jks` están en `.gitignore`. **Nunca** los
> subas al repositorio. Gradle firma el release solo si `keystore.properties` existe;
> si no, compila el release **sin firmar** (no rompe el build).

---

## 3. Subir la versión

Edita `app/build.gradle.kts` → bloque `defaultConfig`:

```kotlin
versionCode = 12           // SIEMPRE +1 respecto a la versión anterior (última: 12)
versionName = "1.5.1"      // versión legible (semver) — última publicada: 1.4.0
```

Reglas:
- `versionCode` es un entero que **debe crecer** en cada release (Android bloquea
  instalar un código menor o igual sobre uno mayor).
- `versionName` es la etiqueta visible (p. ej. `1.1.0`).

---

## 4. Generar el APK firmado

### Opción A — Línea de comandos (recomendada)

```bash
# Windows (Git Bash):
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew clean assembleRelease
```

> **El `clean` no es opcional al subir de versión.** Las etiquetas de versión de la UI
> se leen del `PackageManager` (ver `util/VersionApp.kt`) justamente porque
> `BuildConfig.VERSION_NAME` es una constante que Kotlin **incrusta** en el `.class` de
> cada llamador, y el compilador incremental no recompila un archivo solo porque haya
> cambiado el *valor* de la constante. Si algún día se vuelve a usar `BuildConfig` para
> algo que dependa de la versión, un build incremental puede dejar el valor viejo dentro
> del APK: con la 1.5.1 instalada, `MenuRolesActivity` y `SplashActivity` mostraban
> «Versión 1.2.3».

APK resultante:

```
app/build/outputs/apk/release/app-release.apk
```

### Opción B — Android Studio

`Build → Generate Signed App Bundle / APK… → APK`, seleccionando el keystore.
(Con `keystore.properties` configurado, `assembleRelease` ya firma solo; esta
opción es alternativa.)

---

## 5. Verificar el APK

Confirma versión y firma antes de distribuir:

```bash
# versión y paquete
"$ANDROID_SDK/build-tools/<ver>/aapt.exe" dump badging app/build/outputs/apk/release/app-release.apk | grep -E "package|versionName"

# firma presente
"$JAVA_HOME/bin/keytool" -printcert -jarfile app/build/outputs/apk/release/app-release.apk
```

---

## 6. Etiquetar en git

```bash
git add app/build.gradle.kts
git commit -m "chore: version 1.5.1 (versionCode 12)"
git checkout master && git merge --no-ff <rama>          # si venías de una rama de feature
git tag -a v1.5.1 -m "SIGEP v1.5.1"
git push origin master
git push origin v1.5.1
```

---

## 7. Distribuir a las tablets

1. Copia `app-release.apk` a cada tablet (USB, MDM, enlace de descarga, etc.).
2. Instálalo encima de la versión anterior (el `versionCode` mayor lo permite).
3. Si cambió la firma respecto a la instalada, Android pedirá **desinstalar antes**.
   Por eso es crítico usar **siempre el mismo keystore**.

---

## Checklist rápido de release

- [ ] `versionCode` incrementado y `versionName` actualizado.
- [ ] App compila **con `clean`** (`./gradlew clean assembleRelease`).
- [ ] La versión que se ve en pantalla (Splash / selección de perfil / producción)
      coincide con `versionName`. Comprobable sin instalar:
      `adb shell dumpsys package com.example.controller | grep versionName`.
- [ ] Contrato cliente↔backend en sincronía (endpoints Retrofit en `ProduccionApi.kt`
      coinciden con el OpenAPI del backend).
- [ ] APK firmado y verificado (`aapt dump badging`).
- [ ] Commit + tag `vX.Y.Z` + push.
- [ ] APK distribuido e instalado en al menos una tablet de prueba.

---

## Notas

- **applicationId** = `com.example.controller`. Cambiarlo (p. ej. a
  `com.detcuador.sigep`) da identidad propia pero **rompe FCM**: hay que registrar la
  app nueva en Firebase y reemplazar `google-services.json`, y las tablets tratarían la
  app como nueva (reinstalación). Es una migración aparte, no un cambio menor.
- **Backend**: vive en `150.36.200.252` (FastAPI + MySQL, servicio `sigep.service`).
  El dashboard web se sirve por nginx en el puerto `3000`.
