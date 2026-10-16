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
storeFile=C:/Users/.../llave_detcuador.jks   # ruta al .jks (ver 2.3)
storePassword=********
keyAlias=sigep_key
keyPassword=********
```

> `keystore.properties` y los archivos `*.jks` están en `.gitignore`. **Nunca** los
> subas al repositorio. Gradle firma el release solo si `keystore.properties` existe;
> si no, compila el release **sin firmar** (no rompe el build).

### 2.3 Cuál llave es la oficial (importante)

**Llave oficial: `llave_detcuador.jks`, alias `sigep_key`.** Es la que firma el APK
`1.5.1` que está instalado en las tablets:

```
CN=John Garcia, O=Detcuador S.A., L=Duran, ST=Guyas, C=EC
SHA-256: 16dc6993b2dce83bbf4a68f4f3bfe9dfb49d620c7e76b95ac6ce37c65d71a2fa
```

> ⚠️ Existe un **segundo** keystore histórico, `sigep-release.jks` (`CN=SIGEP`,
> SHA-256 `1d773b04…`), con el que se firmaron las versiones hasta la `1.3.1`/`1.4.0`.
> **No usarlo.** Un APK firmado con él no instala encima de la `1.5.1`: Android exige
> desinstalar, y **desinstalar borra la cola offline** (`sigep_db`) y el turno activo.
> Se conserva solo para poder actualizar alguna tablet que se haya quedado en `1.4.0`.

Las contraseñas viven en el gestor del asistente «Generate Signed APK» de Android
Studio (`.idea/workspace.xml` → `GenerateSignedApkSettings` guarda ruta y alias, no las
claves). Para firmar por línea de comandos hay que ponerlas en `keystore.properties`;
mientras estén como `PENDIENTE_PONER_CLAVE`, el build de release **falla a propósito**,
que es preferible a producir en silencio un APK con la firma equivocada.

---

## 3. Subir la versión

Edita `app/build.gradle.kts` → bloque `defaultConfig`:

```kotlin
versionCode = 16           // SIEMPRE +1 respecto a la versión anterior (última: 16)
versionName = "1.8.0"      // versión legible (semver) — última: 1.8.0 (2026-10-16, pendiente de instalar en planta)
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

# firma: la huella DEBE coincidir con la llave oficial (ver 2.3)
"$ANDROID_SDK/build-tools/<ver>/apksigner.bat" verify --print-certs app/build/outputs/apk/release/app-release.apk
#   esperado -> SHA-256 digest: 16dc6993b2dce83bbf4a68f4f3bfe9dfb49d620c7e76b95ac6ce37c65d71a2fa
```

> Si la huella no coincide, **no distribuyas el APK**: no instalará encima de lo que hay
> en las tablets y obligaría a desinstalarlas.

---

## 6. Etiquetar en git

```bash
git add app/build.gradle.kts
git commit -m "chore: version 1.6.0 (versionCode 13)"
git checkout master && git merge --no-ff <rama>          # si venías de una rama de feature
git tag -a v1.6.0 -m "SIGEP v1.6.0"
git push origin master
git push origin v1.6.0
```

---

## 7. Distribuir a las tablets

1. Copia `app-release.apk` a cada tablet (USB, MDM, enlace de descarga, etc.).
2. Instálalo encima de la versión anterior (el `versionCode` mayor lo permite).
3. Si cambió la firma respecto a la instalada, Android **rechaza la actualización** y hay
   que desinstalar antes. **Desinstalar borra los datos de la app**: la cola offline
   (`sigep_db`) y el turno activo. Si no hay más remedio, hazlo **al cerrar el turno y con
   el badge de pendientes en 0** (verde), nunca a media jornada: lo que esté en la cola se
   pierde. Por eso es crítico usar **siempre la llave oficial** (ver 2.3).
4. Comprueba en la tablet que la versión que muestra la pantalla coincide con la que
   instalaste (rótulo al pie de Splash / selección de perfil / producción).

---

## Checklist rápido de release

- [ ] `versionCode` incrementado y `versionName` actualizado.
- [ ] App compila **con `clean`** (`./gradlew clean assembleRelease`).
- [ ] La versión que se ve en pantalla (Splash / selección de perfil / producción)
      coincide con `versionName`. Comprobable sin instalar:
      `adb shell dumpsys package com.example.controller | grep versionName`.
- [ ] Contrato cliente↔backend en sincronía (endpoints Retrofit en `ProduccionApi.kt`
      coinciden con el OpenAPI del backend).
- [ ] APK firmado con la llave oficial: huella `16dc6993…` verificada con `apksigner verify --print-certs` (ver 2.3).
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
