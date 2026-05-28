# 📝 ARTÍCULOS PARA BLOGGER — CodeHub Labs
## Publicar en: codehub-labs.blogspot.com
## Instrucciones: copia cada artículo en modo HTML en Blogger → Nueva entrada

---

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# ARTÍCULO 1
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
## Título:
Cómo construí mi portfolio web con HTML, CSS y JavaScript puro — sin frameworks

## Categorías/Etiquetas:
portfolio, html, css, javascript, desarrollo web, tutorial, Guatemala

## Descripción (para SEO):
Aprende cómo construí CodeHub, mi portfolio personal, usando solo HTML, CSS y JavaScript puro. Sin React, sin frameworks. Desde la red neuronal animada hasta las 23 herramientas web.

## CONTENIDO (pega esto en el editor HTML de Blogger):

<article>
<p>Cuando empecé a construir mi portfolio, me hice la pregunta que todo desarrollador junior se hace: ¿uso React, Vue, o algún framework moderno? La respuesta que elegí fue ninguno, y hoy te cuento por qué fue la mejor decisión que tomé.</p>

<h2>Por qué elegí HTML, CSS y JavaScript puro</h2>
<p>CodeHub, mi portfolio en <a href="https://wilson360-labs.vercel.app">wilson360-labs.vercel.app</a>, está construido completamente con tecnologías nativas del navegador. Sin React, sin Angular, sin Vue. Solo HTML5, CSS3 y JavaScript vanilla. Esta decisión tiene tres razones concretas:</p>
<ul>
<li><strong>Rendimiento:</strong> sin capas de abstracción, el sitio carga más rápido. No hay bundle que compilar ni hydration que esperar.</li>
<li><strong>Aprendizaje profundo:</strong> entender el DOM directamente, sin que un framework lo gestione, me enseñó mucho más sobre cómo funciona realmente el navegador.</li>
<li><strong>Control total:</strong> cada animación, cada transición, cada efecto visual lo diseñé exactamente como lo imaginé.</li>
</ul>

<h2>La red neuronal animada en Canvas</h2>
<p>El fondo del sitio muestra nodos conectados que se mueven y reaccionan al cursor del mouse. Esto está hecho con la API Canvas de HTML5. El principio es simple: crear 60 nodos con posiciones y velocidades aleatorias, moverlos en cada frame, y si dos nodos están a menos de 150px de distancia, dibujar una línea entre ellos con opacidad proporcional a esa distancia.</p>
<pre><code>
class Node {
  constructor() {
    this.x = Math.random() * canvas.width;
    this.y = Math.random() * canvas.height;
    this.speedX = (Math.random() - 0.5) * 0.5;
    this.speedY = (Math.random() - 0.5) * 0.5;
  }
}
</code></pre>
<p>El resultado es un efecto visual que se ve complejo pero tiene menos de 80 líneas de código.</p>

<h2>El splash screen y la barra de progreso</h2>
<p>El splash screen de CodeHub usa un canvas de partículas, una imagen de logo con animación de entrada, y una barra de progreso que avanza de forma fluida. El truco está en usar <code>requestAnimationFrame</code> con easing para que la barra no avance de forma lineal sino con una curva que se siente más natural.</p>
<p>La barra avanza libremente hasta el 70%, y cuando el logo termina de cargar (evento <code>onload</code> de la imagen), salta al 100% y cierra el splash. Así, si el logo está en caché, el splash dura apenas un segundo.</p>

<h2>Las 23 herramientas web</h2>
<p>Una de las partes más desafiantes fue crear más de 23 herramientas web que funcionen de forma completamente local en el navegador, sin servidor. Cada herramienta usa las APIs nativas del navegador:</p>
<ul>
<li><strong>Generador de contraseñas:</strong> usa <code>crypto.getRandomValues()</code> para máxima aleatoriedad criptográfica.</li>
<li><strong>Hash SHA-256/512:</strong> usa la Web Crypto API con <code>crypto.subtle.digest()</code>.</li>
<li><strong>Generador de QR:</strong> biblioteca externa integrada como módulo ES.</li>
<li><strong>Conversor de unidades:</strong> tabla de factores de conversión en un objeto JavaScript plano.</li>
</ul>

<h2>Lecciones aprendidas</h2>
<p>Después de más de 20,000 líneas de código en CodeHub, estas son las tres lecciones más importantes:</p>
<ol>
<li><strong>El código que entiendes completamente es mejor que el código que solo funciona.</strong> Cada vez que usas una capa de abstracción que no comprendes del todo, introduces deuda técnica.</li>
<li><strong>Las animaciones CSS son más performantes que las de JavaScript.</strong> Siempre que sea posible, usa <code>transform</code> y <code>opacity</code> en CSS en lugar de cambiar propiedades con JS.</li>
<li><strong>Los Intersection Observers cambian todo.</strong> Animar elementos solo cuando entran al viewport ahorra recursos y hace el sitio más fluido.</li>
</ol>
<p>Si quieres explorar el resultado, puedes ver CodeHub en <a href="https://wilson360-labs.vercel.app">wilson360-labs.vercel.app</a>. Si tienes preguntas sobre alguna implementación específica, déjalo en los comentarios.</p>
</article>

---

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# ARTÍCULO 2
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
## Título:
Guía práctica de Python para automatizar tareas repetitivas — desde cero

## Categorías/Etiquetas:
python, automatización, scripting, tutorial, programación, BeautifulSoup

## Descripción (para SEO):
Aprende a automatizar tareas repetitivas con Python: desde renombrar archivos hasta hacer scraping web. Guía práctica con ejemplos reales para principiantes.

## CONTENIDO:

<article>
<p>Python es el lenguaje más poderoso para automatizar tareas que haces manualmente todos los días. En este artículo voy a mostrarte ejemplos reales que uso para ahorrar horas de trabajo.</p>

<h2>¿Qué tipo de tareas puedes automatizar?</h2>
<p>Antes de ver código, identifiquemos qué vale la pena automatizar. Una buena regla es: si haces algo más de tres veces, automatízalo. Aquí algunos ejemplos concretos:</p>
<ul>
<li>Renombrar cientos de archivos según un patrón</li>
<li>Descargar imágenes o archivos de una lista de URLs</li>
<li>Enviar correos electrónicos automáticos con reportes</li>
<li>Extraer datos de páginas web (scraping)</li>
<li>Convertir archivos CSV a Excel con formato</li>
<li>Monitorear el precio de un producto en línea</li>
</ul>

<h2>Renombrar archivos en masa con Python</h2>
<p>Imagina que tienes 500 fotos llamadas <code>IMG_001.jpg</code> hasta <code>IMG_500.jpg</code> y quieres renombrarlas con la fecha actual. Con Python, esto tarda menos de 10 líneas:</p>
<pre><code>
import os
from datetime import datetime

carpeta = "mis_fotos"
fecha = datetime.now().strftime("%Y-%m-%d")

for i, archivo in enumerate(os.listdir(carpeta)):
    if archivo.endswith(".jpg"):
        nuevo_nombre = f"{fecha}_foto_{i+1:03d}.jpg"
        os.rename(
            os.path.join(carpeta, archivo),
            os.path.join(carpeta, nuevo_nombre)
        )

print("¡Listo! Archivos renombrados.")
</code></pre>

<h2>Descargar imágenes de una lista de URLs</h2>
<p>Con la librería <code>requests</code>, puedes descargar archivos de internet de forma automática. Este script descarga todas las imágenes de una lista:</p>
<pre><code>
import requests
import os

urls = [
    "https://ejemplo.com/imagen1.jpg",
    "https://ejemplo.com/imagen2.jpg",
]

os.makedirs("descargas", exist_ok=True)

for i, url in enumerate(urls):
    respuesta = requests.get(url)
    nombre = f"descargas/imagen_{i+1}.jpg"
    with open(nombre, "wb") as f:
        f.write(respuesta.content)
    print(f"Descargado: {nombre}")
</code></pre>

<h2>Monitorear el precio de un producto en Amazon</h2>
<p>Con BeautifulSoup puedes hacer scraping de precios y recibir una alerta cuando baje. Primero instala las dependencias:</p>
<pre><code>pip install requests beautifulsoup4</code></pre>
<p>Luego el script:</p>
<pre><code>
import requests
from bs4 import BeautifulSoup

URL = "https://www.amazon.com/dp/TU_PRODUCTO_ID"
PRECIO_OBJETIVO = 50.00

cabeceras = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
}

respuesta = requests.get(URL, headers=cabeceras)
soup = BeautifulSoup(respuesta.content, "html.parser")

precio_texto = soup.find("span", {"class": "a-price-whole"}).get_text()
precio = float(precio_texto.replace(",", ""))

if precio <= PRECIO_OBJETIVO:
    print(f"¡Alerta! El precio bajó a ${precio}")
else:
    print(f"Precio actual: ${precio}. Objetivo: ${PRECIO_OBJETIVO}")
</code></pre>

<h2>Automatizar el envío de correos</h2>
<p>Con la librería <code>smtplib</code> de Python (incluida en la instalación estándar), puedes enviar correos automáticamente con reportes o alertas:</p>
<pre><code>
import smtplib
from email.mime.text import MIMEText

def enviar_correo(destinatario, asunto, mensaje):
    remitente = "tu@gmail.com"
    password = "tu_contraseña_de_aplicacion"
    
    msg = MIMEText(mensaje)
    msg["Subject"] = asunto
    msg["From"] = remitente
    msg["To"] = destinatario
    
    with smtplib.SMTP_SSL("smtp.gmail.com", 465) as smtp:
        smtp.login(remitente, password)
        smtp.send_message(msg)
    
    print(f"Correo enviado a {destinatario}")

enviar_correo(
    "cliente@ejemplo.com",
    "Reporte diario",
    "El proceso automatizado se completó exitosamente."
)
</code></pre>
<p><strong>Nota:</strong> para Gmail, necesitas crear una "Contraseña de aplicación" en la configuración de tu cuenta de Google, no usar tu contraseña normal.</p>

<h2>Próximos pasos</h2>
<p>Estos son solo ejemplos básicos. Python puede ir mucho más lejos: integraciones con APIs, procesamiento de PDFs, control de navegadores con Selenium, análisis de datos con Pandas. Si quieres que cubra alguno de estos temas en profundidad, déjalo en los comentarios.</p>
</article>

---

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# ARTÍCULO 3
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
## Título:
Cómo integrar una IA en tu sitio web con JavaScript — sin backend propio

## Categorías/Etiquetas:
inteligencia artificial, javascript, API, chatbot, tutorial, Groq, Gemini

## Descripción (para SEO):
Tutorial paso a paso para integrar un chatbot con IA en tu sitio web usando solo JavaScript. Aprende a conectarte a APIs de Groq, Gemini y otras sin necesitar servidor propio.

## CONTENIDO:

<article>
<p>Uno de los proyectos más interesantes que he construido es el chatbot EMI IA de CodeHub. En este artículo te explico cómo integrar inteligencia artificial en cualquier sitio web, usando solo JavaScript y una API de IA.</p>

<h2>¿Qué necesitas?</h2>
<ul>
<li>Una cuenta en algún proveedor de IA (Groq, Google Gemini, Anthropic, o OpenRouter)</li>
<li>Conocimientos básicos de JavaScript y fetch</li>
<li>Un servidor backend simple (o un proxy serverless) para proteger tu API key</li>
</ul>
<p><strong>Importante:</strong> nunca expongas tu API key directamente en el JavaScript del frontend, porque cualquiera puede verla. Siempre usa un servidor intermedio o una función serverless.</p>

<h2>Estructura básica de un chatbot</h2>
<p>Un chatbot tiene tres componentes principales: la interfaz de usuario (el chat), el manejo del historial de conversación, y la llamada a la API de IA. El historial es crucial: necesitas enviarlo en cada request para que la IA recuerde el contexto de la conversación.</p>
<pre><code>
// Historial de conversación (memoria del chat)
let historial = [];

async function enviarMensaje(textUsuario) {
  // Agregar mensaje del usuario al historial
  historial.push({
    role: "user",
    content: textUsuario
  });
  
  // Llamar al backend (que llama a la API de IA)
  const respuesta = await fetch("/api/chat", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      messages: historial
    })
  });
  
  const datos = await respuesta.json();
  const textoIA = datos.reply;
  
  // Agregar respuesta al historial
  historial.push({
    role: "assistant",
    content: textoIA
  });
  
  return textoIA;
}
</code></pre>

<h2>El backend con Node.js y Groq</h2>
<p>Groq es una excelente opción para empezar porque tiene un plan gratuito generoso y es extremadamente rápido. El backend puede ser tan simple como esto:</p>
<pre><code>
const express = require("express");
const Groq = require("groq-sdk");

const app = express();
const groq = new Groq({ apiKey: process.env.GROQ_API_KEY });

app.use(express.json());

app.post("/api/chat", async (req, res) => {
  const { messages } = req.body;
  
  const completion = await groq.chat.completions.create({
    model: "llama3-8b-8192",
    messages: [
      {
        role: "system",
        content: "Eres un asistente útil para programadores."
      },
      ...messages
    ],
    max_tokens: 1000
  });
  
  res.json({ reply: completion.choices[0].message.content });
});

app.listen(3000);
</code></pre>

<h2>Sistema de prompts para personalizar la IA</h2>
<p>El mensaje de sistema (system prompt) es donde defines la personalidad y conocimiento de tu IA. Para EMI IA de CodeHub, el system prompt describe quién es Wilson.E, qué herramientas tiene el sitio, y cómo debe responder. Cuanto más específico y detallado sea este prompt, mejor se comportará el chatbot.</p>

<h2>Fallback offline inteligente</h2>
<p>Si el servidor no está disponible, en lugar de mostrar un error genérico, puedes tener un sistema de respuestas predefinidas basadas en palabras clave. Analiza el mensaje del usuario y devuelve la respuesta más relevante de tu base de conocimientos local. Así el chatbot sigue siendo útil aunque la IA no responda.</p>

<h2>Consideraciones de uso y costos</h2>
<p>La mayoría de APIs de IA cobran por token (unidad de texto procesado). Para un sitio personal con uso moderado, los costos suelen ser muy bajos. Groq ofrece un tier gratuito de 14,400 tokens por minuto con los modelos Llama, lo que es más que suficiente para un portfolio personal.</p>
<p>Implementa siempre un límite de mensajes por sesión (en CodeHub son 30) para evitar abuso.</p>
</article>

---

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# ARTÍCULO 4
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
## Título:
MongoDB + Node.js + Railway: tu base de datos en la nube gratis en 30 minutos

## Categorías/Etiquetas:
MongoDB, Node.js, Railway, base de datos, backend, deploy, tutorial

## Descripción (para SEO):
Aprende a desplegar una aplicación Node.js con MongoDB en Railway de forma gratuita. Tutorial paso a paso con variables de entorno, conexión y primer CRUD.

## CONTENIDO:

<article>
<p>En CodeHub tengo un backend que registra visitas, gestiona el chat de IA y sirve datos al frontend. Está construido con Node.js, usa MongoDB Atlas como base de datos, y está desplegado en Railway. En este artículo te muestro cómo hacer lo mismo.</p>

<h2>¿Por qué Railway?</h2>
<p>Railway es una plataforma de hosting que tiene un plan gratuito de $5 al mes en créditos, lo que es suficiente para proyectos pequeños. La ventaja sobre Heroku (que eliminó su tier gratuito) es que Railway detecta automáticamente tu proyecto Node.js, instala las dependencias y lo despliega sin configuración complicada.</p>

<h2>Paso 1: Crear tu proyecto Node.js</h2>
<p>Crea una carpeta para tu proyecto e inicializa con npm:</p>
<pre><code>
mkdir mi-backend && cd mi-backend
npm init -y
npm install express mongoose dotenv cors
</code></pre>
<p>Crea el archivo principal <code>server.js</code>:</p>
<pre><code>
require("dotenv").config();
const express = require("express");
const mongoose = require("mongoose");
const cors = require("cors");

const app = express();
app.use(cors());
app.use(express.json());

// Conectar a MongoDB
mongoose.connect(process.env.MONGODB_URI)
  .then(() => console.log("MongoDB conectado"))
  .catch(err => console.error("Error MongoDB:", err));

// Modelo simple de visita
const Visita = mongoose.model("Visita", new mongoose.Schema({
  pagina: String,
  fecha: { type: Date, default: Date.now },
  ip: String
}));

// Endpoint para registrar visita
app.post("/api/visit", async (req, res) => {
  const { pagina, ip } = req.body;
  await Visita.create({ pagina, ip });
  res.json({ ok: true });
});

// Endpoint para obtener estadísticas
app.get("/api/stats", async (req, res) => {
  const total = await Visita.countDocuments();
  res.json({ total });
});

app.listen(process.env.PORT || 3000, () => {
  console.log("Servidor corriendo");
});
</code></pre>

<h2>Paso 2: MongoDB Atlas (base de datos gratuita)</h2>
<p>MongoDB Atlas ofrece un cluster gratuito de 512MB, más que suficiente para proyectos personales. Los pasos son:</p>
<ol>
<li>Crea cuenta en <strong>mongodb.com/atlas</strong></li>
<li>Crea un cluster gratuito (M0)</li>
<li>En "Database Access", crea un usuario con contraseña</li>
<li>En "Network Access", agrega <code>0.0.0.0/0</code> para permitir acceso desde Railway</li>
<li>En "Connect", copia la cadena de conexión: <code>mongodb+srv://usuario:contraseña@cluster.mongodb.net/midb</code></li>
</ol>

<h2>Paso 3: Desplegar en Railway</h2>
<ol>
<li>Sube tu código a GitHub</li>
<li>En Railway, haz click en "New Project" → "Deploy from GitHub repo"</li>
<li>Selecciona tu repositorio</li>
<li>En la pestaña "Variables", agrega: <code>MONGODB_URI = tu_cadena_de_conexion</code></li>
<li>Railway detectará automáticamente que es Node.js y ejecutará <code>npm start</code></li>
</ol>
<p>En menos de 2 minutos, tu backend estará disponible en una URL como <code>mi-backend.railway.app</code>.</p>

<h2>Conectar tu frontend al backend</h2>
<p>Desde tu JavaScript del frontend, usa fetch con la URL de Railway:</p>
<pre><code>
const BACKEND = "https://mi-backend.railway.app";

// Registrar visita
await fetch(`${BACKEND}/api/visit`, {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ pagina: "/" })
});

// Obtener estadísticas
const stats = await fetch(`${BACKEND}/api/stats`).then(r => r.json());
console.log(`Total visitas: ${stats.total}`);
</code></pre>

<h2>Tips finales</h2>
<ul>
<li>Usa siempre variables de entorno para credenciales, nunca las pongas directo en el código.</li>
<li>Agrega un archivo <code>.gitignore</code> que excluya el archivo <code>.env</code>.</li>
<li>Para producción, usa MongoDB Atlas con backups automáticos habilitados.</li>
<li>Railway apaga los servidores inactivos en el plan gratuito. Para mantenerlo activo, considera hacer un ping cada 14 minutos desde un servicio como UptimeRobot.</li>
</ul>
</article>

---

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# ARTÍCULO 5
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
## Título:
CSS avanzado sin frameworks: técnicas que uso en mis proyectos reales

## Categorías/Etiquetas:
css, diseño web, frontend, animaciones, css custom properties, tutorial

## Descripción (para SEO):
Aprende técnicas avanzadas de CSS que uso en CodeHub: conic-gradient, mask, container queries, custom properties y animaciones complejas sin depender de Tailwind o Bootstrap.

## CONTENIDO:

<article>
<p>Una de las preguntas que me hacen frecuentemente es: ¿por qué no usas Tailwind? La respuesta es que prefiero entender CSS profundamente antes de usar una capa de abstracción. Aquí están las técnicas que más uso en mis proyectos.</p>

<h2>CSS Custom Properties (variables CSS)</h2>
<p>Las variables CSS son la base de cualquier sistema de diseño mantenible. En CodeHub, todas las decisiones de color, espaciado y tipografía viven en variables:</p>
<pre><code>
:root {
  --primary: #ff4500;
  --accent: #ffbd69;
  --bg: #050510;
  --surface: rgba(255,255,255,.04);
  --border: rgba(255,69,0,.18);
  --text: #e8e8f0;
  --text-secondary: rgba(232,232,240,.55);
  --radius: 14px;
  --transition: all .2s cubic-bezier(.2,.8,.2,1);
}
</code></pre>
<p>Cambiar el tema completo de un sitio es tan simple como actualizar estas variables en JavaScript: <code>document.documentElement.style.setProperty('--primary', '#0066ff')</code>.</p>

<h2>Conic-gradient para anillos giratarios</h2>
<p>El anillo que gira alrededor de la foto de perfil en CodeHub usa conic-gradient con mask. Esta combinación permite crear bordes con gradiente de color que antes solo eran posibles con Canvas:</p>
<pre><code>
.anillo-giratorio {
  background: conic-gradient(
    from 0deg,
    #ff4500, #ff6b35, #ffbd69,
    #ff8c00, #ff4500
  );
  animation: girar 3.5s linear infinite;
  /* La magia: mask recorta solo el borde */
  -webkit-mask: radial-gradient(
    farthest-side,
    transparent calc(100% - 11px),
    black calc(100% - 11px)
  );
  mask: radial-gradient(
    farthest-side,
    transparent calc(100% - 11px),
    black calc(100% - 11px)
  );
}

@keyframes girar {
  to { transform: rotate(360deg); }
}
</code></pre>

<h2>Glassmorphism real vs glassmorphism falso</h2>
<p>El glassmorphism (cristal esmerilado) se logra con <code>backdrop-filter: blur()</code>. El error común es aplicarlo sobre un fondo sólido, donde no se ve el efecto. Necesitas que haya contenido "detrás" del elemento:</p>
<pre><code>
.card-glass {
  background: rgba(255,255,255,.06);
  border: 1px solid rgba(255,255,255,.12);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  border-radius: 16px;
}
</code></pre>

<h2>Animaciones con animation-delay escalonado</h2>
<p>Para animar una lista de elementos de forma escalonada, en lugar de JavaScript, usa CSS con custom properties y un bucle generado desde JS o directamente con nth-child:</p>
<pre><code>
.skill-chip {
  opacity: 0;
  transform: translateY(20px);
  animation: fadeUp .5s ease forwards;
}

.skill-chip:nth-child(1) { animation-delay: .1s; }
.skill-chip:nth-child(2) { animation-delay: .2s; }
.skill-chip:nth-child(3) { animation-delay: .3s; }

@keyframes fadeUp {
  to { opacity: 1; transform: translateY(0); }
}
</code></pre>

<h2>Container Queries — el futuro del diseño responsive</h2>
<p>Las container queries permiten aplicar estilos basados en el tamaño del contenedor padre, no del viewport. Son perfectas para componentes reutilizables:</p>
<pre><code>
.card-container {
  container-type: inline-size;
}

@container (min-width: 400px) {
  .card {
    display: grid;
    grid-template-columns: 1fr 2fr;
  }
}
</code></pre>

<h2>Clip-path para formas irregulares</h2>
<p>Con <code>clip-path</code> puedes crear secciones con bordes diagonales o curvas sin usar SVG:</p>
<pre><code>
.seccion-diagonal {
  clip-path: polygon(0 0, 100% 0, 100% 92%, 0 100%);
  background: var(--surface);
  padding: 4rem 2rem 6rem;
}
</code></pre>
<p>Estas son solo algunas de las técnicas que uso. CSS nativo en 2025 es increíblemente poderoso. Antes de agregar un framework, pregúntate si realmente lo necesitas.</p>
</article>

---

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# ARTÍCULO 6
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
## Título:
Cómo conseguir tus primeros clientes freelance como desarrollador web en Guatemala

## Categorías/Etiquetas:
freelance, Guatemala, desarrollador web, clientes, negocios, consejos

## Descripción (para SEO):
Guía práctica para conseguir tus primeros clientes como desarrollador freelance en Guatemala. Dónde buscarlos, cómo cotizar proyectos web y qué errores evitar al inicio.

## CONTENIDO:

<article>
<p>Cuando empecé como desarrollador freelance en Guatemala, cometí todos los errores clásicos: cobrar demasiado poco, aceptar proyectos sin contrato, y no tener un portfolio claro. Aquí comparto lo que aprendí en el camino.</p>

<h2>La realidad del mercado freelance en Guatemala</h2>
<p>Guatemala tiene un mercado freelance creciente pero aún inmaduro en cuanto a tecnología web. Esto es bueno y malo al mismo tiempo: hay menos competencia que en México o Colombia, pero también hay menos cultura de pagar precios justos por trabajo digital. La clave es posicionarte bien desde el inicio.</p>

<h2>Dónde encontrar tus primeros clientes</h2>
<h3>1. Tu red cercana (la más subestimada)</h3>
<p>Tus primeros 3-5 clientes probablemente vendrán de personas que ya te conocen. Avisa en tus redes sociales que ofreces servicios de desarrollo web. Muchos negocios pequeños en Guatemala tienen páginas de Facebook desactualizadas o sitios web que cargan en 10 segundos, y están dispuestos a pagar por mejorarlos.</p>

<h3>2. Grupos de Facebook de negocios guatemaltecos</h3>
<p>Existen grupos específicos de emprendedores guatemaltecos donde puedes ofrecer tus servicios. No spam, sino valor: responde preguntas técnicas, ofrece análisis gratuitos de sitios web, y cuando alguien pregunte por un desarrollador, preséntate.</p>

<h3>3. LinkedIn con perfil optimizado</h3>
<p>Muchas empresas medianas en Guatemala buscan desarrolladores en LinkedIn. Un perfil con tu foto profesional, proyectos de portfolio, y recomendaciones de clientes anteriores puede generar leads inbound (que te busquen a ti).</p>

<h3>4. Plataformas internacionales (Upwork, Workana)</h3>
<p>Para trabajar con clientes de otros países, Upwork y Workana son las mejores opciones desde Guatemala. Al inicio, acepta proyectos pequeños para construir reputación, aunque paguen menos. La reputación en estas plataformas es tu activo más valioso.</p>

<h2>Cómo cotizar proyectos</h2>
<p>Este es el tema donde más desarrolladores novatos cometen errores. Una fórmula básica que funciona:</p>
<ul>
<li><strong>Estima las horas reales de trabajo</strong> (incluye reuniones, correcciones y despliegue)</li>
<li><strong>Multiplica por tu tarifa por hora</strong> (para Guatemala, entre Q75-Q200/hora dependiendo de la complejidad)</li>
<li><strong>Agrega un 20% de margen</strong> por imprevistos</li>
<li><strong>Presenta siempre tres opciones:</strong> básico, estándar y premium</li>
</ul>
<p>Una landing page sencilla en Guatemala puede costar entre Q1,500 y Q4,000. Un sitio web completo con panel de administración, entre Q5,000 y Q20,000. No cobres menos de Q500 por ningún proyecto, por pequeño que sea, porque establece un precedente negativo.</p>

<h2>El contrato es obligatorio</h2>
<p>Nunca empieces a trabajar sin un contrato, ni con amigos ni familiares. El contrato no necesita ser complicado: puede ser un documento de Word con los puntos básicos:</p>
<ul>
<li>Descripción detallada del trabajo a realizar</li>
<li>Precio total y forma de pago (50% al inicio, 50% al entregar)</li>
<li>Plazo de entrega</li>
<li>Número de revisiones incluidas</li>
<li>Qué pasa si el cliente no entrega los materiales a tiempo</li>
</ul>

<h2>Construye tu portfolio antes de buscar clientes</h2>
<p>Tu portfolio es tu herramienta de ventas más importante. Si no tienes clientes aún, crea proyectos ficticios pero realistas: un sitio para una restaurante inventado, una tienda online de ejemplo, un dashboard de datos. Lo importante es mostrar lo que puedes hacer.</p>
<p>Mi portfolio en <a href="https://wilson360-labs.vercel.app">wilson360-labs.vercel.app</a> fue fundamental para conseguir mis primeros clientes reales. Tardé meses en construirlo, pero fue la mejor inversión de tiempo que hice.</p>

<h2>El error más costoso: no cobrar por adelantado</h2>
<p>El 70% de los problemas con clientes freelance se resuelven cobrando el 50% al inicio. Si un cliente no quiere pagar nada por adelantado, es una señal de alerta. Los clientes serios entienden que el trabajo tiene un costo desde el primer día.</p>
<p>¿Tienes preguntas sobre freelancing en Guatemala? Déjalas en los comentarios y con gusto respondo.</p>
</article>

---

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# INSTRUCCIONES PARA PUBLICAR EN BLOGGER
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

1. Ve a: blogger.com → codehub-labs.blogspot.com → Nueva entrada
2. Escribe el TÍTULO del artículo
3. Haz click en "Vista HTML" (en el editor)
4. Pega el CONTENIDO del artículo (solo el HTML, sin las instrucciones)
5. Agrega las ETIQUETAS separadas por comas
6. En "Permalink", pon una URL amigable (ej: /como-construi-mi-portfolio)
7. En "Descripción de búsqueda", pega la DESCRIPCIÓN (para SEO)
8. Publica

ORDEN RECOMENDADO:
- Publicar primero los artículos 1 y 2 (son los más técnicos y de mejor calidad)
- Esperar 2-3 días entre cada publicación
- Google indexa mejor si hay constancia, no ráfagas de contenido
