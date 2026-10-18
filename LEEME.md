# Mini Player para Android

Reproductor flotante de música de YouTube, sin anuncios, que queda encima de tus otras apps.

## Cómo obtener el .apk (gratis, con GitHub)

1. Crea una cuenta en https://github.com (si no tienes).
2. Crea un repositorio nuevo: botón **New** → nombre `mini-player` → **Create repository**.
3. En el repositorio toca **uploading an existing file**, y arrastra **todo el contenido** de esta carpeta
   (no la carpeta en sí, sino lo que hay dentro). Luego **Commit changes**.
4. Revisa que se haya subido la carpeta `.github/workflows/build.yml`.
   Algunos navegadores no suben carpetas que empiezan con punto. Si no aparece:
   - Ve a la pestaña **Actions** → **set up a workflow yourself**.
   - Borra lo que trae, pega el contenido de `.github/workflows/build.yml` y guarda (**Commit changes**).
5. Ve a la pestaña **Actions**: verás "Compilar APK" trabajando (tarda unos 5 minutos).
6. Cuando termine con ✅, en la página principal del repositorio aparece **Releases** a la derecha.
   Ábrelo desde tu celular y descarga `MiniPlayer-debug.apk`.

## Instalar en el celular

1. Abre el `.apk` descargado. Android pedirá permitir "instalar apps desconocidas" para tu navegador: acéptalo.
2. Abre **Mini Player** y toca **Dar permiso** → activa "Mostrar sobre otras apps".
3. Vuelve y toca **Abrir reproductor**.
4. Recomendado: **Ajustes de batería** → Mini Player → "Sin restricciones", para que la música no se corte con la pantalla apagada.

## Uso

- 🔍 buscar música · ➖ convertir en burbuja (tócala para volver a abrir) · ✕ cerrar
- Arrastra la tarjeta o la burbuja tocando cualquier zona libre.
- ❤️ guarda en Favoritos.
- Controles también en la notificación y en la pantalla de bloqueo.
