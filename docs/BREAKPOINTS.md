# Convención de breakpoints — CodeHub

> Regla fija del proyecto. Cualquier corrección de CSS/JS que toque
> comportamiento "móvil" debe respetar este rango, para evitar bugs de
> compatibilidad como el del botón "EXPERIMENTAL" saltando de lugar
> (ver `css/site-tour.css` y `js/site-tour.js`, corregido el 2026-07-29).

## Rango móvil

- **Móvil:** `380px` – `~720px` de ancho de viewport.
  - `380px` es el mínimo soportado (dispositivos angostos tipo iPhone SE).
  - `720px` es el techo aproximado; a partir de ahí se considera tablet/desktop.
- **Ejemplo de media query estándar:**
  ```css
  @media (min-width: 380px) and (max-width: 720px) {
    /* estilos específicos de móvil */
  }
  ```
  o, cuando solo se necesita "es móvil o más chico":
  ```css
  @media (max-width: 720px) {
    /* estilos de móvil (incluye pantallas angostas desde 380px) */
  }
  ```

## Por qué esta regla existe

Antes de esta convención, distintos archivos usaban breakpoints
inconsistentes (`480px`, `500px`, `550px`, `640px`, `768px`, `860px`...),
lo que provocaba que un elemento se tratara como "desktop" en un archivo
y como "mobile" en otro, generando bugs visuales difíciles de rastrear
(elementos que cambian de posición, se superponen o pierden estilos
solo en ciertos anchos).

**A partir de ahora:**
- Todo nuevo componente o corrección debe usar `380px`–`720px` como el
  rango oficial de "móvil".
- Si un archivo ya tiene un breakpoint distinto (ej. `768px`) y se está
  tocando por otro motivo, ajustarlo a `720px` si es razonable hacerlo
  sin romper nada, o dejar un comentario indicando por qué no se pudo.
- No introducir nuevos breakpoints "de una sola vez" (ej. `700px`,
  `715px`) sin buena razón — usar el estándar del proyecto.

## Bug de referencia (para no repetirlo)

El tour guiado (`site-tour.js`) resaltaba elementos añadiendo la clase
`.st-highlight`, que forzaba `position: relative` sin importar el
`position` original del elemento. Esto rompía el botón flotante
`.menu-toggle-btn` (`position: fixed`), sacándolo de su lugar y
haciéndolo "saltar" arriba del todo — visible tanto en móvil como en
desktop porque el problema no era de tamaño de pantalla, sino de que
el JS pisaba un `position` ya definido.

**Regla general derivada de este bug:** nunca sobrescribir `position`
de un elemento por CSS/JS genérico sin antes comprobar su
`position` computado (`getComputedStyle(el).position`). Si ya es
`fixed`, `absolute` o `sticky`, no tocarlo.
