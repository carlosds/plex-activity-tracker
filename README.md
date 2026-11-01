# Plex Activity Tracker

App para Android TV / Google TV que publica filas de tu servidor Plex como canales de la pantalla de inicio, para usarlas en **Projectivy Launcher**:

- **Continuar viendo** (On Deck), también en la fila de sistema *Watch Next* con barra de progreso
- **Películas recientes** y **Series recientes**
- **Recomendado en Plex**: los hubs que Plex calcula por biblioteca (Redescubre, Mejor valoradas, Porque viste…)
- **Plugin de fondo para Projectivy** (requiere Premium): fanart de la tarjeta enfocada y rotación del fanart de tus filas

La app no tiene interfaz más allá de los ajustes. Se actualiza sola cada 15 minutos (configurable), al encender la tele y al cambiar un ajuste.

## 1. Compilar el APK

### Opción A — GitHub Actions (sin instalar nada)
1. Crea un repositorio en GitHub (puede ser privado) y sube esta carpeta tal cual.
2. En la pestaña **Actions**, el flujo *Compilar APK* se lanza solo al hacer push a cualquier rama (o con *Run workflow*).
3. Al terminar (unos 5 min), descarga el artefacto **plex-activity-tracker-apk** (un zip con `app-release.apk`).

### Opción B — Android Studio
Abre la carpeta y ejecuta *Build → Build APK(s)*.

### Firma propia (necesaria para actualizar sin desinstalar)
Sin ella, cada compilación de GitHub usa una clave de depuración distinta y Android no deja actualizar encima: tendrías que desinstalar antes (y volver a configurar). Se crea una sola vez, en tu ordenador:

```bash
scripts/create-signing-key.sh --upload   # con `gh` autenticado: sube los 4 secretos al repo
scripts/create-signing-key.sh            # sin `gh`: te muestra los valores para pegarlos a mano
```

Los secretos (*Settings → Secrets and variables → Actions*) son `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` (`plexactivitytracker`) y `KEY_PASSWORD`. Guarda el `.jks` generado y su contraseña en un lugar seguro (no lo subas al repo; está en `.gitignore`): si lo pierdes, tendrás que desinstalar una vez más. El `versionCode` sube solo con cada ejecución de la Action.

## 2. Instalar en la tele

Con la depuración ADB activada en la Bravia (*Ajustes → Sistema → Información → pulsar 7 veces en Compilación*, luego *Opciones de desarrollador → Depuración ADB*):

```bash
adb connect IP_DE_LA_TELE
adb install -r app-release.apk
```

También vale con *Send Files to TV* + un gestor de archivos, o con Downloader.

## 3. Configurar

1. Abre **Plex Activity Tracker** desde la lista de apps.
2. **Vincular con mi cuenta Plex**: aparece un código; introdúcelo en plex.tv/link desde el móvil. La app busca el servidor en la LAN, guarda URL y token y crea las filas.
   - Alternativa manual: rellena *URL del servidor* (`http://IP_DEL_NAS:32400`) y *Token*.
3. Elige qué filas quieres y pulsa **Actualizar ahora**.

## 4. Añadir las filas en Projectivy

En Projectivy crea una categoría nueva de tipo canal/recomendaciones de una app, elige **Plex Activity Tracker** y la fila que quieras (una categoría por fila). Si alguna no aparece, en los ajustes de Plex Activity Tracker usa **Hacer visibles las filas**.

Para *Continuar viendo* tienes dos opciones: el canal propio o la categoría *Watch Next* filtrada por Plex Activity Tracker (esta muestra la barra de progreso).

**Fondo (Premium):** *Ajustes de Projectivy → Apariencia → Fondo de pantalla → Plex Activity Tracker (fanart)*.

## Al pulsar una tarjeta

La app abre Plex mediante enlaces `plex://play` / `plex://preplay`. Plex no documenta estos enlaces en Android, así que en *Al pulsar una tarjeta* puedes elegir entre reproducir, abrir la ficha o solo abrir la app. Prueba qué funciona en tu tele; el cambio se aplica al momento.

## Notas

- Las URLs de las carátulas llevan tu token de Plex (es lo que permite que Projectivy las cargue). Quedan en la base de datos de canales de la tele, accesible a apps con permiso de lectura de canales. En una tele de casa es razonable, pero tenlo en cuenta.
- Android limita las tareas periódicas a un mínimo de 15 minutos, y la Bravia puede retrasarlas en reposo. Al encender la tele se refresca.
- La carpeta `api/` es la API oficial de plugins de Projectivy (Apache 2.0), copiada sin cambios.
