# Manual interno — Ensayo de Voces (app Android)

Desarrollado por The DOM Studio Web · WhatsApp 261-3431216 · thedomstudioweb@gmail.com

## Qué es
Ensayo de Voces es una app para el teléfono que toma cualquier canción y la separa en pistas para ensayar: la música sola, las voces, y si se pide, la voz principal aparte de los coros. Todo se procesa dentro del teléfono: no se sube nada a internet, no tiene límite de minutos y es gratis. Es de uso propio (Daniel y Belén); no está a la venta.

## Para qué sirve
- Cantar encima de la música sola (pista "Música").
- Escuchar solo las voces para aprender la letra o la melodía.
- Ensayar la línea principal sin los coros, o los coros sin la principal.
- Repetir un pedazo difícil (botones A y B).

## Cómo se usa
1. **Agregar canción**: tocar «＋ Agregar canción» y elegir el archivo (mp3, m4a, wav…). También se puede compartir un audio desde WhatsApp o desde Archivos con «Ensayo de Voces».
2. **Elegir qué separar**:
   - «Voces y música»: dos pistas.
   - «Voz principal, coros y música»: tres pistas; tarda más.
   - «Calidad alta»: más limpio, tarda el doble. Se puede apagar para ir más rápido.
   - «Motor»: Acelerado (recomendado), Chip de IA (prueba el chip gráfico del teléfono; puede ser más rápido o no andar) o Básico (el más lento). Si el elegido falla, la app usa Básico sola. En la lista se ve cuánto tardó cada canción y con qué motor, para comparar.
3. **Separar**: muestra la etapa, el porcentaje y cuánto falta. Se puede salir de la app o apagar la pantalla; cuando termina llega una notificación.
4. **Ensayar**: en el reproductor cada pista tiene su interruptor (prender/apagar), su volumen y el botón SOLO (escuchar solo esa pista). Abajo: reproducir/pausa, −10 / +10 segundos, la barra para ir a cualquier parte, y A / B para repetir un tramo (A marca el inicio, B el final, ✕ quita la repetición).
5. **Intercambiar voz principal y coros**: el motor decide cuál es la "principal" por cómo suena, y a veces se equivoca (por ejemplo en murga, donde la línea principal la canta un grupo). El botón «⇅ Intercambiar» corrige los nombres sin volver a separar.

## La primera vez
La primera separación baja el "motor" (modelo de inteligencia artificial): unos 65 MB para voces y música, y otros 50 MB si se pide separar los coros. Conviene estar con Wi-Fi. Después ya no hace falta internet.

## Cuánto tarda
Depende del largo de la canción y de la calidad elegida. Mientras procesa, la app muestra cuánto falta. En el Motorola Razr 60 Ultra se espera que tarde unos minutos por canción (menos que la duración del tema con calidad normal; más con calidad alta y coros). Es más lento que Moises porque Moises procesa en sus servidores; a cambio, acá no hay límites.

## Límites conocidos
- Separa la voz principal de los coros, pero no separa entre sí dos voces principales que cantan a la vez (por ejemplo, dos hombres a dúo). Voz masculina y femenina por separado: en estudio (fase siguiente).
- Si una voz principal hace coros en algunas partes, esa parte puede quedar en "Coros".
- Si la línea principal la canta un grupo (murga, coro), el motor puede tomarla como coros: usar «Intercambiar».
- Velocidad medida en el Razr 60 Ultra: «Amor» (5:22) tardó casi 40 minutos con calidad alta y motor básico (primera versión).
- La separación nunca es perfecta: puede quedar algo de eco de la música en las voces.

## Datos técnicos (para soporte)
- Motor: modelos abiertos MDX-Net de Ultimate Vocal Remover en formato ONNX: «Kim Vocal 2» (voces/música) y «UVR MDXNET KARA 2» (principal/coros). Corren con ONNX Runtime en el procesador del teléfono.
- Las pistas se guardan dentro de la app (WAV 44,1 kHz). Borrar la canción borra sus pistas.
- Repositorio: github.com/TheDomStudioWeb/ensayo-de-voces-app. Cada cambio en la rama main compila la APK sola y la publica en Releases («EnsayoDeVoces.apk»); se instala encima de la anterior sin perder las canciones.

## Próximas fases
1. ~~Voces y música en el teléfono~~ (hecho)
2. ~~Voz principal y coros~~ (incluido, a probar)
3. Letra sincronizada (transcripción automática).
4. Modo Belén: recibir por WhatsApp un paquete ya separado y solo reproducirlo; guía de notas; voz femenina / masculina por separado.
