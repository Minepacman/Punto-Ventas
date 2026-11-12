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
(repositorio `codigos-barras-mx`, datos de Open Food Facts bajo ODbL 1.0 con atribución); si una
versión del programa lo incluye, su aviso va junto al archivo de datos. El pronóstico del clima
viene de Open-Meteo (<https://open-meteo.com>, CC BY 4.0) cuando el usuario activa esa función.

Si falta alguna biblioteca o algún aviso en esta lista, repórtalo en
<https://github.com/Minepacman/Punto-Ventas/issues>.
