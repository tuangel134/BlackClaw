# Terminal Linux fijo

BlackClaw incluye una terminal local pequeña y sin gestor de paquetes. Usa una
raíz Alpine 3.21 con `bash`, Python 3, Git, curl, jq y utilidades de desarrollo.
Funciona sin root, Shizuku ni ADB; no es un puente al shell de Android.

La raíz se instala una vez desde los assets en el espacio privado de la app. El
agente siempre usa esa sesión no privilegiada, con usuario virtual
`blackclaw` (uid 1000), y no puede seleccionar backends privilegiados, ADB ni
dispositivos remotos. La consola manual conserva su opción Pro por separado.

## Empaquetado y reproducibilidad

`tools/build_terminal_bootstrap.py` reconstruye los rootfs fijos a partir de
Alpine `v3.21`, minirootfs `3.21.7` y los índices APK de `main`. No deja `apk`,
repositorios ni metadatos de paquetes dentro de la imagen final.

| Artefacto | SHA-256 |
| --- | --- |
| `assets/terminal/aarch64/rootfs.tar.gz` | `e742b3c02bdbc2a1c21a7602b50b6990c612d9a6b7a3d53ea1fcc13a91cfbae6` |
| `assets/terminal/x86_64/rootfs.tar.gz` | `4f9df468247c0e670fabf6c3528f9e8d5b8d2aeeeb44ea020d4da15c2527f090` |
| `jniLibs/arm64-v8a/libblackclaw_proot.so` | `297abc237247682a84a3fd4283b28f69506502b4b852faf71fd726fb5d955d60` |
| `jniLibs/x86_64/libblackclaw_proot.so` | `46ca97b11d67f63b7dfe49f5ba50da89ab69b255f00a911c36499377a58f40b0` |

PRoot se empaqueta como biblioteca JNI para que Android la extraiga en el
directorio de bibliotecas nativas ejecutable. Es necesario en Android 10+:
archivos ejecutables escritos en el directorio de datos de una app no se pueden
ejecutar cuando el objetivo es API 29 o superior. Los binarios PRoot proceden
del proyecto `green-green-avk/build-proot-android` (MIT), versión
`0.15_release`; se usan con sus cargadores separados.

## Licencia PRoot wrapper

Copyright (c) 2023 green-green-avk

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
