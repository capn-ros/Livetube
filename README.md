# LiveTube — etapas 1 a 4

App Android personal: iniciás sesión con tu cuenta de YouTube, elegís canales (★) y cuando alguno
está en vivo aparece como burbuja flotante (estilo Messenger) que, al tocarla, abre solo su chat
(leer y escribir, sin video). Sin Google Cloud, sin API key propia, sin cuotas.

## Qué hay
- Etapa 1: cliente InnerTube propio (leer/enviar chat).
- Etapa 2: login con WebView (cookie cifrada con Android Keystore), lista de suscripciones,
  detección de directos por canal, agregar canales a mano.
- Etapa 3: servicio en primer plano + burbujas nativas de Android (Notification Bubbles).
- Etapa 4: emojis personalizados en línea, badges (dueño / mod / miembro), superchats y membresías,
  autoscroll que respeta si estás leyendo para arriba, varias burbujas a la vez.

## Compilar
- Android Studio: abrir la carpeta y Run, o
- GitHub Actions: subir a un repo; `.github/workflows/build.yml` deja el APK como artifact.

## Usar
1. Abrí la app > "Iniciar sesión con Google" (si Google lo bloquea: "pegar cookie").
2. Se cargan tus suscripciones. Marcá con ★ los canales que querés como burbuja.
3. Activá el switch "Burbujas de canales en vivo" (pide permiso de notificaciones).
4. En "Ajustes" asegurate de que las burbujas estén permitidas para la app.
5. Cuando un canal seguido esté en vivo (se revisa cada 2 minutos) aparece su burbuja.

## Notas
- Burbujas: Android 11 o superior (mejor en 12+). Si tu teléfono no las muestra, el toque en la
  notificación abre el mismo chat como pantalla normal.
- El chat se consulta solo mientras la burbuja está abierta (ahorra batería y datos).
- Las llamadas son no oficiales: si YouTube cambia algo, hay que ajustar `InnerTubeClient.kt`.
- No se pudo compilar ni probar contra YouTube real en el entorno donde se escribió.

## Flujo con GitHub Actions
1. En el repo debe estar `.github/workflows/build.yml` (se sube una sola vez, a mano).
2. Subí `livetube.zip` a la raíz del repo (con el repo adentro, en la raíz del zip).
3. Al hacer push de `livetube.zip` el workflow lo descomprime, reemplaza todo el repo
   (menos `.git`, `.github` y el propio zip), guarda el reemplazo en un commit y compila.
4. El APK queda en la pestaña Actions > la corrida > Artifacts > `livetube-apk`.
También se puede lanzar a mano con "Run workflow".
