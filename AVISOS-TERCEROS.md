# Licencias de terceros

Caja Libre (Copyright (C) 2026 Elias Oziel Sánchez Fernández, GNU GPL v3) se distribuye junto con
las bibliotecas siguientes, cada una con su propia licencia. Los archivos `.jar` van sueltos en la
carpeta `app/` (o `lib/app/`) del programa instalado, de modo que cualquier biblioteca LGPL puede
sustituirse por otra versión. Las versiones exactas están en `Punto-Ventas/build.gradle`.

| Componente | Uso en el programa | Licencia |
|---|---|---|
| OpenJDK 17 (runtime empaquetado por jpackage, Eclipse Temurin) | Máquina virtual de Java | GPL v2 con Classpath Exception |
| OpenJFX / JavaFX 17 (base, graphics, controls, fxml) | Interfaz gráfica | GPL v2 con Classpath Exception |
| Spring Boot, Spring Framework, Spring Data JPA | Contexto de la aplicación, acceso a datos | Apache License 2.0 |
| Spring Security Crypto | BCrypt para las contraseñas | Apache License 2.0 |
| Hibernate ORM 6.4 y Hibernate Community Dialects | Mapeo objeto-relacional, dialecto SQLite | **GNU LGPL v2.1** (ver nota) |
| Jakarta Persistence, Jakarta Annotations y demás APIs Jakarta | APIs estándar | Eclipse Public License 2.0 / Eclipse Distribution License 1.0 |
| Jackson (databind, core, annotations) | JSON (actualizaciones, clima, claves de apoyo) | Apache License 2.0 |
| HikariCP | Pool de conexiones | Apache License 2.0 |
| SLF4J y Logback | Registro de eventos | MIT (SLF4J); EPL 1.0 / LGPL 2.1 (Logback) |
| SnakeYAML | Lectura de configuración de Spring | Apache License 2.0 |
| sqlite-jdbc (Xerial) | Base de datos de una caja | Apache License 2.0 |
| PostgreSQL JDBC Driver | Base de datos de varias cajas | BSD 2-Clause |
| JmDNS | Nombre `.local` del acceso móvil | Apache License 2.0 |
| AtlantaFX | Tema visual (NordLight) | MIT |
| Nayuki QR Code generator | Código QR del acceso móvil | MIT |
| jSerialComm | Báscula por puerto serial | **Apache License 2.0** (biblioteca de doble licencia LGPL v3 / Apache 2.0; se elige Apache 2.0) |
| fastexcel y opczip | Exportar a .xlsx | Apache License 2.0 |
| OpenPDF | Exportar a PDF | **Mozilla Public License 2.0** (doble licencia MPL 2.0 / LGPL 2.1+; se elige MPL 2.0) |
| Lucide (trazos de iconos en `util/Iconos.java`, página móvil y sitio) | Iconos | ISC — Copyright (c) 2022 Lucide Contributors |
| Project Lombok | Solo al compilar; no se distribuye | MIT |

**Nota sobre Hibernate (LGPL 2.1).** Caja Libre usa Hibernate sin modificarlo, como biblioteca
separada; el usuario puede sustituir el `.jar` por otra versión compatible. El código fuente de
Hibernate está en <https://hibernate.org> y el texto de la LGPL 2.1 en
<https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html>.

**Textos de las licencias:** Apache 2.0 <https://www.apache.org/licenses/LICENSE-2.0>; MIT
<https://opensource.org/license/mit>; BSD 2-Clause <https://opensource.org/license/bsd-2-clause>;
ISC <https://opensource.org/license/isc-license-txt>; EPL 2.0 <https://www.eclipse.org/legal/epl-2.0/>;
MPL 2.0 <https://www.mozilla.org/MPL/2.0/>; GPL v2 con Classpath Exception
<https://openjdk.org/legal/gplv2+ce.html>. Cada `.jar` conserva sus propios archivos `LICENSE` /
`NOTICE` dentro de `META-INF/`.

**Datos.** El catálogo de códigos de barras de productos comunes de México es un proyecto aparte
(repositorio <https://github.com/Minepacman/codigos-barras-mx>, datos de Open Food Facts y proyectos
hermanos bajo la Open Database License, ODbL 1.0, <https://opendatacommons.org/licenses/odbl/1-0/>;
contenido bajo la Database Contents License, DbCL 1.0). Contiene información de Open Food Facts, disponible
bajo la Open Database License (ODbL). El programa trae una copia sin modificar de ese catálogo
(`catalogo/catalogo-mx.csv.gz` dentro del jar, la misma que publica el repositorio) y, si la búsqueda en
internet está encendida, baja la versión más nueva una vez por semana, consulta por código los fragmentos
del repositorio y, como último recurso, la API pública de Open Food Facts
(<https://world.openfoodfacts.org>), para proponer descripción, departamento y foto al dar de alta un
producto por su código de barras; donde se muestran esos datos aparece la atribución «Datos de Open Food
Facts (ODbL)» (Configuración → Códigos de barras permite apagar la consulta en internet). Las fotos de los
productos son de Open Food Facts (CC BY-SA 3.0) y solo se bajan al pedirlas.
El pronóstico del clima y la búsqueda de ciudades por nombre vienen de Open-Meteo
(<https://open-meteo.com>, datos bajo CC BY 4.0) cuando el usuario activa esa función o busca su ciudad.
El botón «Detectar mi ubicación» (asistente inicial y Configuración → Ubicación y clima) estima la ciudad a
partir de la conexión a internet consultando, solo al pulsarlo y por HTTPS, uno de estos servicios gratuitos
y sin llave, en orden hasta que uno responde: ipwho.is (<https://ipwho.is>, plan gratuito de uso justo),
GeoJS (<https://www.geojs.io>, proyecto abierto) e ipapi.co (<https://ipapi.co>, plan gratuito). A ninguno se
le envía dato alguno de la tienda; cada uno ve únicamente la dirección IP pública desde la que se le consulta,
igual que cualquier sitio web. Lo que se guarda en el programa es la ciudad y las coordenadas que el
administrador confirma.

Lector de tickets de proveedor (OCR). Los instaladores de Windows y macOS incluyen Tesseract OCR
(<https://github.com/tesseract-ocr/tesseract>, Apache-2.0) en `ocr/tesseract`, con los datos de idioma `spa` y
`eng` de tessdata_fast 4.1.0 (<https://github.com/tesseract-ocr/tessdata_fast>, Apache-2.0) y el archivo de
configuración `configs/tsv` de Tesseract (Apache-2.0). El texto de la licencia de Tesseract va en
`ocr/tesseract/LICENSE-tesseract.txt` y el de cada biblioteca en `ocr/tesseract/licencias/`:

| Biblioteca | Licencia | Windows (x64/x86, enlazada estáticamente en `tesseract.exe`, compilada con vcpkg) | macOS (de Homebrew, como bibliotecas aparte) |
|---|---|---|---|
| Tesseract 5.5.4 | Apache-2.0 | sí | sí |
| Leptonica 1.87.0 (<http://www.leptonica.org>) | BSD-2-Clause | sí | sí |
| libjpeg-turbo | IJG + BSD-3-Clause + zlib | sí | sí |
| libpng | Licencia libpng/PNG Reference Library | sí | sí |
| libtiff | Licencia libtiff (tipo BSD) | sí | sí |
| zlib | zlib | sí | (la del sistema) |
| giflib, libwebp, OpenJPEG | MIT, BSD-3-Clause, BSD-2-Clause | solo si vcpkg las agrega como funciones por defecto de leptonica (su `copyright` iría en `licencias/`) | sí |
| libarchive, xz/liblzma, zstd, lz4, libb2 | BSD-2-Clause, 0BSD, BSD-3-Clause, BSD-2-Clause, CC0-1.0 | no (Tesseract sin libarchive ni curl) | sí |

En Windows el ejecutable es estático y usa el runtime de Microsoft Visual C++ enlazado estáticamente (permitido
por la licencia de Visual Studio para redistribuir programas compilados); no lleva DLLs. En Linux el lector usa el
Tesseract de la distribución (el paquete `.deb`/`.rpm` lo declara como dependencia), que no se distribuye con el
programa. En equipos aptos y solo si la selección lo pide, el programa descarga una vez ONNX Runtime para Java
(<https://github.com/microsoft/onnxruntime>, MIT) y los modelos PP-OCRv6 de PaddleOCR
(<https://github.com/PaddlePaddle/PaddleOCR>, Apache-2.0) en la versión ONNX publicada por RapidOCR
(<https://github.com/RapidAI/RapidOCR>, Apache-2.0), primero del espejo en la release `ocr-modelos-1` de este
repositorio (copia sin cambios) y si no de la fuente original; cada archivo se verifica con su SHA-256. Las fotos
de los tickets se leen en la computadora de la tienda y no se envían a nadie.

Si falta alguna biblioteca o algún aviso en esta lista, repórtalo en
<https://github.com/Minepacman/Punto-Ventas/issues>.

## Paquetes de iconos

Además de Lucide, el programa trae paquetes de iconos opcionales en `Punto-Ventas/src/main/resources/iconos/`
(uno por archivo `.json`). Solo se incluye el subconjunto que usa el programa, con los trazos modificados:
llevados a una caja de 24×24, con círculos, rectángulos y líneas convertidos a trazos de ruta y coordenadas
redondeadas. El texto completo de cada licencia va en `iconos/LICENCIAS.txt` dentro del programa. Los genera
`Punto-Ventas/herramientas/iconos/generar-paquetes.mjs` a partir de los paquetes de npm indicados.

| Paquete | Versión (npm) | Sitio | Licencia | Copyright |
|---|---|---|---|---|
| Tabler Icons | 3.52.0 (`@tabler/icons`) | <https://tabler.io/icons> | MIT | Copyright (c) 2020-2026 Paweł Kuna |
| Phosphor Icons | 2.1.1 (`@phosphor-icons/core`) | <https://phosphoricons.com> | MIT | Copyright (c) 2023 Phosphor Icons |
| Heroicons | 2.2.0 (`heroicons`) | <https://heroicons.com> | MIT | Copyright (c) Tailwind Labs, Inc. |
| Material Symbols Rounded | 0.49.0 (`@material-symbols/svg-400`) | <https://fonts.google.com/icons> | **Apache License 2.0** | Iconos creados por Google |
| Iconoir | 7.12.2 (`iconoir`) | <https://iconoir.com> | MIT | Copyright (c) 2021 Luca Burgio |

**Nota sobre Material Symbols (Apache 2.0).** Los iconos son de Google (Material Symbols) y se toman del
paquete de npm `@material-symbols/svg-400`, que no trae archivo NOTICE; si una versión futura lo trae, se
reproduce sin cambios en `iconos/LICENCIAS.txt`. Los archivos incluidos están modificados por Caja Libre
(subconjunto de iconos, trazos normalizados a 24×24 y redondeados), como lo pide la sección 4 de la licencia.

## Letras (Configuración → Apariencia)

Las familias de letra que se pueden elegir en Configuración → Apariencia van dentro del programa
(`Punto-Ventas/src/main/resources/fuentes/`) y el acceso móvil las sirve a los teléfonos desde la propia
computadora (`/fuentes/…`, sin internet). Las tres están bajo la **SIL Open Font License 1.1**
(<https://openfontlicense.org>); el texto completo de cada una va junto a los archivos (`OFL-*.txt`).

| Familia | Versión y origen | Copyright | Cambios |
|---|---|---|---|
| Inter (Regular y Bold) | 4.1, <https://github.com/rsms/inter> (archivos estáticos `extras/ttf`) | Copyright (c) 2016 The Inter Project Authors | Subconjunto latino (español y signos comunes) con fontTools; sin nombre reservado |
| Atkinson Hyperlegible (Regular y Bold) | <https://github.com/google/fonts/tree/main/ofl/atkinsonhyperlegible> | Copyright 2020 Braille Institute of America, Inc. | Ninguno (archivos originales: la licencia reserva los nombres «Atkinson» e «Hyperlegible») |
| Nunito (Regular y Bold) | Fuente variable de <https://github.com/google/fonts/tree/main/ofl/nunito> | Copyright 2014 The Nunito Project Authors | Instancias fijas de peso 400 y 700 y subconjunto latino con fontTools; sin nombre reservado |
